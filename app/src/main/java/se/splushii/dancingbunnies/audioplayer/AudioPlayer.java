package se.splushii.dancingbunnies.audioplayer;

import android.util.Log;

import java.util.List;

import se.splushii.dancingbunnies.musiclibrary.EntryID;
import se.splushii.dancingbunnies.util.Util;

public abstract class AudioPlayer {
    private static final String LC = Util.getLogContext(AudioPlayer.class);

    enum Type {
        LOCAL,
        CAST
    }

    Callback audioPlayerCallback;
    private final Callback emptyAudioPlayerCallback = new Callback() {
        @Override
        public void onReady() {
            Log.w(LC, "onReady");
        }

        @Override
        public void onEnded() {
            Log.w(LC, "onEnded");
        }

        @Override
        public void onStateChanged(int playBackState) {
            Log.w(LC, "onStateChanged");
        }

        @Override
        public void onMetaChanged(EntryID entryID) {
            Log.w(LC, "onMetaChanged");
        }

        @Override
        public void onQueueChanged() {
            Log.w(LC, "onQueueChanged");
        }
    };
    AudioPlayer() {
        audioPlayerCallback = emptyAudioPlayerCallback;
    }
    void setListener(Callback audioPlayerCallback) {
        this.audioPlayerCallback = audioPlayerCallback;
    }
    public void removeListener() {
        audioPlayerCallback = emptyAudioPlayerCallback;
    }
    abstract long getSeekPosition();
    abstract void play();
    abstract void pause();
    abstract void stop();
    abstract void seekTo(long pos);
    abstract int getNumToPreload();
    abstract void setPreloadNext(List<PlaybackEntry> playbackEntries);
    abstract void next();
    abstract void previous();

    interface Callback {
        void onReady();
        void onEnded();
        void onStateChanged(int playBackState);
        void onMetaChanged(EntryID entryID);
        void onQueueChanged();
    }
}