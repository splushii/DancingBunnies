package se.splushii.dancingbunnies.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.RatingCompat;
import android.util.ArrayMap;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.ArrayList;

import se.splushii.dancingbunnies.musiclibrary.Meta;
import se.splushii.dancingbunnies.musiclibrary.Song;
import se.splushii.dancingbunnies.util.Util;

public class Storage {
    private static final String LC = Util.getLogContext(Storage.class);

    private final Context context;
    private DB db;
    private SQLiteDatabase database;
    private int connectedClients = 0;

    public Storage(Context context) {
        this.context = context;
    }

    public synchronized Storage open() {
        if (connectedClients == 0 && database == null) {
            db = new DB(context);
            database = db.getWritableDatabase();
        }
        connectedClients++;
        Log.d(LC, "open(): connected clients: " + connectedClients);
        return this;
    }

    public synchronized void close() {
        if (connectedClients > 0) {
            connectedClients--;
        }
        Log.d(LC, "close(): connected clients: " + connectedClients);
        if (connectedClients == 0) {
            db.close();
            database = null;
        }
    }

    public void insertSongs(ArrayList<Song> songs) {
        long start = System.currentTimeMillis();
        Log.d(LC, "insertSongs start");
        database.beginTransaction();
        for (Song song: songs) {
            insertSong(song);
        }
        database.setTransactionSuccessful();
        database.endTransaction();
        Log.d(LC, "insertSongs finish " + (System.currentTimeMillis() - start));
    }

    private void insertSong(Song song) {
        ContentValues c = new ContentValues();
        MediaMetadataCompat meta = song.meta();
        for (String key: meta.keySet()) {
            Meta.Type type = Meta.getType(key);
            switch (type) {
                case STRING:
                    c.put(DB.Keyify(key), meta.getString(key));
                    break;
                case LONG:
                    c.put(DB.Keyify(key), meta.getLong(key));
                    break;
                case BITMAP:
                    Bitmap b = meta.getBitmap(key);
                    int bytes = b.getByteCount();
                    ByteBuffer buffer = ByteBuffer.allocate(bytes);
                    b.copyPixelsToBuffer(buffer);
                    byte[] array = buffer.array(); //Get the underlying array containing the data.
                    c.put(DB.Keyify(key), array);
                    break;
                case RATING:
                    RatingCompat rating = meta.getRating(key);
                    if (rating == null) {
                        break;
                    }
                    if (rating.getRatingStyle() != RatingCompat.RATING_5_STARS) {
                        Log.w(LC, "Rating style not RATING_5_STARS. Only RATING_5_STARTS supported.");
                        break;
                    }
                    c.put(DB.Keyify(key), rating.getStarRating());
                    break;
                default:
                    Log.w(LC, "Unhandled type: " + type);
                    break;
            }
        }
        database.replace(DB.SONG_TABLE_NAME, null, c);
    }

    private class ColumnIndexCache {
        private ArrayMap<String, Integer> mMap = new ArrayMap<>();

        private int getColumnIndex(Cursor cursor, String columnName) {
            if (!mMap.containsKey(columnName))
                mMap.put(columnName, cursor.getColumnIndex(columnName));
            return mMap.get(columnName);
        }

        private void clear() {
            mMap.clear();
        }
    }

    public ArrayList<MediaMetadataCompat> getAll() {
        long start = System.currentTimeMillis();
        Log.d(LC, "getAll start");
        ColumnIndexCache cache = new ColumnIndexCache();
        ArrayList<MediaMetadataCompat> list = new ArrayList<>();
        Cursor cursor = database.rawQuery("SELECT * FROM " + DB.SONG_TABLE_NAME, null);
        Log.i(LC, "Entries in 'songs' table: " + cursor.getCount());
        if (cursor.moveToFirst()) {
            while (!cursor.isAfterLast()) {
                MediaMetadataCompat.Builder b = new MediaMetadataCompat.Builder();
                for (String key: Meta.keys) {
                    Meta.Type type = Meta.getType(key);
                    int index = cache.getColumnIndex(cursor, DB.Keyify(key));
                    if (index == -1 && (type == Meta.Type.STRING || type == Meta.Type.LONG)) {
                        Log.w(LC, "Could not find column: " + DB.Keyify(key));
                        continue;
                    }
                    switch (type) {
                        case STRING:
                            String sValue = cursor.getString(index);
                            if (sValue == null) {
                                break;
                            }
                            b.putString(key, sValue);
                            break;
                        case LONG:
                            // Fixme: May give '0' value when it in fact is null.
                            Long lValue = cursor.getLong(index);
                            b.putLong(key, lValue);
                            break;
                        case BITMAP:
                            byte[] array = cursor.getBlob(index);
                            if (array == null) {
                                break;
                            }
                            Bitmap bitmap = BitmapFactory.decodeByteArray(array, 0, array.length);
                            b.putBitmap(key, bitmap);
                            break;
                        case RATING:
                            // Fixme: May give '0' value when it in fact is null.
                            float stars = cursor.getFloat(index);
                            RatingCompat rating = RatingCompat.newStarRating(RatingCompat.RATING_5_STARS, stars);
                            b.putRating(key, rating);
                            break;
                        default:
                            Log.w(LC, "Unhandled type: " + type);
                            break;
                    }
                }
                list.add(b.build());
                cursor.moveToNext();
            }
        }
        cache.clear();
        cursor.close();
        Log.d(LC, "getAll finish " + (System.currentTimeMillis() - start));
        return list;
    }

    public void clearAll() {
        database.delete(DB.SONG_TABLE_NAME, null, null);
    }

    public void clearAll(String src) {
        database.delete(DB.SONG_TABLE_NAME,
                DB.Keyify(Meta.METADATA_KEY_API) + "=?",
                new String[]{src});
    }
}
