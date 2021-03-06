package se.splushii.dancingbunnies.musiclibrary;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.v4.media.MediaBrowserCompat;

import java.util.ArrayList;

import androidx.annotation.NonNull;

public class QueryEntry implements Comparable<QueryEntry>, Parcelable {
    private static final String BUNDLE_KEY_ENTRY_ID = "dancingbunnies.bundle.key.queryentry.entryid";
    private static final String BUNDLE_KEY_NAME = "dancingbunnies.bundle.key.queryentry.name";
    private static final String BUNDLE_KEY_SORTED_BY = "dancingbunnies.bundle.key.queryentry.sortedby";

    public final EntryID entryID;
    private final String name;
    private final ArrayList<String> sortedByValues;

    public QueryEntry(EntryID entryID, String name, ArrayList<String> sortedByValues) {
        this.entryID = entryID;
        this.name = name;
        this.sortedByValues = sortedByValues;
    }

    private QueryEntry(Parcel in) {
        entryID = in.readParcelable(EntryID.class.getClassLoader());
        name = in.readString();
        sortedByValues = new ArrayList<>();
        in.readStringList(sortedByValues);
    }

    public static final Creator<QueryEntry> CREATOR = new Creator<QueryEntry>() {
        @Override
        public QueryEntry createFromParcel(Parcel in) {
            return new QueryEntry(in);
        }

        @Override
        public QueryEntry[] newArray(int size) {
            return new QueryEntry[size];
        }
    };

    public Bundle toBundle() {
        Bundle b = new Bundle();
        b.putParcelable(BUNDLE_KEY_ENTRY_ID, entryID);
        b.putString(BUNDLE_KEY_NAME, name());
        b.putStringArrayList(BUNDLE_KEY_SORTED_BY, sortedByValues());
        return b;
    }

    public static QueryEntry from(MediaBrowserCompat.MediaItem item) {
        Bundle b = item.getDescription().getExtras();
        EntryID entryID = b.getParcelable(BUNDLE_KEY_ENTRY_ID);
        String name = b.getString(BUNDLE_KEY_NAME);
        ArrayList<String> sortedByValues = b.getStringArrayList(BUNDLE_KEY_SORTED_BY);
        return new QueryEntry(entryID, name, sortedByValues);
    }

    @Override
    public String toString() {
        return name + ":" + entryID.key();
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
        QueryEntry e = (QueryEntry) obj;
        if (name == null && e.name != null) {
            return false;
        }
        if (sortedByValues == null && e.sortedByValues != null) {
            return false;
        }
        if (EntryID.UNKOWN.equals(entryID) || EntryID.UNKOWN.equals(e.entryID)) {
            return false;
        }
        return this.entryID.equals(e.entryID)
                && (name == null || name.equals(e.name))
                && (sortedByValues == null || sortedByValues.equals(e.sortedByValues));
    }

    @Override
    public int compareTo(@NonNull QueryEntry o) {
        if (o == null || o.name == null) {
            return -1;
        }
        if (name == null) {
            return 1;
        }
        int nameVal = name.toLowerCase().compareTo(o.name.toLowerCase());
        return nameVal != 0 ? nameVal : key().compareTo(o.key());
    }

    public String src() { return entryID.src; }
    public String id() { return entryID.id; }
    public String type() { return entryID.type; }
    public String key() { return entryID.key(); }
    public String name() { return name; }
    public ArrayList<String> sortedByValues() { return sortedByValues; }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(entryID, flags);
        dest.writeString(name);
        dest.writeStringList(sortedByValues);
    }

    public boolean isBrowsable() {
        return !Meta.FIELD_SPECIAL_ENTRY_ID_TRACK.equals(entryID.type);
    }
}
