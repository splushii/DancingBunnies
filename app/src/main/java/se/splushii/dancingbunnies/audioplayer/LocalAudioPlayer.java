package se.splushii.dancingbunnies.audioplayer;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import se.splushii.dancingbunnies.backend.AudioDataDownloadHandler;
import se.splushii.dancingbunnies.musiclibrary.AudioDataSource;
import se.splushii.dancingbunnies.musiclibrary.EntryID;
import se.splushii.dancingbunnies.musiclibrary.Meta;
import se.splushii.dancingbunnies.musiclibrary.MusicLibraryService;
import se.splushii.dancingbunnies.util.Util;

// TODO: Set error state if a triggered action is unsuccessful
// TODO: Make it thread-safe. A lock is probably needed for player and nextplayers.
class LocalAudioPlayer extends AudioPlayer {
    private static final String LC = Util.getLogContext(LocalAudioPlayer.class);
    private static final int NUM_TO_PRELOAD = 3;

    private enum MediaPlayerState {
        NULL,
        IDLE,
        INITIALIZED,
        PREPARING,
        STARTED,
        PAUSED,
        STOPPED,
        PLAYBACK_COMPLETED,
        PREPARED
    }
    private boolean playWhenReady = false;
    private final MusicLibraryService musicLibraryService;
    private MediaPlayerInstance player;
    private LinkedList<MediaPlayerInstance> queuePlayers;
    private LinkedList<MediaPlayerInstance> playlistPlayers;
    private LinkedList<MediaPlayerInstance> historyPlayers;

    LocalAudioPlayer(Callback audioPlayerCallback,
                     MusicLibraryService musicLibraryService,
                     AudioPlayerState state) {
        super(audioPlayerCallback);
        this.musicLibraryService = musicLibraryService;
        queuePlayers = new LinkedList<>();
        playlistPlayers = new LinkedList<>();
        historyPlayers = new LinkedList<>();
        loadState(state);
    }

    private void loadState(AudioPlayerState state) {
        for (PlaybackEntry entry: state.history) {
            historyPlayers.add(new MediaPlayerInstance(entry));
        }
        for (PlaybackEntry entry: state.entries) {
            if (!PlaybackEntry.USER_TYPE_QUEUE.equals(entry.playbackType)
                    && !PlaybackEntry.USER_TYPE_PLAYLIST.equals(entry.playbackType)) {
                continue;
            }
            MediaPlayerInstance playerInstance = new MediaPlayerInstance(entry);
            playerInstance.preload();
            if (player == null) {
                player = playerInstance;
            } else if (PlaybackEntry.USER_TYPE_QUEUE.equals(entry.playbackType)) {
                queuePlayers.add(playerInstance);
            } else {
                playlistPlayers.add(playerInstance);
            }
        }
        if (player != null) {
            player.seekTo(state.lastPos);
        }
        setCurrentPlayer(player);
        preparePlayers();
    }

    @Override
    public CompletableFuture<Optional<String>> checkPreload() {
        Log.d(LC, "checkPreload()");
        int numPreloaded = getNumPreloaded();
        if (NUM_TO_PRELOAD > numPreloaded) {
            addNewPreloadEntries(NUM_TO_PRELOAD - numPreloaded);
        }
        return actionResult(null);
    }

    private void addNewPreloadEntries(int num) {
        List<PlaybackEntry> entries = audioPlayerCallback.requestPreload(num);
        for (PlaybackEntry entry: entries) {
            MediaPlayerInstance playerInstance = new MediaPlayerInstance(entry);
            if (player == null) {
                setCurrentPlayer(playerInstance);
            } else if (entry.playbackType.equals(PlaybackEntry.USER_TYPE_QUEUE)) {
                queuePlayers.add(playerInstance);
            } else {
                playlistPlayers.add(playerInstance);
            }
        }
        audioPlayerCallback.onPreloadChanged();
        preparePlayers();
    }

    private int getNumPreloaded() {
        return queuePlayers.size() + playlistPlayers.size() + (player == null ? 0 : 1);
    }

    @Override
    public CompletableFuture<Optional<String>> play() {
        playWhenReady = true;
        if (player == null) {
            return actionResult("Player is null");
        }
        player.play();
        preparePlayers();
        updatePlaybackState();
        return actionResult(null);
    }

    private void preparePlayers() {
        int count = 0;
        for (MediaPlayerInstance mediaPlayerInstance: queuePlayers) {
            if (count >= NUM_TO_PRELOAD) {
                mediaPlayerInstance.release();
            } else if (mediaPlayerInstance.isIdle()) {
                mediaPlayerInstance.preload();
            } else if (mediaPlayerInstance.isStopped()){
                mediaPlayerInstance.prepare();
            }
            count++;
        }
        for (MediaPlayerInstance mediaPlayerInstance: playlistPlayers) {
            if (count >= NUM_TO_PRELOAD) {
                mediaPlayerInstance.release();
            } else if (mediaPlayerInstance.isIdle()) {
                mediaPlayerInstance.preload();
            } else if (mediaPlayerInstance.isStopped()){
                mediaPlayerInstance.prepare();
            }
            count++;
        }
    }

    @Override
    public CompletableFuture<Optional<String>> pause() {
        playWhenReady = false;
        if (player == null) {
            return actionResult("Player is null");
        }
        player.pause();
        updatePlaybackState();
        return actionResult(null);
    }

    @Override
    public CompletableFuture<Optional<String>> stop() {
        playWhenReady = false;
        if (player == null) {
            return actionResult("Player is null");
        }
        player.stop();
        for (MediaPlayerInstance mediaPlayerInstance: queuePlayers) {
            mediaPlayerInstance.stop();
        }
        for (MediaPlayerInstance mediaPlayerInstance: playlistPlayers) {
            mediaPlayerInstance.stop();
        }
        updatePlaybackState();
        return actionResult(null);
    }

    @Override
    public CompletableFuture<Optional<String>> next() {
        MediaPlayerInstance previousPlayer = player;
        MediaPlayerInstance nextPlayer = queuePlayers.poll();
        if (nextPlayer == null) {
            nextPlayer = playlistPlayers.poll();
        }
        setCurrentPlayer(nextPlayer);
        if (previousPlayer != null) {
            previousPlayer.pause();
            previousPlayer.seekTo(0);
            previousPlayer.release();
            historyPlayers.add(previousPlayer);
        }
        updatePlaybackState();
        checkPreload();
        audioPlayerCallback.onPreloadChanged();
        preparePlayers();
        if (playWhenReady) {
            return play();
        }
        return actionResult(null);
    }

    @Override
    CompletableFuture<Optional<String>> skipItems(int offset) {
        Log.d(LC, "skipItems(" + offset + ")");
        if (offset == 0) {
            return actionResult(null);
        }
        if (offset == 1) {
            return next();
        }
        MediaPlayerInstance nextPlayer = null;
        if (offset > 0) {
            // Skip forward
            int numPlayerQueueEntries = queuePlayers.size();
            int totalQueueEntries = numPlayerQueueEntries + audioPlayerCallback.getNumQueueEntries();
            if (offset <= totalQueueEntries) {
                // Play queue item at offset now
                if (offset <= numPlayerQueueEntries) {
                    // Move the queue entry to after current index and skip to next
                    Log.d(LC, "skipItems short queue offset");
                    nextPlayer = queuePlayers.remove(offset - 1);
                } else {
                    // Get the queue entry from PlaybackController, queue after current and play
                    Log.d(LC, "skipItems long queue offset");
                    nextPlayer = new MediaPlayerInstance(
                            audioPlayerCallback.consumeQueueEntry(offset - 1)
                    );
                }
            } else {
                // Skip all playlist items until offset
                offset -= numPlayerQueueEntries;
                int numPlayerPlaylistEntries = getPreloadedPlaylistEntries(Integer.MAX_VALUE).size();
                if (offset <= numPlayerPlaylistEntries) {
                    // Dequeue all playlist items up until offset, then move offset to after current
                    // index and skip to next
                    Log.d(LC, "skipItems short playlist offset");
                    for (int i = 0; i < offset; i++) {
                        nextPlayer = playlistPlayers.poll();
                        if (i < offset - 1 && nextPlayer != null) {
                            nextPlayer.release();
                        }
                    }
                } else {
                    // Dequeue all playlist items. Consume and throw away all playlist items up
                    // until offset. Insert and play offset.
                    Log.d(LC, "skipItems long playlist offset");
                    int consumeOffset = offset - numPlayerPlaylistEntries;
                    for (MediaPlayerInstance mediaPlayerInstance: playlistPlayers) {
                        mediaPlayerInstance.release();
                    }
                    playlistPlayers.clear();
                    for (int i = 0; i < consumeOffset - 1; i++) {
                        audioPlayerCallback.consumePlaylistEntry();
                    }
                    nextPlayer = new MediaPlayerInstance(
                            audioPlayerCallback.consumePlaylistEntry()
                    );
                }
            }
        } else {
            // Skip backward
            // TODO: implement
            return actionResult("Not implemented: skipItems backward");
        }
        if (nextPlayer == null) {
            return actionResult("Internal error. Could not skip items.");
        }
        if (player != null) {
            player.stop();
            player.release();
            historyPlayers.add(player);
            player = null;
        }
        setCurrentPlayer(nextPlayer);
        updatePlaybackState();
        checkPreload();
        audioPlayerCallback.onPreloadChanged();
        preparePlayers();
        if (playWhenReady) {
            return play();
        }
        return actionResult(null);
    }

    @Override
    AudioPlayerState getLastState() {
        long lastPos = player == null ? 0 : player.getCurrentPosition();
        List<PlaybackEntry> history = historyPlayers.stream()
                .map(p -> p.playbackEntry).collect(Collectors.toCollection(LinkedList::new));
        List<PlaybackEntry> entries = new LinkedList<>();
        if (player != null) {
            entries.add(player.playbackEntry);
        }
        entries.addAll(queuePlayers.stream().map(p -> p.playbackEntry).collect(Collectors.toList()));
        entries.addAll(playlistPlayers.stream().map(p -> p.playbackEntry).collect(Collectors.toList()));
        return new AudioPlayerState(history, entries, lastPos);
    }

    @Override
    public CompletableFuture<Optional<String>> seekTo(long pos) {
        if (player == null) {
            return actionResult("Player is null");
        }
        player.seekTo(pos);
        updatePlaybackState();
        return actionResult(null);
    }

    @Override
    public long getSeekPosition() {
        if (player == null) {
            return 0;
        }
        return player.getCurrentPosition();
    }

    private List<PlaybackEntry> getPreloadedEntries(int maxNum, List<MediaPlayerInstance> players) {
        List<PlaybackEntry> entries = new LinkedList<>();
        for (MediaPlayerInstance p: players) {
            if (entries.size() >= maxNum) {
                return entries;
            }
            Bundle b = p.playbackEntry.meta.getBundle();
            b.putString(
                    Meta.METADATA_KEY_PLAYBACK_PRELOADSTATUS,
                    PlaybackEntry.PRELOADSTATUS_PRELOADED
            );
            entries.add(new PlaybackEntry(Meta.from(b)));
        }
        return entries;
    }

    @Override
    List<PlaybackEntry> getPreloadedQueueEntries(int maxNum) {
        return getPreloadedEntries(maxNum, queuePlayers);
    }

    @Override
    List<PlaybackEntry> getPreloadedPlaylistEntries(int maxNum) {
        return getPreloadedEntries(maxNum, playlistPlayers);
    }

    @Override
    CompletableFuture<Optional<String>> queue(List<PlaybackEntry> playbackEntries, PlaybackQueue.QueueOp op) {
        if (playbackEntries == null || playbackEntries.isEmpty()) {
            return actionResult(null);
        }
        MediaPlayerInstance newCurrentPlayer = null;
        LinkedList<PlaybackEntry> playlistEntriesToDepreload = new LinkedList<>();
        LinkedList<PlaybackEntry> queueEntriesToDepreload = new LinkedList<>();
        List<MediaPlayerInstance> playersToQueue = new LinkedList<>();
        List<PlaybackEntry> entriesToDePreload = new LinkedList<>();

        // Remove player playlist entries to de-preload if needed.
        while (queuePlayers.size() + playlistPlayers.size()
                + playbackEntries.size() > NUM_TO_PRELOAD
                && playlistEntriesToDepreload.size() <= playbackEntries.size()
                && !playlistPlayers.isEmpty()) {
            MediaPlayerInstance m = playlistPlayers.pollLast();
            m.release();
            playlistEntriesToDepreload.addFirst(m.playbackEntry);
        }
        if (op.equals(PlaybackQueue.QueueOp.NEXT)) {
            // Remove player queue entries to de-preload if needed.
            while (queuePlayers.size() + playlistPlayers.size()
                    + playbackEntries.size() > NUM_TO_PRELOAD
                    && playlistEntriesToDepreload.size()
                    + queueEntriesToDepreload.size() <= playbackEntries.size()
                    && !queuePlayers.isEmpty()) {
                MediaPlayerInstance m = queuePlayers.pollLast();
                m.release();
                queueEntriesToDepreload.addFirst(m.playbackEntry);
            }
        }
        // Create new players to preload
        int numToQueue = NUM_TO_PRELOAD
                - (queuePlayers.size() - queueEntriesToDepreload.size())
                - (playlistPlayers.size() - playlistEntriesToDepreload.size());
        numToQueue = numToQueue > playbackEntries.size() ?
                playbackEntries.size() : numToQueue;
        for (PlaybackEntry p: playbackEntries.subList(0, numToQueue)) {
            MediaPlayerInstance m = new MediaPlayerInstance(p);
            m.preload();
            if (player == null) {
                newCurrentPlayer = m;
            } else {
                playersToQueue.add(m);
            }
        }
        // Put the rest to de-preload
        entriesToDePreload.addAll(playbackEntries.subList(numToQueue, playbackEntries.size()));
        // Perform the actions
        Log.d(LC, "queue()"
                + "\nqueueEntriesToDepreload: " + queueEntriesToDepreload.size()
                + "\nplaylistEntriesToDepreload: " + playlistEntriesToDepreload.size()
                + "\ncurrentPlayer: " + newCurrentPlayer
                + "\nplayersToQueue: " + playersToQueue.size()
                + "\nentriesToDepreload: " + entriesToDePreload.size());
        if (newCurrentPlayer != null) {
            setCurrentPlayer(newCurrentPlayer);
        }
        if (!playersToQueue.isEmpty()) {
            if (op.equals(PlaybackQueue.QueueOp.NEXT)) {
                queuePlayers.addAll(0, playersToQueue);
            } else {
                queuePlayers.addAll(playersToQueue);
            }
        }
        if (!playlistEntriesToDepreload.isEmpty()) {
            audioPlayerCallback.dePreloadPlaylistEntries(playlistEntriesToDepreload);
        }
        if (!queueEntriesToDepreload.isEmpty()) {
            audioPlayerCallback.dePreloadQueueEntries(queueEntriesToDepreload, op);
        }
        if (!entriesToDePreload.isEmpty()) {
            audioPlayerCallback.dePreloadQueueEntries(entriesToDePreload, op);
        }
        return actionResult(null);
    }

    @Override
    CompletableFuture<Optional<String>> dequeue(int queuePosition) {
        if (queuePosition < 0) {
            return actionResult("Can not dequeue negative index: " + queuePosition);
        }
        if (queuePosition < queuePlayers.size()) {
            queuePlayers.remove(queuePosition).release();
            return actionResult(null);
        }
        audioPlayerCallback.consumeQueueEntry(queuePosition - queuePlayers.size());
        return actionResult(null);
    }

    @Override
    public CompletableFuture<Optional<String>> previous() {
        // TODO: implement
        Log.e(LC, "previous not implemented");
        return actionResult("Not implemented");
    }

    private class MediaPlayerInstance {
        private MediaPlayer mediaPlayer;
        private MediaPlayerState state;
        private PlaybackEntry playbackEntry;
        private boolean buffering = false;

        MediaPlayerInstance(PlaybackEntry playbackEntry) {
            reconstruct();
            this.playbackEntry = playbackEntry;
        }

        private void reconstruct() {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
            );
            mediaPlayer.setOnErrorListener(mediaPlayerErrorListener);
            mediaPlayer.setOnPreparedListener(mp -> {
                buffering = false;
                state = MediaPlayerState.PREPARED;
                Log.d(LC, "MediaPlayer(" + title() + ") prepared");
                if (this.equals(player)) {
                    Log.d(LC, "onReady: " + title());
                    updatePlaybackState();
                    if (playWhenReady) {
                        play();
                        updatePlaybackState();
                    }
                }
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                Log.d(LC, "MediaPlayer(" + title() + ") completed");
                state = MediaPlayerState.PLAYBACK_COMPLETED;
                if (this.equals(player)) {
                    next();
                } else {
                    Log.e(LC, "This should never happen...");
                }
            });
            state = MediaPlayerState.IDLE;
        }

        void preload() {
            switch (state) {
                case IDLE:
                    break;
                case NULL:
                    reconstruct();
                    break;
                default:
                    Log.w(LC, "MediaPlayer(" + title() + ") preload in wrong state: " + state);
                    return;
            }
            buffering = true;
            if (this.equals(player)) {
                audioPlayerCallback.onStateChanged(PlaybackStateCompat.STATE_BUFFERING);
            }
            musicLibraryService.getAudioData(playbackEntry.entryID, new AudioDataDownloadHandler() {
                @Override
                public void onStart() {
                    Log.d(LC, "MediaPlayer(" + title() + ") getting audio data");
                }

                @Override
                public void onSuccess(AudioDataSource audioDataSource) {
                    Log.d(LC, "MediaPlayer(" + title() + ") successfully got audio data");
                    initialize(audioDataSource);
                }

                @Override
                public void onFailure(String status) {
                    Log.e(LC, "MediaPlayer(" + title() + ") could not get audio data: "
                            + status +".\nRetrying...");
                    // TODO: Restrict number of attempts and show user error when maxed out.
                    preload();
                }
            });
        }

        private void initialize(AudioDataSource audioDataSource) {
            switch (state) {
                case IDLE:
                    break;
                default:
                    Log.w(LC, "MediaPlayer(" + title() + ") "
                            + "initialize in wrong state: " + state);
                    return;
            }
            Log.d(LC, "MediaPlayer(" + title() + ") initializing");
            mediaPlayer.setDataSource(audioDataSource);
            state = MediaPlayerState.INITIALIZED;
            prepare();
        }

        private void prepare() {
            switch (state) {
                case INITIALIZED:
                case STOPPED:
                    break;
                default:
                    Log.w(LC, "MediaPlayer(" + title() + ") "
                            + "prepare in wrong state: " + state);
                    return;
            }
            Log.d(LC, "MediaPlayer(" + title() + ") preparing");
            mediaPlayer.prepareAsync();
            state = MediaPlayerState.PREPARING;
        }

        boolean play() {
            switch (state) {
                case PREPARED:
                case PAUSED:
                case PLAYBACK_COMPLETED:
                    break;
                case STARTED:
                    Log.d(LC, "MediaPlayer(" + title() + ") play in STARTED");
                    return false;
                case IDLE:
                    preload();
                    return false;
                case STOPPED:
                    prepare();
                    return false;
                default:
                    Log.w(LC, "MediaPlayer(" + title() + ") play in wrong state: " + state);
                    return false;
            }
            Log.d(LC, "MediaPlayer(" + title() + ") starting");
            mediaPlayer.start();
            state = MediaPlayerState.STARTED;
            return true;
        }

        boolean pause() {
            switch (state) {
                case PAUSED:
                case STARTED:
                    break;
                default:
                    Log.w(LC, "MediaPlayer(" + title() + ") pause in wrong state: " + state);
                    return false;
            }
            if (mediaPlayer == null) {
                return false;
            }
            Log.d(LC, "MediaPlayer(" + title() + ") pausing");
            mediaPlayer.pause();
            state = MediaPlayerState.PAUSED;
            return true;
        }

        boolean stop() {
            switch (state) {
                case PREPARED:
                case STARTED:
                case PAUSED:
                case PLAYBACK_COMPLETED:
                    break;
                case STOPPED:
                case NULL:
                    return true;
                default:
                    Log.w(LC, "MediaPlayer(" + title() + ") stop in wrong state: " + state);
                    return false;
            }
            Log.d(LC, "MediaPlayer(" + title() + ") stopping");
            mediaPlayer.stop();
            state = MediaPlayerState.STOPPED;
            return true;
        }

        void release() {
            if (mediaPlayer != null) {
                Log.d(LC, "MediaPlayer(" + title() + ") releasing");
                mediaPlayer.release();
                mediaPlayer = null;
            }
            state = MediaPlayerState.NULL;
        }

        boolean seekTo(long pos) {
            switch (state) {
                case PREPARED:
                case STARTED:
                case PAUSED:
                case PLAYBACK_COMPLETED:
                    break;
                default:
                    Log.w(LC, "MediaPlayer(" + title() + ") seekTo in wrong state: " + state);
                    return false;
            }
            Log.d(LC, "MediaPlayer(" + title() + ") seeking");
            mediaPlayer.seekTo((int) pos);
            return true;
        }

        long getCurrentPosition() {
            switch (state) {
                case PREPARED:
                case STARTED:
                case PAUSED:
                case PLAYBACK_COMPLETED:
                case STOPPED:
                    break;
                default:
                    Log.w(LC, "MediaPlayer(" + title() + ") "
                            + "getPlayerSeekPosition in wrong state: " + state);
                    return 0L;
            }
            Log.d(LC, "MediaPlayer(" + title() + ") getPlayerSeekPosition");
            return mediaPlayer.getCurrentPosition();
        }

        boolean isReady() {
            switch (state) {
                case PREPARED:
                case STARTED:
                case PAUSED:
                case PLAYBACK_COMPLETED:
                    return true;
                default:
                    return false;
            }
        }

        boolean isStopped() {
            return MediaPlayerState.STOPPED.equals(state);
        }


        boolean isIdle() {
            return MediaPlayerState.IDLE.equals(state);
        }

        private int getPlaybackState() {
            switch (state) {
                case STARTED:
                    return PlaybackStateCompat.STATE_PLAYING;
                case PLAYBACK_COMPLETED:
                case PAUSED:
                case PREPARED:
                    return PlaybackStateCompat.STATE_PAUSED;
                case IDLE:
                    if (buffering) {
                        return PlaybackStateCompat.STATE_BUFFERING;
                    }
                case NULL:
                case STOPPED:
                case INITIALIZED:
                    return PlaybackStateCompat.STATE_STOPPED;
                case PREPARING:
                    return PlaybackStateCompat.STATE_BUFFERING;
                default:
                    Log.w(LC, "MediaPlayer(" + title() + ") unknown state");
                    return PlaybackStateCompat.STATE_ERROR;
            }
        }

        private final MediaPlayer.OnErrorListener mediaPlayerErrorListener = (mp, what, extra) -> {
            String msg;
            switch (what) {
                case MediaPlayer.MEDIA_ERROR_UNKNOWN:
                    switch (extra) {
                        case MediaPlayer.MEDIA_ERROR_IO:
                            msg = "MEDIA_ERROR_IO";
                            break;
                        case MediaPlayer.MEDIA_ERROR_MALFORMED:
                            msg = "MEDIA_ERROR_MALFORMED";
                            break;
                        case MediaPlayer.MEDIA_ERROR_UNSUPPORTED:
                            msg = "MEDIA_ERROR_UNSUPPORTED";
                            break;
                        case MediaPlayer.MEDIA_ERROR_TIMED_OUT:
                            msg = "MEDIA_ERROR_TIMED_OUT";
                            break;
                        case 0x80000000: // MediaPlayer.MEDIA_ERROR_SYSTEM
                            msg = "MEDIA_ERROR_SYSTEM (low level weird error)";
                            break;
                        default:
                            msg = "Unhandled MEDIA_ERROR_UNKNOWN error extra: " + extra;
                            break;
                    }
                    break;
                case MediaPlayer.MEDIA_ERROR_SERVER_DIED:
                    msg = "MEDIA_ERROR_SERVER_DIED";
                    break;
                case MediaPlayer.MEDIA_ERROR_UNSUPPORTED:
                    msg = "MEDIA_ERROR_UNSUPPORTED";
                    break;
                default:
                    msg = "Unhandled error: " + what;
                    break;
            }
            Log.e(LC, "MediaPlayer(" + title() + ") error: " + msg);
            return false;
        };

        private String title() {
            return playbackEntry.meta.getString(Meta.METADATA_KEY_TITLE);
        }
    }

    private void updatePlaybackState() {
        if (player == null) {
            audioPlayerCallback.onStateChanged(PlaybackStateCompat.STATE_STOPPED);
            return;
        }
        audioPlayerCallback.onStateChanged(player.getPlaybackState());
    }

    private void setCurrentPlayer(MediaPlayerInstance mediaPlayerInstance) {
        player = mediaPlayerInstance;
        Log.d(LC, "setCurrentPlayer: " + (player == null ? "null" : player.title()));
        if (player == null) {
            audioPlayerCallback.onMetaChanged(EntryID.from(Meta.UNKNOWN_ENTRY));
        } else {
            player.preload();
            audioPlayerCallback.onMetaChanged(player.playbackEntry.entryID);
        }
    }
}
