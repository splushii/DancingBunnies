package se.splushii.dancingbunnies.audioplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaBrowserServiceCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.app.NotificationCompat.MediaStyle;
import android.support.v4.media.session.MediaButtonReceiver;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import se.splushii.dancingbunnies.MainActivity;
import se.splushii.dancingbunnies.R;
import se.splushii.dancingbunnies.musiclibrary.EntryID;
import se.splushii.dancingbunnies.musiclibrary.LibraryEntry;
import se.splushii.dancingbunnies.musiclibrary.Meta;
import se.splushii.dancingbunnies.musiclibrary.MusicLibraryService;
import se.splushii.dancingbunnies.musiclibrary.MusicLibraryQuery;
import se.splushii.dancingbunnies.musiclibrary.PlaylistID;
import se.splushii.dancingbunnies.musiclibrary.PlaylistItem;
import se.splushii.dancingbunnies.util.Util;

// TODO: Handle audio output switching. AudioManager.ACTION_AUDIO_BECOMING_NOISY.
// TODO: Handle incoming call. PhoneStateListener.LISTEN_CALL_STATE.
public class AudioPlayerService extends MediaBrowserServiceCompat {
    private static String LC = Util.getLogContext(AudioPlayerService.class);
    private static final String NOTIFICATION_CHANNEL_ID = "dancingbunnies.notification.channel";
    private static final String NOTIFICATION_CHANNEL_NAME = "DancingBunnies";
    private static final int SERVICE_NOTIFICATION_ID = 1337;
    private static final float PLAYBACK_SPEED_PAUSED = 0f;
    private static final float PLAYBACK_SPEED_PLAYING = 1f;
    public static final String MEDIA_ID_ROOT = "dancingbunnies.media.id.root";

    public static final String SESSION_EVENT_PLAYLIST_POSITION_CHANGED = "PLAYLIST_POSITION_CHANGED";
    public static final String SESSION_EVENT_PLAYLIST_CHANGED = "PLAYLIST_CHANGED";

    public static final String COMMAND_GET_PLAYLISTS = "GET_PLAYLISTS";
    public static final String COMMAND_GET_CURRENT_PLAYLIST = "GET_CURRENT_PLAYLIST";
    public static final String COMMAND_GET_PLAYLIST_ENTRIES = "GET_PLAYLIST_ENTRIES";
    public static final String COMMAND_GET_PLAYLIST_NEXT = "GET_PLAYLIST_NEXT";
    public static final String COMMAND_GET_PLAYLIST_PREVIOUS = "GET_PLAYLIST_PREVIOUS";
    public static final String COMMAND_ADD_TO_PLAYLIST = "ADD_TO_PLAYLIST";
    public static final String COMMAND_GET_CURRENT_PLAYBACK_ENTRY = "GET_CURRENT_PLAYBACK_ENTRY";
    private static final String COMMAND_REMOVE_FROM_PLAYLIST = "REMOVE_FROM_PLAYLIST";

    public static final String STARTCMD_INTENT_CAST_ACTION = "dancingbunnies.intent.castaction";

    public static final String CAST_ACTION_TOGGLE_PLAYBACK = "TOGGLE_PLAYBACK";
    public static final String CAST_ACTION_NEXT = "NEXT";
    public static final String CAST_ACTION_PREVIOUS = "PREVIOUS";

    private PlaybackController playbackController;
    private final PlaybackController.Callback audioPlayerManagerCallback = new PlaybackCallback();
    private MediaSessionCompat mediaSession;
    private PlaybackStateCompat.Builder playbackStateBuilder;
    private PlaybackStateCompat playbackState;
    private boolean notify = true;
    private String currentParentId = MEDIA_ID_ROOT;

    private MusicLibraryService musicLibraryService;
    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(LC, "Connected MusicLibraryService");
            MusicLibraryService.MusicLibraryBinder binder = (MusicLibraryService.MusicLibraryBinder) service;
            musicLibraryService = binder.getService();
            setupPlaybackController();
            setupMediaSession();
            playbackController.update();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            musicLibraryService = null;
            Log.d(LC, "Disconnected from MusicLibraryService");
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(LC, "onBind");
        return super.onBind(intent);
    }

    @Nullable
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName,
                                 int clientUid,
                                 @Nullable Bundle rootHints) {
        return new BrowserRoot(MEDIA_ID_ROOT, null);
    }

    @Override
    public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        Bundle options = new Bundle();
        onLoadChildren(parentId, result, options);
    }

    @Override
    public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result, @NonNull Bundle options) {
        List<MediaBrowserCompat.MediaItem> mediaItems = new ArrayList<>();
        // TODO: Put EntryID.key() in parentId. Make a EntryID.from(key).
        // Make a EntryID.toMusicLibraryQueryOptions()
        // parentId is now usable!
        Log.d(LC, "onLoadChildren parentId: " + parentId);
        currentParentId = parentId;
        List<LibraryEntry> entries = musicLibraryService.getSubscriptionEntries(new MusicLibraryQuery(options));
        Log.d(LC, "onLoadChildren entries: " + entries.size());
        Collections.sort(entries);
        for (LibraryEntry entry: entries) {
            MediaBrowserCompat.MediaItem item = generateMediaItem(entry);
            mediaItems.add(item);
        }
        Log.d(LC, "onLoadChildren mediaItems: " + mediaItems.size());
        result.sendResult(mediaItems);
    }

    @Override
    public void onSearch(@NonNull String query, Bundle extras, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        List<MediaBrowserCompat.MediaItem> mediaItems = new ArrayList<>();
        List<LibraryEntry> entries = musicLibraryService.getSearchEntries(query);
        for (LibraryEntry entry: entries) {
            MediaBrowserCompat.MediaItem item = generateMediaItem(entry);
            mediaItems.add(item);
        }
        result.sendResult(mediaItems);
    }

    private MediaBrowserCompat.MediaItem generateMediaItem(LibraryEntry entry) {
        EntryID entryID = entry.entryID;
        Bundle extras = entryID.toBundle();
        if (entryID.type.equals(Meta.METADATA_KEY_MEDIA_ID)) {
            MediaMetadataCompat meta = musicLibraryService.getSongMetaData(entryID);
            extras.putAll(meta.getBundle());
        }
        MediaDescriptionCompat desc = new MediaDescriptionCompat.Builder()
                .setMediaId(entryID.key())
                .setExtras(extras)
                .setTitle(entry.name())
                .build();
        int flags = MediaBrowserCompat.MediaItem.FLAG_PLAYABLE;
        switch(entryID.type) {
            case Meta.METADATA_KEY_MEDIA_ID:
                break;
            default:
                flags |= MediaBrowserCompat.MediaItem.FLAG_BROWSABLE;
                break;
        }
        return new MediaBrowserCompat.MediaItem(desc, flags);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(LC, "onStartCommand");
        if (intent != null && intent.hasExtra(STARTCMD_INTENT_CAST_ACTION)) {
            handleCastIntent(intent);
        } else {
            MediaButtonReceiver.handleIntent(mediaSession, intent);
        }
        return START_STICKY;
    }

    private void handleCastIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        Bundle extras = intent.getExtras();
        if (extras == null) {
            return;
        }
        String action = extras.getString(STARTCMD_INTENT_CAST_ACTION);
        if (action == null) {
            return;
        }
        Log.d(LC, "CAST_ACTION: " + action);
        switch (action) {
            case CAST_ACTION_TOGGLE_PLAYBACK:
                playbackController.playPause();
                break;
            case CAST_ACTION_PREVIOUS:
                playbackController.skipToPrevious();
                break;
            case CAST_ACTION_NEXT:
                playbackController.skipToNext();
                break;
            default:
                break;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(LC, "onCreate");

        bindService(
                new Intent(this, MusicLibraryService.class),
                serviceConnection,
                Context.BIND_AUTO_CREATE
        );

        NotificationChannel notificationChannel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        assert notificationManager != null;
        notificationManager.createNotificationChannel(notificationChannel);
    }

    @Override
    public void onDestroy() {
        Log.d(LC, "onDestroy");
        playbackController.onDestroy();
        unbindService(serviceConnection);
        musicLibraryService = null;
        mediaSession.release();
        super.onDestroy();
    }

    private void setupPlaybackController() {
        playbackController = new PlaybackController(
                this, musicLibraryService, audioPlayerManagerCallback
        );
    }

    private void setupMediaSession() {
        mediaSession = new MediaSessionCompat(this, "AudioPlayerService");
        setSessionToken(mediaSession.getSessionToken());
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
                | MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS);
        playbackStateBuilder = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE
                        | PlaybackStateCompat.ACTION_STOP
                        | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                        | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM)
                .setState(PlaybackStateCompat.STATE_NONE, 0, PLAYBACK_SPEED_PAUSED);
        playbackState = playbackStateBuilder.build();
        mediaSession.setPlaybackState(playbackState);
        mediaSession.setCallback(new MediaSessionCallback());

        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.PAGER_SELECTION, MainActivity.PAGER_NOWPLAYING);
        PendingIntent pi = PendingIntent.getActivity(this, 99 /*request code*/,
                intent, PendingIntent.FLAG_UPDATE_CURRENT);
        mediaSession.setSessionActivity(pi);
        mediaSession.setMetadata(Meta.UNKNOWN_ENTRY);

        mediaSession.setQueue(new LinkedList<>());
    }

    private boolean isStoppedState() {
        int state = playbackState.getState();
        return state == PlaybackStateCompat.STATE_ERROR
                || state == PlaybackStateCompat.STATE_NONE
                || state == PlaybackStateCompat.STATE_STOPPED;
    }

    private boolean isPlayingState() {
        int state = playbackState.getState();
        return state == PlaybackStateCompat.STATE_PLAYING;
    }

    private void setNotification() {
        if (!notify || isStoppedState()) {
            stopForeground(true);
            return;
        }
        String play_pause_string;
        int play_pause_drawable;
        switch (playbackState.getState()) {
            case PlaybackState.STATE_PLAYING:
            case PlaybackState.STATE_BUFFERING:
                play_pause_string = getString(R.string.pause);
                play_pause_drawable = R.drawable.ic_pause;
                break;
            default:
                play_pause_string = getString(R.string.play);
                play_pause_drawable = R.drawable.ic_play;
                break;
        }
        NotificationCompat.Action action_play_pause = new NotificationCompat.Action.Builder(
                play_pause_drawable,
                play_pause_string,
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this,
                        PlaybackStateCompat.ACTION_PLAY_PAUSE
                )
        ).build();
        NotificationCompat.Action action_stop = new NotificationCompat.Action.Builder(
                R.drawable.ic_stop,
                getString(R.string.stop),
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this,
                        PlaybackStateCompat.ACTION_STOP
                )
        ).build();
        NotificationCompat.Action action_next = new NotificationCompat.Action.Builder(
                R.drawable.ic_next,
                getString(R.string.next),
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this,
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                )
        ).build();
        NotificationCompat.Action action_previous = new NotificationCompat.Action.Builder(
                R.drawable.ic_prev,
                getString(R.string.previous),
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this,
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
        ).build();
        MediaStyle style = new MediaStyle()
                .setMediaSession(mediaSession.getSessionToken())
                .setShowActionsInCompactView(1, 3);
        MediaControllerCompat controller = mediaSession.getController();
        String description = Meta.getLongDescription(controller.getMetadata());
        Notification notification =
                new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                        .setContentTitle(description)
                        .setContentText(description)
                        .setLargeIcon(controller.getMetadata().getDescription().getIconBitmap())
                        .setSmallIcon(R.drawable.ic_play)
                        .setContentIntent(controller.getSessionActivity())
                        .addAction(action_previous)
                        .addAction(action_play_pause)
                        .addAction(action_next)
                        .addAction(action_stop)
                        .setStyle(style)
                        .build();
        startForeground(SERVICE_NOTIFICATION_ID, notification);
    }

    private class MediaSessionCallback extends MediaSessionCompat.Callback {
        @Override
        public void onPlay() {
            Log.d(LC, "onPlay");
            playbackController.play();
        }

        @Override
        public void onPause() {
            Log.d(LC, "onPause");
            playbackController.pause();
        }

        @Override
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            Log.d(LC, "onPlayFromMediaId");
            PlaybackEntry playbackEntry = getPlaybackEntry(EntryID.from(extras));
            playbackController.playNow(playbackEntry);
            setToast(playbackEntry.meta, "Playing %s \"%s\" now!");
        }

        @Override
        public void onAddQueueItem(MediaDescriptionCompat description) {
            Log.d(LC, "onAddQueueItem");
            PlaybackEntry playbackEntry = getPlaybackEntry(EntryID.from(description));
            playbackController.addToQueue(playbackEntry, PlaybackQueue.QueueOp.LAST);
            setToast(playbackEntry.meta, "Adding %s \"%s\" to queue!");
        }

        private void setToast(MediaMetadataCompat meta, String format) {
            String entryType = meta.getString(Meta.METADATA_KEY_TYPE);
            if (Meta.METADATA_KEY_MEDIA_ID.equals(entryType)) {
                entryType = Meta.METADATA_KEY_TITLE;
            }
            String title = meta.getString(entryType);
            String type = Meta.getHumanReadable(entryType);
            String message = String.format(Locale.getDefault(), format, type, title);
            Toast.makeText(
                    AudioPlayerService.this,
                    message,
                    Toast.LENGTH_SHORT
            ).show();
        }

        @Override
        public void onRemoveQueueItem(MediaDescriptionCompat description) {
            Log.d(LC, "onRemoveQueueItem");
            assert description.getExtras() != null;
            long pos = description.getExtras().getLong(Meta.METADATA_KEY_QUEUE_POS);
            playbackController.removeFromQueue((int) pos);
        }

        @Override
        public void onSkipToNext() {
            Log.d(LC, "onSkipToNext");
            playbackController.skipToNext();
        }

        @Override
        public void onSkipToPrevious() {
            Log.d(LC, "onSkipToPrevious");
            playbackController.skipToPrevious();
        }

        @Override
        public void onSkipToQueueItem(long queueItemId) {
            Log.d(LC, "onSkipToQueueItem: " + queueItemId);
            playbackController.skipToQueueItem(queueItemId);
        }

        @Override
        public void onStop() {
            Log.d(LC, "onStop");
            playbackController.stop();
        }

        @Override
        public void onSeekTo(long pos) {
            Log.d(LC, "onSeekTo(" + pos + ")");
            playbackController.seekTo(pos);
        }

        PlaybackEntry getPlaybackEntry(EntryID entryID) {
            if (!Meta.METADATA_KEY_MEDIA_ID.equals(entryID.type)) {
                Log.e(LC, "Non-track entry. Unhandled! Beware!");
            }
            MediaMetadataCompat meta = musicLibraryService.getSongMetaData(entryID);
            return new PlaybackEntry(entryID, meta);
        }

        @Override
        public void onCustomAction(String action, Bundle extras) {
            switch (action) {
                default:
                    Log.e(LC, "Unhandled MediaSession onCustomAction: " + action);
                    break;
            }
        }

        @Override
        public void onSetShuffleMode(int shuffleMode) {
            // TODO: implement
            Log.e(LC, "onSetShuffleMode not implemented");
            switch (shuffleMode) {
                case PlaybackStateCompat.SHUFFLE_MODE_INVALID:
                case PlaybackStateCompat.SHUFFLE_MODE_NONE:
                case PlaybackStateCompat.SHUFFLE_MODE_ALL:
                case PlaybackStateCompat.SHUFFLE_MODE_GROUP:
                default:
                    Log.e(LC, "onSetShuffleMode unhandled mode: " + shuffleMode);
                    break;
            }
        }

        @Override
        public void onSetRepeatMode(int repeatMode) {
            // TODO: implement
            Log.e(LC, "onSetRepeatMode not implemented");
            switch (repeatMode) {
                case PlaybackStateCompat.REPEAT_MODE_INVALID:
                case PlaybackStateCompat.REPEAT_MODE_NONE:
                case PlaybackStateCompat.REPEAT_MODE_ONE:
                case PlaybackStateCompat.REPEAT_MODE_ALL:
                case PlaybackStateCompat.REPEAT_MODE_GROUP:
                default:
                    Log.e(LC, "onSetRepeatMode unhandled mode: " + repeatMode);
                    break;
            }
        }

        @Override
        public void onCommand(String command, Bundle extras, ResultReceiver cb) {
            switch (command) {
                case COMMAND_GET_PLAYLISTS:
                    List<PlaylistItem> playlistItems = musicLibraryService.getPlaylists();
                    cb.send(0, putPlaylistItems(new ArrayList<>(playlistItems)));
                    break;
                case COMMAND_GET_PLAYLIST_ENTRIES:
                    PlaylistID playlistID = PlaylistID.from(extras);
                    List<LibraryEntry> playlistEntries = musicLibraryService.getPlaylistEntries(playlistID);
                    cb.send(0, putPlaylistEntries(new ArrayList<>(playlistEntries)));
                    break;
                case COMMAND_ADD_TO_PLAYLIST:
                    EntryID entryID = EntryID.from(extras);
                    musicLibraryService.playlistAddEntry(
                            playbackController.getCurrentPlaylist().playlistID,
                            entryID
                    );
                    setToast(musicLibraryService.getSongMetaData(entryID),
                            "Adding %s \"%s\" to current playlist!");
                    break;
                case COMMAND_GET_CURRENT_PLAYLIST:
                    cb.send(0, putPlaylist(playbackController.getCurrentPlaylist()));
                    break;
                case COMMAND_GET_PLAYLIST_NEXT:
                    int maxNum = extras.getInt("MAX_ENTRIES");
                    PlaylistItem playListItem = playbackController.getCurrentPlaylist();
                    long pos = playbackController.getCurrentPlaylistPosition();
                    List<PlaybackEntry> playbackEntries = musicLibraryService.playlistGetNext(
                            playListItem.playlistID,
                            pos,
                            maxNum
                    );
                    if (playbackEntries.isEmpty()) {
                        cb.send(1, null);
                        return;
                    }
                    cb.send(0, putPlaybackEntries(new ArrayList<>(playbackEntries)));
                    break;
                case COMMAND_GET_PLAYLIST_PREVIOUS:
                    Log.e(LC, "COMMAND_GET_PLAYLIST_PREVIOUS not implemented");
                    break;
                case COMMAND_GET_CURRENT_PLAYBACK_ENTRY:
                    PlaybackEntry playbackEntry = playbackController.getCurrentPlaybackEntry();
                    if (playbackEntry == null) {
                        cb.send(1, null);
                        return;
                    }
                    cb.send(0, putPlaybackEntry(playbackEntry));
                    break;
                case COMMAND_REMOVE_FROM_PLAYLIST:
                    removeFromPlaylist(cb, extras);
                    mediaSession.sendSessionEvent(
                            AudioPlayerService.SESSION_EVENT_PLAYLIST_CHANGED,
                            null
                    );
                    break;
                default:
                    Log.e(LC, "Unhandled MediaSession onCommand: " + command);
                    break;
            }
        }
    }

    public static CompletableFuture<Boolean> removeFromPlaylist(
            MediaControllerCompat mediaController,
            PlaylistID playlistID,
            int position
    ) {
        Bundle params = new Bundle();
        params.putParcelable("playlist", playlistID);
        params.putInt("position", position);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        mediaController.sendCommand(
                AudioPlayerService.COMMAND_REMOVE_FROM_PLAYLIST,
                params,
                new ResultReceiver(null) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        future.complete(resultCode == 0);
                    }
                }
        );
        return future;
    }

    private void removeFromPlaylist(ResultReceiver cb, Bundle b) {
        b.setClassLoader(PlaylistID.class.getClassLoader());
        PlaylistID playlistID = b.getParcelable("playlist");
        int position = b.getInt("position");
        musicLibraryService.playlistRemoveEntry(playlistID, position);
        cb.send(0, null);
    }

    private static final String BUNDLE_KEY_PLAYLIST_ITEMS =
            "BUNDLE_KEY_PLAYLIST_ITEMS";
    private static final String BUNDLE_KEY_LIBRARY_ENTRIES =
            "BUNDLE_KEY_LIBRARY_ENTRIES";
    private static final String BUNDLE_KEY_PLAYLIST_ITEM =
            "BUNDLE_KEY_PLAYLIST_ITEM";
    private static final String BUNDLE_KEY_PLAYBACK_ENTRY =
            "BUNDLE_KEY_PLAYBACK_ENTRY";
    private static final String BUNDLE_KEY_PLAYBACK_ENTRIES =
            "BUNDLE_KEY_PLAYBACK_ENTRIES";

    private static Bundle putPlaybackEntry(PlaybackEntry playbackEntry) {
        Bundle b = new Bundle();
        b.putParcelable(BUNDLE_KEY_PLAYBACK_ENTRY, playbackEntry);
        return b;
    }

    public static PlaybackEntry getPlaybackEntry(Bundle resultData) {
        return resultData.getParcelable(BUNDLE_KEY_PLAYBACK_ENTRY);
    }

    private static Bundle putPlaybackEntries(ArrayList<PlaybackEntry> playbackEntries) {
        Bundle b = new Bundle();
        b.putParcelableArrayList(BUNDLE_KEY_PLAYBACK_ENTRIES, playbackEntries);
        return b;
    }

    public static List<PlaybackEntry> getPlaybackEntries(Bundle resultData) {
        return resultData.getParcelableArrayList(BUNDLE_KEY_PLAYBACK_ENTRIES);
    }

    private static Bundle putPlaylist(PlaylistItem playlistItem) {
        Bundle b = new Bundle();
        b.putParcelable(BUNDLE_KEY_PLAYLIST_ITEM, playlistItem);
        return b;
    }

    public static PlaylistItem getPlaylist(Bundle resultData) {
        return resultData.getParcelable(BUNDLE_KEY_PLAYLIST_ITEM);
    }

    private static Bundle putPlaylistItems(ArrayList<PlaylistItem> playlistItems) {
        Bundle b = new Bundle();
        b.putParcelableArrayList(BUNDLE_KEY_PLAYLIST_ITEMS, playlistItems);
        return b;
    }

    public static ArrayList<PlaylistItem> getPlaylistItems(Bundle resultData) {
        return resultData.getParcelableArrayList(BUNDLE_KEY_PLAYLIST_ITEMS);
    }

    private static Bundle putPlaylistEntries(ArrayList<LibraryEntry> playlistEntries) {
        Bundle b = new Bundle();
        b.putParcelableArrayList(BUNDLE_KEY_LIBRARY_ENTRIES, playlistEntries);
        return b;
    }

    public static ArrayList<LibraryEntry> getPlaylistEntries(Bundle resultData) {
        return resultData.getParcelableArrayList(BUNDLE_KEY_LIBRARY_ENTRIES);
    }

    private class PlaybackCallback implements PlaybackController.Callback {
        @Override
        public void onPlayerChanged(AudioPlayer.Type audioPlayerType) {
            switch (audioPlayerType) {
                case LOCAL:
                    notify = true;
                    break;
                case CAST:
                default:
                    notify = false;
                    break;
            }
            setNotification();
        }

        private String getPlaybackStateString(int playbackState) {
            switch (playbackState) {
                case PlaybackStateCompat.STATE_ERROR:
                    return "STATE_ERROR";
                case PlaybackStateCompat.STATE_STOPPED:
                    return "STATE_STOPPED";
                case PlaybackStateCompat.STATE_NONE:
                    return "STATE_NONE";
                case PlaybackStateCompat.STATE_PAUSED:
                    return "STATE_PAUSED";
                case PlaybackStateCompat.STATE_PLAYING:
                    return "STATE_PLAYING";
                case PlaybackStateCompat.STATE_BUFFERING:
                    return "STATE_BUFFERING";
                case PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS:
                    return "STATE_SKIPPING_TO_PREVIOUS";
                case PlaybackStateCompat.STATE_SKIPPING_TO_NEXT:
                    return "STATE_SKIPPING_TO_NEXT";
                case PlaybackStateCompat.STATE_SKIPPING_TO_QUEUE_ITEM:
                    return "STATE_SKIPPING_TO_QUEUE_ITEM";
                case PlaybackStateCompat.STATE_FAST_FORWARDING:
                    return "STATE_FAST_FORWARDING";
                case PlaybackStateCompat.STATE_REWINDING:
                    return "STATE_REWINDING";
                case PlaybackStateCompat.STATE_CONNECTING:
                    return "STATE_CONNECTING";
                default:
                    return "UNKNOWN_STATE: " + playbackState;
            }
        }

        @Override
        public void onStateChanged(int newPlaybackState) {
            Log.d(LC, "PlaybackState: " + getPlaybackStateString(newPlaybackState));
            switch (newPlaybackState) {
                case PlaybackStateCompat.STATE_ERROR:
                case PlaybackStateCompat.STATE_NONE:
                case PlaybackStateCompat.STATE_STOPPED:
                    setPlaybackState(newPlaybackState, 0L, PLAYBACK_SPEED_PAUSED);
                    stopForeground(true);
                    stopSelf();
                    break;
                case PlaybackStateCompat.STATE_PAUSED:
                    setPlaybackState(
                            newPlaybackState,
                            playbackController.getPlayerSeekPosition(),
                            PLAYBACK_SPEED_PAUSED
                    );
                    break;
                case PlaybackStateCompat.STATE_PLAYING:
                    Log.d(LC, "startService");
                    startService(new Intent(
                            AudioPlayerService.this,
                            AudioPlayerService.class));
                    setPlaybackState(
                            newPlaybackState,
                            playbackController.getPlayerSeekPosition(),
                            PLAYBACK_SPEED_PLAYING
                    );
                    break;
                case PlaybackStateCompat.STATE_BUFFERING:
                    setPlaybackState(
                            newPlaybackState,
                            playbackController.getPlayerSeekPosition(),
                            PLAYBACK_SPEED_PAUSED
                    );
                    break;
                case PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS:
                case PlaybackStateCompat.STATE_SKIPPING_TO_NEXT:
                case PlaybackStateCompat.STATE_SKIPPING_TO_QUEUE_ITEM:
                    setPlaybackState(newPlaybackState, 0L, PLAYBACK_SPEED_PAUSED);
                    break;
                case PlaybackStateCompat.STATE_FAST_FORWARDING:
                    Log.w(LC, "Unhandled state: STATE_FAST_FORWARDING");
                    break;
                case PlaybackStateCompat.STATE_REWINDING:
                    Log.w(LC, "Unhandled state: STATE_REWINDING");
                    break;
                case PlaybackStateCompat.STATE_CONNECTING:
                    Log.w(LC, "Unhandled state: STATE_CONNECTING");
                    break;
                default:
                    Log.w(LC, "Unhandled state: " + newPlaybackState);
                    break;
            }
            setNotification();
        }

        private void setPlaybackState(int newPlaybackState, long position, float playbackSpeed) {
            playbackState = playbackStateBuilder
                    .setState(newPlaybackState, position, playbackSpeed)
                    .build();
            mediaSession.setPlaybackState(playbackState);
        }

        @Override
        public void onMetaChanged(EntryID entryID) {
            MediaMetadataCompat meta = musicLibraryService.getSongMetaData(entryID);
            mediaSession.setMetadata(meta);
        }

        @Override
        public void onQueueChanged() {
            List<MediaSessionCompat.QueueItem> queue = playbackController.getQueue();
            mediaSession.setQueue(queue);
        }

        @Override
        public void onPlaylistPositionChanged() {
            mediaSession.sendSessionEvent(
                    AudioPlayerService.SESSION_EVENT_PLAYLIST_POSITION_CHANGED,
                    null
            );
        }
    }
}