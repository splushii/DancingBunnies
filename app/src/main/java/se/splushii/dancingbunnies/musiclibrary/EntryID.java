package se.splushii.dancingbunnies.musiclibrary;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.v4.media.MediaBrowserCompat.MediaItem;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat.QueueItem;

import com.google.android.gms.cast.MediaMetadata;

import org.apache.lucene.document.Document;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;

import androidx.annotation.NonNull;
import se.splushii.dancingbunnies.storage.db.Entry;
import se.splushii.dancingbunnies.util.Util;

public class EntryID implements Parcelable {
    public static final String TYPE_TRACK = "track";
    public static final String TYPE_PLAYLIST = "playlist";

    static final String BUNDLE_KEY_SRC = "dancingbunnies.bundle.key.entryid.src";
    static final String BUNDLE_KEY_ID = "dancingbunnies.bundle.key.entryid.id";
    static final String BUNDLE_KEY_TYPE = "dancingbunnies.bundle.key.entryid.type";
    static final String JSON_KEY_SRC = "src";
    static final String JSON_KEY_ID = "id";
    static final String JSON_KEY_TYPE = "type";

    public static final EntryID UNKOWN = new EntryID(
            "dancingbunnies.entryid.UNKNOWN_SRC",
            "dancingbunnies.entryid.UNKNOWN_ID",
            "dancingbunnies.entryid.UNKNOWN_TYPE"
    );

    @NonNull public final String src;
    @NonNull public final String id;
    @NonNull public final String type;

    public EntryID(@NonNull String src, @NonNull String id, @NonNull String type) {
        this.src = src;
        this.id = id;
        this.type = type;
    }

    protected EntryID(Parcel in) {
        src = Objects.requireNonNull(in.readString());
        id = Objects.requireNonNull(in.readString());
        type = Objects.requireNonNull(in.readString());
    }

    public static EntryID generate(String src, String type) {
        return new EntryID(
                src,
                Util.generateID(),
                type
        );
    }

    private static boolean isUnknown(EntryID entryID) {
        return UNKOWN.equals(entryID);
    }

    public boolean isUnknown() {
        return isUnknown(this);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(src);
        dest.writeString(id);
        dest.writeString(type);
    }

    public static final Creator<EntryID> CREATOR = new Creator<EntryID>() {
        @Override
        public EntryID createFromParcel(Parcel in) {
            return new EntryID(in);
        }

        @Override
        public EntryID[] newArray(int size) {
            return new EntryID[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "{src: " + src + ", id: " + id + ", type: " + type + "}";
    }

    public String getDisplayableString() {
        return "id " + id + " from " + src;
    }

    @Override
    public int hashCode() {
        return key().hashCode();
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
        EntryID e = (EntryID) obj;
        return src.equals(e.src) && id.equals(e.id) && type.equals(e.type);
    }

    public String key() {
        return src + id + type;
    }

    public MediaDescriptionCompat toMediaDescriptionCompat() {
        Bundle b = toBundle();
        return new MediaDescriptionCompat.Builder()
                .setMediaId(id)
                .setExtras(b)
                .build();
    }

    public static EntryID from(MediaItem item) {
        Bundle b = item.getDescription().getExtras();
        return from(b);
    }

    public static EntryID from(MediaDescriptionCompat description) {
        Bundle b = description.getExtras();
        return from(b);
    }

    public static EntryID from(QueueItem queueItem) {
        Bundle b = queueItem.getDescription().getExtras();
        return from(b);
    }

    public static EntryID from(Bundle b) {
        String src = Objects.requireNonNull(b.getString(BUNDLE_KEY_SRC));
        String id = Objects.requireNonNull(b.getString(BUNDLE_KEY_ID));
        String type = Objects.requireNonNull(b.getString(BUNDLE_KEY_TYPE));
        return new EntryID(src, id, type);
    }

    public Bundle toBundle() {
        Bundle b = new Bundle();
        b.putString(BUNDLE_KEY_SRC, src);
        b.putString(BUNDLE_KEY_ID, id);
        b.putString(BUNDLE_KEY_TYPE, type);
        return b;
    }

    public static EntryID from(QueryEntry e) {
        return new EntryID(e.src(), e.id(), e.type());
    }

    public static EntryID from(Document doc) {
        String src = doc.get(Meta.FIELD_SPECIAL_ENTRY_SRC);
        String id = doc.get(Meta.FIELD_SPECIAL_ENTRY_ID_TRACK);
        String type = Meta.FIELD_SPECIAL_ENTRY_ID_TRACK;
        return new EntryID(src, id, type);
    }

    public static EntryID from(MediaMetadata castMeta) {
        String src = castMeta.getString(BUNDLE_KEY_SRC);
        String id = castMeta.getString(BUNDLE_KEY_ID);
        String type = castMeta.getString(BUNDLE_KEY_TYPE);
        return new EntryID(src, id, type);
    }

    public static EntryID from(MediaMetadataCompat meta) {
        return EntryID.from(meta.getBundle());
    }

    public static EntryID from(JSONObject json) throws JSONException {
        String src = json.getString(JSON_KEY_SRC);
        String id = json.getString(JSON_KEY_ID);
        String type = json.getString(JSON_KEY_TYPE);
        return new EntryID(src, id, type);
    }

    public static EntryID from(Entry entry, String entryType) {
        return new EntryID(entry.src, entry.id, entryType);
    }

    public JSONObject toJSON() {
        JSONObject root = new JSONObject();
        try {
            root.put(JSON_KEY_SRC, src);
            root.put(JSON_KEY_ID, id);
            root.put(JSON_KEY_TYPE, type);
        } catch (JSONException e) {
            e.printStackTrace();
            return null;
        }
        return root;
    }
}
