package se.splushii.dancingbunnies.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import se.splushii.dancingbunnies.R;
import se.splushii.dancingbunnies.audioplayer.PlaybackEntry;
import se.splushii.dancingbunnies.musiclibrary.EntryID;
import se.splushii.dancingbunnies.musiclibrary.Meta;
import se.splushii.dancingbunnies.musiclibrary.PlaylistID;
import se.splushii.dancingbunnies.musiclibrary.PlaylistItem;
import se.splushii.dancingbunnies.util.Util;

public class PlaybackControllerStorage {
    private static final String LC = Util.getLogContext(PlaybackControllerStorage.class);
    public static final int QUEUE_ID_QUEUE = 0;
    public static final int QUEUE_ID_PLAYLIST = 1;
    public static final int QUEUE_ID_HISTORY = 2;
    public static final int QUEUE_ID_LOCALAUDIOPLAYER_QUEUE = 3;
    public static final int QUEUE_ID_LOCALAUDIOPLAYER_PLAYLIST = 4;
    public static final int QUEUE_ID_LOCALAUDIOPLAYER_HISTORY = 5;

    private final RoomPlaybackControllerEntryDao entryModel;
    private final SharedPreferences preferences;
    private final String src_key;
    private final String id_key;
    private final String lastPos_key;
    private final String playlist_src_key;
    private final String playlist_id_key;
    private final String playlist_type_key;
    private final String playlist_name_key;
    private final String playlist_position_key;

    public PlaybackControllerStorage(Context context) {
        entryModel = RoomDB.getDB(context).playbackControllerEntryModel();
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        playlist_src_key = context.getResources().getString(
                R.string.pref_key_playbackcontroller_playlist_src
        );
        playlist_id_key = context.getResources().getString(
                R.string.pref_key_playbackcontroller_playlist_id
        );
        playlist_type_key = context.getResources().getString(
                R.string.pref_key_playbackcontroller_playlist_type
        );
        playlist_name_key = context.getResources().getString(
                R.string.pref_key_playbackcontroller_playlist_name
        );
        playlist_position_key = context.getResources().getString(
                R.string.pref_key_playbackcontroller_position_name
        );
        src_key = context.getResources().getString(R.string.pref_key_localaudioplayer_current_src);
        id_key = context.getResources().getString(R.string.pref_key_localaudioplayer_current_id);
        lastPos_key = context.getResources().getString(R.string.pref_key_localaudioplayer_current_lastpos);
    }

    public static String getQueueName(int queueID) {
        switch (queueID) {
            case PlaybackControllerStorage.QUEUE_ID_QUEUE:
                return "queue";
            case PlaybackControllerStorage.QUEUE_ID_PLAYLIST:
                return "playlist";
            case PlaybackControllerStorage.QUEUE_ID_HISTORY:
                return "history";
            case PlaybackControllerStorage.QUEUE_ID_LOCALAUDIOPLAYER_QUEUE:
                return "localaudioplayer_queue";
            case PlaybackControllerStorage.QUEUE_ID_LOCALAUDIOPLAYER_PLAYLIST:
                return "localaudioplayer_playlist";
            case PlaybackControllerStorage.QUEUE_ID_LOCALAUDIOPLAYER_HISTORY:
                return "localaudioplayer_history";
            default:
                return "unknown";
        }
    }

    public LiveData<List<PlaybackEntry>> getQueueEntries() {
        return getEntries(PlaybackControllerStorage.QUEUE_ID_QUEUE);
    }

    public LiveData<List<PlaybackEntry>> getPlaylistEntries() {
        return getEntries(PlaybackControllerStorage.QUEUE_ID_PLAYLIST);
    }

    public LiveData<List<PlaybackEntry>> getHistoryEntries() {
        return getEntries(PlaybackControllerStorage.QUEUE_ID_HISTORY);
    }

    private LiveData<List<PlaybackEntry>> getEntries(int queueID) {
        String playbackType = getPlaybackType(queueID);
        if (playbackType == null) {
                return new MutableLiveData<>();
        }
        return Transformations.map(entryModel.getEntries(queueID), entries ->
                RoomPlaybackControllerEntryList2PlaybackEntryList(entries, playbackType)
        );
    }

    private List<PlaybackEntry> RoomPlaybackControllerEntryList2PlaybackEntryList(
            List<RoomPlaybackControllerEntry> roomPlaybackControllerEntries,
            String playbackType) {
        return roomPlaybackControllerEntries.stream().map(entry ->
                new PlaybackEntry(
                        new EntryID(entry.api, entry.id, Meta.METADATA_KEY_MEDIA_ID),
                        playbackType,
                        "type: " + playbackType + " pos: " + entry.pos
                )
        ).collect(Collectors.toList());
    }

    public CompletableFuture<Void> insert(int queueID, int toPosition, List<EntryID> entries) {
        return CompletableFuture.supplyAsync(() -> {
            List<RoomPlaybackControllerEntry> roomEntries = new ArrayList<>();
            int entryPosition = toPosition;
            StringBuilder sb = new StringBuilder();
            for (EntryID entryID: entries) {
                sb.append("insert entryID: ").append(entryID)
                        .append(" pos: ").append(entryPosition)
                        .append("\n");
                roomEntries.add(RoomPlaybackControllerEntry.from(queueID, entryID, entryPosition++));
            }
            int numNewEntries = entries.size();
            Log.d(LC, "insert to " + getQueueName(queueID) + ":\n"
                    + "updatepos from: " + toPosition
                    + " inc: " + numNewEntries
                    + "\n" + sb.toString());
            entryModel._update_pos_before_insert(queueID, toPosition, numNewEntries);
            entryModel._insert(roomEntries);
            return null;
        });
    }

    public CompletableFuture<Void> remove(int queueID, List<Integer> positions) {
        return CompletableFuture.supplyAsync(() -> {
            entryModel.remove(queueID, positions);
            return null;
        });
    }

    public CompletableFuture<Void> removeAll(int queueID) {
        return CompletableFuture.supplyAsync(() -> {
            entryModel.removeAll(queueID);
            return null;
        });
    }

    public void setLocalAudioPlayerCurrent(EntryID entryID, long lastPos) {
        preferences.edit()
                .putString(src_key, entryID.src)
                .putString(id_key, entryID.id)
                .putLong(lastPos_key, lastPos)
                .apply();
    }

    public void removeLocalAudioPlayerCurrent() {
        preferences.edit()
                .remove(src_key)
                .remove(id_key)
                .apply();
    }

    public EntryID getLocalAudioPlayerCurrentEntry() {
        String src = preferences.getString(src_key, null);
        String id = preferences.getString(id_key, null);
        if (src == null || id == null) {
            return null;
        }
        return new EntryID(src, id, Meta.METADATA_KEY_MEDIA_ID);
    }

    public long getLocalAudioPlayerCurrentLastPos() {
        return preferences.getLong(lastPos_key, 0);
    }

    public CompletableFuture<List<PlaybackEntry>> getLocalAudioPlayerQueueEntries() {
        return getEntriesSync(PlaybackControllerStorage.QUEUE_ID_LOCALAUDIOPLAYER_QUEUE);
    }

    public CompletableFuture<List<PlaybackEntry>> getLocalAudioPlayerPlaylistEntries() {
        return getEntriesSync(PlaybackControllerStorage.QUEUE_ID_LOCALAUDIOPLAYER_PLAYLIST);
    }

    public CompletableFuture<List<PlaybackEntry>> getLocalAudioPlayerHistoryEntries() {
        return getEntriesSync(PlaybackControllerStorage.QUEUE_ID_LOCALAUDIOPLAYER_HISTORY);
    }

    private CompletableFuture<List<PlaybackEntry>> getEntriesSync(int queueID) {
        String playbackType = getPlaybackType(queueID);
        if (playbackType == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        return CompletableFuture.supplyAsync(() -> entryModel.getEntriesSync(queueID))
                .thenApply(entries ->
                        RoomPlaybackControllerEntryList2PlaybackEntryList(entries, playbackType)
                );
    }

    private String getPlaybackType(int queueID) {
        switch (queueID) {
            case PlaybackControllerStorage.QUEUE_ID_QUEUE:
            case PlaybackControllerStorage.QUEUE_ID_LOCALAUDIOPLAYER_QUEUE:
                return PlaybackEntry.USER_TYPE_QUEUE;
            case PlaybackControllerStorage.QUEUE_ID_PLAYLIST:
            case PlaybackControllerStorage.QUEUE_ID_LOCALAUDIOPLAYER_PLAYLIST:
                return PlaybackEntry.USER_TYPE_PLAYLIST;
            case PlaybackControllerStorage.QUEUE_ID_HISTORY:
                return PlaybackEntry.USER_TYPE_HISTORY;
        }
        return null;
    }

    public PlaylistItem getCurrentPlaylist() {
        String src = preferences.getString(playlist_src_key, null);
        String id = preferences.getString(playlist_id_key, null);
        String type = preferences.getString(playlist_type_key, null);
        String name = preferences.getString(playlist_name_key, null);
        if (src == null || id == null || type == null) {
            return PlaylistItem.defaultPlaylist;
        }
        return new PlaylistItem(new PlaylistID(src, id, type), name);
    }

    public void setCurrentPlaylist(PlaylistItem playlist) {
        preferences.edit()
                .putString(playlist_src_key, playlist.playlistID.src)
                .putString(playlist_id_key, playlist.playlistID.id)
                .putString(playlist_type_key, playlist.playlistID.type)
                .putString(playlist_name_key, playlist.name)
                .apply();
    }

    public long getPlaylistPosition() {
        return preferences.getLong(playlist_position_key, 0);
    }

    public void setPlaylistPosition(long position) {
        preferences.edit()
                .putLong(playlist_position_key, position)
                .apply();
    }
}