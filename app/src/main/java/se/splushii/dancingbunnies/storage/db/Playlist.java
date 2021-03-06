package se.splushii.dancingbunnies.storage.db;

import androidx.room.Entity;
import androidx.room.Index;

@Entity(tableName = DB.TABLE_PLAYLIST_ID,
        indices = {
                @Index(value = {
                        DB.COLUMN_SRC,
                        DB.COLUMN_ID
                }, unique = true)
        },
        primaryKeys = {
                DB.COLUMN_SRC,
                DB.COLUMN_ID
        }
)
public class Playlist extends Entry {
    public static Playlist from(String src, String id) {
        Playlist playlist = new Playlist();
        playlist.src = src;
        playlist.id = id;
        return playlist;
    }
}