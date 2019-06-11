package se.splushii.dancingbunnies.ui.nowplaying;

import java.util.ArrayList;
import java.util.List;

import se.splushii.dancingbunnies.audioplayer.PlaybackEntry;
import se.splushii.dancingbunnies.musiclibrary.PlaylistID;
import se.splushii.dancingbunnies.storage.db.PlaylistEntry;

class NowPlayingState {
    final List<PlaybackEntry> queue;
    final PlaylistID currentPlaylistID;
    final PlaylistEntry currentPlaylistEntry;

    private NowPlayingState(List<PlaybackEntry> queue, PlaylistID currentPlaylistID, PlaylistEntry currentPlaylistEntry) {
        this.queue = queue;
        this.currentPlaylistID = currentPlaylistID;
        this.currentPlaylistEntry = currentPlaylistEntry;
    }

    static class Builder {
        private List<PlaybackEntry> queue = new ArrayList<>();
        private PlaylistID currentPlaylistID;
        private PlaylistEntry currentPlaylistEntry;

        Builder() {}

        Builder fromState(NowPlayingState state) {
            queue = state.queue;
            currentPlaylistID = state.currentPlaylistID;
            currentPlaylistEntry = state.currentPlaylistEntry;
            return this;
        }

        public Builder setQueue(List<PlaybackEntry> queue) {
            this.queue = queue;
            return this;
        }

        Builder setCurrentPlaylist(PlaylistID playlistID) {
            this.currentPlaylistID = playlistID;
            return this;
        }

        Builder setCurrentPlaylistEntry(PlaylistEntry playlistEntry) {
            this.currentPlaylistEntry = playlistEntry;
            return this;
        }

        NowPlayingState build() {
            return new NowPlayingState(
                    queue,
                    currentPlaylistID,
                    currentPlaylistEntry
            );
        }
    }
}