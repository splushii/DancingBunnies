package se.splushii.dancingbunnies.audioplayer;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.media.session.MediaButtonReceiver;
import se.splushii.dancingbunnies.MainActivity;
import se.splushii.dancingbunnies.R;
import se.splushii.dancingbunnies.musiclibrary.EntryID;
import se.splushii.dancingbunnies.musiclibrary.LibraryEntry;
import se.splushii.dancingbunnies.musiclibrary.Meta;
import se.splushii.dancingbunnies.musiclibrary.MusicLibraryQuery;
import se.splushii.dancingbunnies.musiclibrary.MusicLibraryService;
import se.splushii.dancingbunnies.musiclibrary.PlaylistItem;
import se.splushii.dancingbunnies.storage.PlaybackControllerStorage;
import se.splushii.dancingbunnies.util.Util;

// TODO: Handle incoming call. PhoneStateListener.LISTEN_CALL_STATE.
public class AudioPlayerService extends MediaBrowserServiceCompat {
    private static final String LC = Util.getLogContext(AudioPlayerService.class);
    private static final String NOTIFICATION_CHANNEL_ID = "dancingbunnies.notification.channel";
    private static final String NOTIFICATION_CHANNEL_NAME = "DancingBunnies";
    private static final int SERVICE_NOTIFICATION_ID = 1337;
    private static final float PLAYBACK_SPEED_PAUSED = 0f;
    private static final float PLAYBACK_SPEED_PLAYING = 1f;
    private static final String MEDIA_ID_ROOT = "dancingbunnies.media.id.root";

    public static final String SESSION_EVENT_PLAYLIST_POSITION_CHANGED = "PLAYLIST_POSITION_CHANGED";
    public static final String SESSION_EVENT_PLAYLIST_CHANGED = "PLAYLIST_CHANGED";

    public static final String COMMAND_GET_CURRENT_PLAYLIST = "GET_CURRENT_PLAYLIST";
    private static final String COMMAND_QUEUE_ENTRYIDS = "QUEUE_ENTRYIDS";
    private static final String COMMAND_DEQUEUE = "DEQUEUE";
    private static final String COMMAND_MOVE_QUEUE_ITEMS = "MOVE_QUEUE_ITEMS";

    public static final String BUNDLE_KEY_DEQUEUE_QUEUE_POS =
            "dancingbunnies.bundle.key.audioplayerservice.dequeue_queue_pos";

    public static final int QUEUE_LAST = -1;

    public static final String STARTCMD_INTENT_CAST_ACTION = "dancingbunnies.intent.castaction";

    public static final String CAST_ACTION_TOGGLE_PLAYBACK = "TOGGLE_PLAYBACK";
    public static final String CAST_ACTION_NEXT = "NEXT";
    public static final String CAST_ACTION_PREVIOUS = "PREVIOUS";

    private PlaybackControllerStorage playbackControllerStorage;
    private PlaybackController playbackController;
    private final PlaybackController.Callback audioPlayerManagerCallback = new PlaybackCallback();
    private MediaSessionCompat mediaSession;
    private int metaChangedListenerID;
    private PlaybackStateCompat.Builder playbackStateBuilder;
    private PlaybackStateCompat playbackState;
    private boolean casting = false;

    private IntentFilter intentFilter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
    private final MakeSomeNoiseReceiver makeSomeNoiseReceiver = new MakeSomeNoiseReceiver();
    private boolean isNoiseReceiverRegistered;

    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private boolean playOnAudioFocusGain;

    private MusicLibraryService musicLibraryService;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(LC, "Connected MusicLibraryService");
            MusicLibraryService.MusicLibraryBinder binder = (MusicLibraryService.MusicLibraryBinder) service;
            musicLibraryService = binder.getService();
            setupAudioFocus();
            setupMediaSession();
            setupPlaybackController();
            playbackController.initialize();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            musicLibraryService.removeMetaChangedListener(metaChangedListenerID);
            playbackController.onDestroy();
            playbackController = null;
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

    private HashSet<String> subscriptionIDs = new HashSet<>();
    private HashMap<String, List<MediaBrowserCompat.MediaItem>> subscriptionResults = new HashMap<>();
    private HashMap<String, Observer<List<LibraryEntry>>> subscriptionObservers = new HashMap<>();
    private HashMap<String, LiveData<List<LibraryEntry>>> subscriptionLiveData = new HashMap<>();
    @Override
    public void onSubscribe(String id, Bundle options) {
        subscriptionIDs.add(id);
        subscriptionResults.put(id, new ArrayList<>());
        String showField = options.getString(MusicLibraryQuery.BUNDLE_KEY_SHOW);
        String sortField = options.getString(MusicLibraryQuery.BUNDLE_KEY_SORT);
        Bundle query = options.getBundle(MusicLibraryQuery.BUNDLE_KEY_QUERY);
        LiveData<List<LibraryEntry>> entries = musicLibraryService.getSubscriptionEntries(
                showField,
                sortField,
                query
        );
        subscriptionLiveData.put(id, entries);
        Observer<List<LibraryEntry>> observer = libraryEntries -> {
            List<MediaBrowserCompat.MediaItem> mediaItems = new ArrayList<>();
            Log.d(LC, "onSubscribe(" + id + ") entries: " + libraryEntries.size());
            for (LibraryEntry entry: libraryEntries) {
                MediaBrowserCompat.MediaItem item = generateMediaItem(entry);
                mediaItems.add(item);
            }
            Log.d(LC, "onSubscribe(" + id + ") mediaItems: " + mediaItems.size());
            subscriptionResults.put(id, mediaItems);
            notifyChildrenChanged(id);
        };
        subscriptionObservers.put(id, observer);
        entries.observeForever(observer);
        Log.d(LC, "onSubscribe subscriptions: " + subscriptionIDs.size()
                + " id: " + id + " options: " + options.toString());
    }

    @Override
    public void onUnsubscribe(String id) {
        subscriptionIDs.remove(id);
        subscriptionResults.remove(id);
        LiveData<List<LibraryEntry>> liveData = subscriptionLiveData.remove(id);
        Observer<List<LibraryEntry>> observer = subscriptionObservers.remove(id);
        if (liveData != null && observer != null) {
            liveData.removeObserver(observer);
        }
        Log.d(LC, "onUnsubscribe subscriptions: " + subscriptionIDs.size() + " id: " + id);
    }

    @Override
    public void onLoadChildren(@NonNull String parentId,
                               @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        Bundle options = new Bundle();
        onLoadChildren(parentId, result, options);
    }

    @Override
    public void onLoadChildren(@NonNull String parentId,
                               @NonNull Result<List<MediaBrowserCompat.MediaItem>> result,
                               @NonNull Bundle options) {
        // TODO: Put EntryID.key() in parentId. Make a EntryID.from(key).
        // Make a EntryID.toMusicLibraryQueryOptions()
        // parentId is now usable!
        Log.d(LC, "onLoadChildren parentId: " + parentId);
        result.sendResult(subscriptionResults.get(parentId));
    }

    @Override
    public void onSearch(@NonNull String query, Bundle extras, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        result.sendResult(musicLibraryService.getSearchEntries(query).stream()
                .map(e -> generateMediaItem(e, e.id))
                .collect(Collectors.toList())
        );
    }

    public static MediaBrowserCompat.MediaItem generateMediaItem(LibraryEntry entry) {
        return generateMediaItem(entry.entryID, entry.name());
    }

    public static MediaBrowserCompat.MediaItem generateMediaItem(EntryID entryID, String title) {
        Bundle extras = entryID.toBundle();
        MediaDescriptionCompat desc = new MediaDescriptionCompat.Builder()
                .setMediaId(entryID.key())
                .setExtras(extras)
                .setTitle(title)
                .build();
        int flags = MediaBrowserCompat.MediaItem.FLAG_PLAYABLE;
        if (!Meta.FIELD_SPECIAL_MEDIA_ID.equals(entryID.type)) {
            flags |= MediaBrowserCompat.MediaItem.FLAG_BROWSABLE;
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
        CompletableFuture<Void> result;
        switch (action) {
            case CAST_ACTION_TOGGLE_PLAYBACK:
                result = playbackController.playPause();
                break;
            case CAST_ACTION_PREVIOUS:
                result = playbackController.skipToPrevious();
                break;
            case CAST_ACTION_NEXT:
                result = playbackController.skipToNext();
                break;
            default:
                result = CompletableFuture.completedFuture(null);
                break;
        }
        result.handle(this::handleControllerResult);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(LC, "onCreate");
        playbackControllerStorage = new PlaybackControllerStorage(this);

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
        playbackController = null;
        unbindService(serviceConnection);
        musicLibraryService = null;
        mediaSession.release();
        super.onDestroy();
    }

    private boolean requestAudioFocus() {
        int res = audioManager.requestAudioFocus(audioFocusRequest);
        switch (res) {
            case AudioManager.AUDIOFOCUS_REQUEST_FAILED:
                return false;
            case AudioManager.AUDIOFOCUS_REQUEST_GRANTED:
                return true;
            case AudioManager.AUDIOFOCUS_REQUEST_DELAYED:
                Log.w(LC, "Delayed audio focus not configured. This should never happen.");
                return false;
            default:
                Log.e(LC, "Unknown audio focus request result: " + res);
                return false;
        }
    }

    private void setupAudioFocus() {
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioManager.OnAudioFocusChangeListener audioFocusChangeListener = focusChange -> {
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_LOSS:
                    playbackController.pause()
                            .handle(this::handleControllerResult);
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    // Temporarily pause playback
                    if (isPlayingState()) {
                        playOnAudioFocusGain = true;
                    }
                    playbackController.pause()
                            .handle(this::handleControllerResult);
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    // Lower the volume, keep playing. Let Android handle this automatically.
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    if (playOnAudioFocusGain) {
                        playbackController.play()
                                .handle(this::handleControllerResult);
                        playOnAudioFocusGain = false;
                    }
                    break;
                default:
                    Log.e(LC, "Unhandled audio focus change: " + focusChange);
                    break;
            }
        };
        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build();
    }

    private void setupPlaybackController() {
        playbackController = new PlaybackController(
                this,
                playbackControllerStorage,
                musicLibraryService,
                audioPlayerManagerCallback
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
                        | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE
                        | PlaybackStateCompat.ACTION_STOP
                        | PlaybackStateCompat.ACTION_SEEK_TO
                        | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                        | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM
                        | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
//                        TODO: Implement
//                        | PlaybackStateCompat.ACTION_SET_RATING
//                        | PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE
//                        | PlaybackStateCompat.ACTION_SET_REPEAT_MODE
//                        | PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
                )
                .setState(PlaybackStateCompat.STATE_NONE, 0, PLAYBACK_SPEED_PAUSED);
        playbackState = playbackStateBuilder.build();
        mediaSession.setPlaybackState(playbackState);
        mediaSession.setCallback(new MediaSessionCallback());

        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.INTENT_EXTRA_PAGER_SELECTION, MainActivity.PAGER_NOWPLAYING);
        PendingIntent pi = PendingIntent.getActivity(this, 99 /*request code*/,
                intent, PendingIntent.FLAG_UPDATE_CURRENT);
        mediaSession.setSessionActivity(pi);
        mediaSession.setMetadata(Meta.UNKNOWN_ENTRY.toMediaMetadataCompat());

        mediaSession.setQueueTitle("Queue");
        mediaSession.setQueue(new LinkedList<>());
        metaChangedListenerID = musicLibraryService.addMetaChangedListener(() -> {
            playbackController.updateCurrent();
            playbackController.updateQueue();
        });
    }

    private boolean isStoppedState() {
        int state = playbackState.getState();
        return state == PlaybackStateCompat.STATE_ERROR
                || state == PlaybackStateCompat.STATE_NONE
                || state == PlaybackStateCompat.STATE_STOPPED;
    }

    private boolean isPlayingState() {
        return playbackState.getState() == PlaybackStateCompat.STATE_PLAYING;
    }

    @SuppressLint("SwitchIntDef")
    private void setNotification() {
        if (casting || isStoppedState()) {
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
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .setContentIntent(controller.getSessionActivity())
                        .addAction(action_previous)
                        .addAction(action_play_pause)
                        .addAction(action_next)
                        .addAction(action_stop)
                        .setStyle(style)
                        .build();
        startForeground(SERVICE_NOTIFICATION_ID, notification);
    }

    private Void handleControllerResult(Void result, Throwable t) {
        if (t != null) {
            Log.e(LC, Log.getStackTraceString(t));
            Toast.makeText(this, t.getMessage(), Toast.LENGTH_SHORT).show();
        }
        return result;
    }

    private class MediaSessionCallback extends MediaSessionCompat.Callback {
        @Override
        public void onPlay() {
            Log.d(LC, "onPlay");
            if (requestAudioFocus()) {
                playbackController.play()
                        .handle(AudioPlayerService.this::handleControllerResult);
            } else {
                Toast.makeText(
                        AudioPlayerService.this,
                        "Could not get audio focus. Audio focus is held by another app.",
                        Toast.LENGTH_SHORT
                ).show();
            }
        }

        @Override
        public void onPause() {
            Log.d(LC, "onPause");
            playOnAudioFocusGain = false;
            playbackController.pause()
                    .handle(AudioPlayerService.this::handleControllerResult);
        }

        @Override
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            Log.d(LC, "onPlayFromMediaId");
            EntryID entryID = EntryID.from(extras);
            playbackController.playNow(entryID)
                    .thenRun(() -> setToast(
                            entryID,
                            "Playing %s \"%s\" now!")
                    )
                    .handle(AudioPlayerService.this::handleControllerResult);
        }

        @Override
        public void onAddQueueItem(MediaDescriptionCompat description) {
            onAddQueueItem(description, AudioPlayerService.QUEUE_LAST);
        }

        @Override
        public void onAddQueueItem(MediaDescriptionCompat description, int index) {
            Log.d(LC, "onAddQueueItem(" +
                    (index == AudioPlayerService.QUEUE_LAST ? "last" : index) + ")");
            EntryID entryID = EntryID.from(description);
            playbackController.queue(Collections.singletonList(entryID), index)
                    .thenRunAsync(() -> setToast(
                            entryID,
                            "Adding %s \"%s\" to queue!"),
                            Util.getMainThreadExecutor())
                    .handle(AudioPlayerService.this::handleControllerResult);
        }

        private void setToast(EntryID entryID, String format) {
            String type = Meta.FIELD_SPECIAL_MEDIA_ID.equals(entryID.type) ?
                    Meta.FIELD_TITLE : entryID.type;
            musicLibraryService.getSongMeta(entryID).thenAcceptAsync(meta -> {
                        String title = meta.getAsString(Meta.FIELD_TITLE);
                        String message = String.format(Locale.getDefault(), format, type, title);
                        Toast.makeText(
                                AudioPlayerService.this,
                                message,
                                Toast.LENGTH_SHORT
                        ).show();
                    },
                    Util.getMainThreadExecutor());
        }

        @Override
        public void onRemoveQueueItem(MediaDescriptionCompat description) {
            Log.d(LC, "onRemoveQueueItem");
            assert description.getExtras() != null;
            long pos = description.getExtras().getLong(BUNDLE_KEY_DEQUEUE_QUEUE_POS);
            playbackController.deQueue(new long[]{pos})
                    .handle(AudioPlayerService.this::handleControllerResult);
        }

        @Override
        public void onSkipToNext() {
            Log.d(LC, "onSkipToNext");
            playbackController.skipToNext()
                    .handle(AudioPlayerService.this::handleControllerResult);
        }

        @Override
        public void onSkipToPrevious() {
            Log.d(LC, "onSkipToPrevious");
            playbackController.skipToPrevious()
                    .handle(AudioPlayerService.this::handleControllerResult);
        }

        @Override
        public void onSkipToQueueItem(long queueItemId) {
            playbackController.skip((int) queueItemId)
                    .handle(AudioPlayerService.this::handleControllerResult);
        }

        @Override
        public void onStop() {
            Log.d(LC, "onStop");
            playOnAudioFocusGain = false;
            playbackController.stop()
                    .handle(AudioPlayerService.this::handleControllerResult);
        }

        @Override
        public void onSeekTo(long pos) {
            Log.d(LC, "onSeekTo(" + pos + ")");
            playbackController.seekTo(pos)
                    .handle(AudioPlayerService.this::handleControllerResult);
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
                case COMMAND_GET_CURRENT_PLAYLIST:
                    // TODO: Rewrite this in the same spirit as below commands
                    cb.send(0, putPlaylist(playbackController.getCurrentPlaylist()));
                    break;
                case COMMAND_QUEUE_ENTRYIDS:
                    queue(cb, extras);
                    break;
                case COMMAND_DEQUEUE:
                    dequeue(cb, extras);
                    break;
                case COMMAND_MOVE_QUEUE_ITEMS:
                    moveQueueItems(cb, extras);
                    break;
                default:
                    Log.e(LC, "Unhandled MediaSession onCommand: " + command);
                    break;
            }
        }
    }

    public static CompletableFuture<Boolean> queue(
            MediaControllerCompat mediaController,
            List<EntryID> entryIDs,
            int toPosition
    ) {
        Bundle params = new Bundle();
        params.putParcelableArrayList("entryids", new ArrayList<>(entryIDs));
        params.putInt("toPosition", toPosition);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        mediaController.sendCommand(
                AudioPlayerService.COMMAND_QUEUE_ENTRYIDS,
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

    private void queue(ResultReceiver cb, Bundle b) {
        ArrayList<EntryID> entryIDs = b.getParcelableArrayList("entryids");
        int toPosition = b.getInt("toPosition");
        if (entryIDs == null || entryIDs.isEmpty()) {
            cb.send(-1, null);
            return;
        }
        Log.d(LC, "queue() adding " + entryIDs.size() + " entryIDs");
        musicLibraryService.getSongEntries(entryIDs)
                .thenComposeAsync(songEntryIDs -> {
                    Log.d(LC, "queue() total song entries: " + songEntryIDs.size());
                    return playbackController.queue(songEntryIDs, toPosition);
                }, Util.getMainThreadExecutor())
                .handle((r, t) -> {
                    handleControllerResult(r, t);
                    cb.send(t == null ? 0 : 1, null);
                    return r;
                });
    }

    public static CompletableFuture<Boolean> dequeue(MediaControllerCompat mediaController,
                                                     List<Long> positionList) {
        Bundle params = new Bundle();
        params.putLongArray("positionList", positionList.stream().mapToLong(l -> l).toArray());
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        mediaController.sendCommand(
                AudioPlayerService.COMMAND_DEQUEUE,
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

    private void dequeue(ResultReceiver cb, Bundle extras) {
        long[] positions = extras.getLongArray("positionList");
        playbackController.deQueue(positions).handle((r, t) -> {
            cb.send(t == null ? 0 : 1, null);
            return handleControllerResult(r, t);
        });
    }

    public static CompletableFuture<Boolean> moveQueueItems(MediaControllerCompat mediaController,
                                                            List<Long> positionList,
                                                            int toPosition) {
        Bundle params = new Bundle();
        params.putLongArray("positionList", positionList.stream().mapToLong(l -> l).toArray());
        params.putInt("toPosition", toPosition);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        mediaController.sendCommand(
                AudioPlayerService.COMMAND_MOVE_QUEUE_ITEMS,
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

    private void moveQueueItems(ResultReceiver cb, Bundle extras) {
        long[] positions = extras.getLongArray("positionList");
        int toPosition = extras.getInt("toPosition");
        playbackController.moveQueueItems(positions, toPosition).handle((r, t) -> {
            cb.send(t == null ? 0 : 1, null);
            return handleControllerResult(r, t);
        });
    }

    private static final String BUNDLE_KEY_PLAYLIST_ITEM =
            "BUNDLE_KEY_PLAYLIST_ITEM";

    private static Bundle putPlaylist(PlaylistItem playlistItem) {
        Bundle b = new Bundle();
        b.putParcelable(BUNDLE_KEY_PLAYLIST_ITEM, playlistItem);
        return b;
    }

    public static PlaylistItem getPlaylist(Bundle resultData) {
        return resultData.getParcelable(BUNDLE_KEY_PLAYLIST_ITEM);
    }

    private class PlaybackCallback implements PlaybackController.Callback {
        @Override
        public void onPlayerChanged(AudioPlayer.Type audioPlayerType) {
            switch (audioPlayerType) {
                case CAST:
                    casting = true;
                    break;
                default:
                case LOCAL:
                    casting = false;
                    break;
            }
            setMediaSessionActiveness();
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
                    toggleNoiseReceiver(false);
                    audioManager.abandonAudioFocusRequest(audioFocusRequest);
                    break;
                case PlaybackStateCompat.STATE_PAUSED:
                    setPlaybackState(
                            newPlaybackState,
                            playbackController.getPlayerSeekPosition(),
                            PLAYBACK_SPEED_PAUSED
                    );
                    toggleNoiseReceiver(false);
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
                    toggleNoiseReceiver(true);
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
                    Log.e(LC, "Unhandled state: " + newPlaybackState);
                    break;
            }
            setMediaSessionActiveness();
            setNotification();
        }

        private void setPlaybackState(int newPlaybackState, long position, float playbackSpeed) {
            Log.d(LC, "setPlaybackState: " + getPlaybackStateString(newPlaybackState)
                    + " pos: " + position + " speed: " + playbackSpeed);
            playbackState = playbackStateBuilder
                    .setState(newPlaybackState, position, playbackSpeed)
                    .build();
            mediaSession.setPlaybackState(playbackState);
        }

        @Override
        public void onMetaChanged(EntryID entryID) {
            musicLibraryService.getSongMeta(entryID).thenAcceptAsync(
                    meta -> mediaSession.setMetadata(meta.toMediaMetadataCompat()),
                    Util.getMainThreadExecutor()
            );
        }

        @Override
        public void onQueueChanged(List<PlaybackEntry> queue) {
            List<MediaSessionCompat.QueueItem> queueItems = new ArrayList<>();
            for (int i = 0; i < queue.size(); i++) {
                PlaybackEntry playbackEntry = queue.get(i);
                queueItems.add(new MediaSessionCompat.QueueItem(
                        playbackEntry.toMediaDescriptionCompat(),
                        playbackEntry.playbackID
                ));
            }
            mediaSession.setQueue(queueItems);
        }

        @Override
        public void onPlaylistPositionChanged() {
            mediaSession.sendSessionEvent(
                    AudioPlayerService.SESSION_EVENT_PLAYLIST_POSITION_CHANGED,
                    null
            );
        }

        @Override
        public void onPlayerSeekPositionChanged(long pos) {
            setPlaybackState(playbackState.getState(), pos, playbackState.getPlaybackSpeed());
        }
    }

    private void setMediaSessionActiveness() {
        boolean active = !isStoppedState() && !casting;
        if (active != mediaSession.isActive()) {
            mediaSession.setActive(active);
        }
    }

    private void toggleNoiseReceiver(boolean register) {
        if (register) {
            if (!isNoiseReceiverRegistered) {
                registerReceiver(makeSomeNoiseReceiver, intentFilter);
                isNoiseReceiverRegistered = true;
            }
        } else {
            if(isNoiseReceiverRegistered) {
                unregisterReceiver(makeSomeNoiseReceiver);
                isNoiseReceiverRegistered = false;
            }
        }
    }

    private class MakeSomeNoiseReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                if (!casting) {
                    playbackController.pause()
                            .handle(AudioPlayerService.this::handleControllerResult);
                }
            }
        }
    }
}
