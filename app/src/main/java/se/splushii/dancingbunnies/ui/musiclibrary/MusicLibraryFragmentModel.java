package se.splushii.dancingbunnies.ui.musiclibrary;

import android.support.v4.media.MediaBrowserCompat;
import android.util.Pair;

import java.util.LinkedList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import se.splushii.dancingbunnies.musiclibrary.EntryID;
import se.splushii.dancingbunnies.musiclibrary.Meta;
import se.splushii.dancingbunnies.musiclibrary.MusicLibraryQuery;

public class MusicLibraryFragmentModel extends ViewModel {
    static final String INITIAL_DISPLAY_TYPE = Meta.METADATA_KEY_ARTIST;

    private MutableLiveData<MusicLibraryUserState> userState;
    private LinkedList<MusicLibraryUserState> backStack;
    private String currentSubscriptionID;
    private MutableLiveData<List<MediaBrowserCompat.MediaItem>> dataset;

    private static MusicLibraryUserState initialUserState() {
        MusicLibraryQuery query = new MusicLibraryQuery();
        query.addToQuery(Meta.METADATA_KEY_TYPE, INITIAL_DISPLAY_TYPE);
        return new MusicLibraryUserState(query, 0, 0);
    }

    private MutableLiveData<MusicLibraryUserState> getMutableUserState() {
        if (userState == null) {
            userState = new MutableLiveData<>();
            userState.setValue(initialUserState());
        }
        return userState;
    }

    LiveData<MusicLibraryUserState> getUserState() {
        return getMutableUserState();
    }

    void updateUserState(Pair<Integer, Integer> currentPosition) {
        MusicLibraryQuery query = getUserState().getValue().query;
        getMutableUserState().setValue(new MusicLibraryUserState(
                query,
                currentPosition
        ));
    }

    private LinkedList<MusicLibraryUserState> getBackStack() {
        if (backStack == null) {
            backStack = new LinkedList<>();
            backStack.push(initialUserState());
        }
        return backStack;
    }

    void addBackStackHistory(Pair<Integer, Integer> currentPosition) {
        getBackStack().push(new MusicLibraryUserState(
                getUserState().getValue().query,
                currentPosition
        ));
    }

    boolean popBackStack() {
        if (getBackStack().size() > 0) {
            getMutableUserState().setValue(getBackStack().pop());
            return true;
        }
        return false;
    }

    void filter(String filterType, String filter) {
        MusicLibraryQuery query = new MusicLibraryQuery(getUserState().getValue().query);
        query.addToQuery(filterType, filter);
        getMutableUserState().setValue(new MusicLibraryUserState(query, 0, 0));
    }

    void browse(EntryID entryID) {
        MusicLibraryQuery query = new MusicLibraryQuery(getUserState().getValue().query);
        String displayType = Meta.METADATA_KEY_ARTIST.equals(entryID.type) ?
                Meta.METADATA_KEY_ALBUM : Meta.METADATA_KEY_MEDIA_ID;
        query.addToQuery(Meta.METADATA_KEY_TYPE, displayType);
        if (entryID.id != null && !entryID.isUnknown()) {
            query.addToQuery(entryID.type, entryID.id);
        }
        getMutableUserState().setValue(new MusicLibraryUserState(query, 0, 0));
    }

    void displayType(String displayType) {
        MusicLibraryQuery query = new MusicLibraryQuery(getUserState().getValue().query);
        query.addToQuery(Meta.METADATA_KEY_TYPE, displayType);
        getMutableUserState().setValue(new MusicLibraryUserState(query, 0, 0));
    }

    void showOnly(String filterType, String filter) {
        MusicLibraryQuery query = new MusicLibraryQuery();
        query.addToQuery(filterType, filter);
        getMutableUserState().setValue(new MusicLibraryUserState(query, 0, 0));
    }

    void clearFilter(String filterType) {
        MusicLibraryQuery query = new MusicLibraryQuery(getUserState().getValue().query);
        query.removeFromQuery(filterType);
        getMutableUserState().setValue(new MusicLibraryUserState(query, 0, 0));
    }

    void setCurrentSubscriptionID(String currentSubscriptionID) {
        this.currentSubscriptionID = currentSubscriptionID;
    }

    String getCurrentSubscriptionID() {
        return currentSubscriptionID;
    }

    public void search(String query) {
        addBackStackHistory(new Pair<>(0, 0));
        getMutableUserState().setValue(new MusicLibraryUserState(
                new MusicLibraryQuery(query), 0, 0
        ));
    }

    private MutableLiveData<List<MediaBrowserCompat.MediaItem>> getMutableDataSet() {
        if (dataset == null) {
            dataset = new MutableLiveData<>();
            dataset.setValue(new LinkedList<>());
        }
        return dataset;
    }

    LiveData<List<MediaBrowserCompat.MediaItem>> getDataSet() {
        return getMutableDataSet();
    }

    public void query(MediaBrowserCompat mediaBrowser) {
        // Unsubscribe
        String currentSubscriptionID = getCurrentSubscriptionID();
        if (currentSubscriptionID != null && mediaBrowser.isConnected()) {
            mediaBrowser.unsubscribe(currentSubscriptionID);
        }
        currentSubscriptionID = getUserState().getValue().query.query(
                mediaBrowser,
                new MusicLibraryQuery.MusicLibraryQueryCallback() {
                    @Override
                    public void onQueryResult(@NonNull List<MediaBrowserCompat.MediaItem> items) {
                        getMutableDataSet().setValue(items);
                    }
                }
        );
        setCurrentSubscriptionID(currentSubscriptionID);
    }
}