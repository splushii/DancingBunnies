package se.splushii.dancingbunnies.audioplayer;

import android.support.v4.media.MediaMetadataCompat;

import se.splushii.dancingbunnies.musiclibrary.EntryID;

public class PlaybackEntry {
    public final EntryID entryID;
    public final MediaMetadataCompat meta;

    // TODO: Why not use LibraryEntry for this instead?
    public PlaybackEntry(EntryID entryID, MediaMetadataCompat meta) {
        this.entryID = entryID;
        this.meta = meta;
    }

    @Override
    public String toString() {
        return entryID.toString() + ":  " + meta.getDescription().getDescription();
    }

    @Override
    public int hashCode() {
        return entryID.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        PlaybackEntry e = (PlaybackEntry) obj;
        return entryID.equals(e.entryID);
    }
}
