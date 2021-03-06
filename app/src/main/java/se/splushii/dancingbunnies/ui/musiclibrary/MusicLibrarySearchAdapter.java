package se.splushii.dancingbunnies.ui.musiclibrary;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.recyclerview.selection.Selection;
import se.splushii.dancingbunnies.R;
import se.splushii.dancingbunnies.audioplayer.PlaybackEntry;
import se.splushii.dancingbunnies.musiclibrary.EntryID;
import se.splushii.dancingbunnies.musiclibrary.MusicLibraryService;
import se.splushii.dancingbunnies.musiclibrary.QueryEntry;
import se.splushii.dancingbunnies.storage.AudioStorage;
import se.splushii.dancingbunnies.ui.ActionModeCallback;
import se.splushii.dancingbunnies.ui.TrackItemActionsView;
import se.splushii.dancingbunnies.ui.TrackItemView;
import se.splushii.dancingbunnies.ui.selection.ItemDetailsViewHolder;
import se.splushii.dancingbunnies.ui.selection.SmartDiffSelectionRecyclerViewAdapter;
import se.splushii.dancingbunnies.util.Util;

import static se.splushii.dancingbunnies.ui.MenuActions.ACTION_CACHE;
import static se.splushii.dancingbunnies.ui.MenuActions.ACTION_CACHE_DELETE;
import static se.splushii.dancingbunnies.ui.MenuActions.ACTION_INFO;
import static se.splushii.dancingbunnies.ui.MenuActions.ACTION_PLAY;
import static se.splushii.dancingbunnies.ui.MenuActions.ACTION_PLAYLIST_ENTRY_ADD;
import static se.splushii.dancingbunnies.ui.MenuActions.ACTION_QUEUE_ADD;

public class MusicLibrarySearchAdapter extends
        SmartDiffSelectionRecyclerViewAdapter<EntryID, MusicLibrarySearchAdapter.SongViewHolder> {
    private static final String LC = Util.getLogContext(MusicLibrarySearchAdapter.class);
    private final MusicLibraryFragment fragment;
    private TrackItemActionsView selectedActionView;
    private boolean initialScrolled;
    private LiveData<HashSet<EntryID>> cachedEntriesLiveData;
    private LiveData<HashMap<EntryID, AudioStorage.AudioDataFetchState>> fetchStateLiveData;
    private LiveData<PlaybackEntry> currentEntryLiveData;

    MusicLibrarySearchAdapter(MusicLibraryFragment fragment) {
        this.fragment = fragment;
        setHasStableIds(true);
    }

    void scrollWhenReady() {
        initialScrolled = false;
    }

    void setModel(MusicLibraryFragmentModel model) {
        initialScrolled = false;
        cachedEntriesLiveData = MusicLibraryService.getCachedEntries(fragment.getContext());
        fetchStateLiveData = AudioStorage.getInstance(fragment.getContext()).getFetchState();
        model.getQueryEntries().observe(fragment.getViewLifecycleOwner(), dataset -> {
            MusicLibraryUserState state = model.getUserState().getValue();
            if (state.query.isSearchQuery()) {
                setDataset(dataset);
            } else {
                setDataset(new ArrayList<>());
            }
            updateScrollPos(state, dataset);
        });
        currentEntryLiveData = model.getCurrentEntry();
    }

    private void updateScrollPos(MusicLibraryUserState userState, List<QueryEntry> entries) {
        if (!initialScrolled && !entries.isEmpty()) {
            initialScrolled = true;
            fragment.scrollSearchTo(userState.pos, userState.pad);
        }
    }

    void hideTrackItemActions() {
        if (selectedActionView != null) {
            selectedActionView.animateShow(false);
            selectedActionView = null;
        }
    }

    @Override
    protected void onSelectionChanged() {
        if (hasSelection() && selectedActionView != null) {
            selectedActionView.animateShow(false);
            selectedActionView = null;
        }
    }

    @Override
    public void onSelectionDrop(Collection<EntryID> selection, int targetPos, EntryID idAfterTargetPos) {
        throw new RuntimeException("Not supported");
    }

    @Override
    public void onUseViewHolderForDrag(SongViewHolder dragViewHolder, Collection<EntryID> selection) {
        throw new RuntimeException("Not supported");
    }

    @Override
    public void onResetDragViewHolder(SongViewHolder dragViewHolder) {
        throw new RuntimeException("Not supported");
    }

    @Override
    public void onActionModeStarted(ActionModeCallback actionModeCallback,
                                    Selection<EntryID> selection) {
        updateActionModeView(actionModeCallback, selection);
    }

    @Override
    public void onActionModeSelectionChanged(ActionModeCallback actionModeCallback,
                                             Selection<EntryID> selection) {
        updateActionModeView(actionModeCallback, selection);
    }

    private void updateActionModeView(ActionModeCallback actionModeCallback,
                                      Selection<EntryID> selection) {
        actionModeCallback.getActionMode().setTitle(selection.size() + " entries");
    }

    @Override
    public void onActionModeEnding(ActionModeCallback actionModeCallback) {}

    @Override
    public boolean validDrag(Selection<EntryID> selection) {
        return false;
    }

    @Override
    public boolean validSelect(EntryID key) {
        return true;
    }

    @Override
    public boolean validMove(SongViewHolder current, SongViewHolder target) {
        return false;
    }

    @Override
    public boolean validDrag(SongViewHolder viewHolder) {
        return false;
    }

    public class SongViewHolder extends ItemDetailsViewHolder<EntryID> {
        private final View item;
        private final TrackItemView itemContent;
        private final TrackItemActionsView actionsView;
        private MutableLiveData<EntryID> entryIDLiveData;

        SongViewHolder(View view) {
            super(view);
            entryIDLiveData = new MutableLiveData<>();
            item = view.findViewById(R.id.musiclibrary_search_item);
            itemContent = view.findViewById(R.id.musiclibrary_search_item_content);
            actionsView = view.findViewById(R.id.musiclibrary_search_item_actions);
            item.setOnClickListener(v -> {
                if (hasSelection()) {
                    return;
                }
                if (selectedActionView != null && selectedActionView != actionsView) {
                    selectedActionView.animateShow(false);
                }
                selectedActionView = actionsView;
                actionsView.animateShow(actionsView.getVisibility() != View.VISIBLE);
            });
        }

        @Override
        protected EntryID getSelectionKeyOf() {
            return entryIDLiveData.getValue();
        }

        void update(EntryID entryID) {
            entryIDLiveData.setValue(entryID);
        }

        void updateHighlight(PlaybackEntry currentEntry) {
            boolean isCurrentEntry = currentEntry != null
                    && currentEntry.entryID.equals(entryIDLiveData.getValue());
            itemContent.setItemHighlight(isCurrentEntry);
        }
    }

    @NonNull
    @Override
    public SongViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        SongViewHolder holder = new SongViewHolder(
                LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.musiclibrary_search_item, parent, false)
        );
        currentEntryLiveData.observe(fragment.getViewLifecycleOwner(), holder::updateHighlight);
        holder.itemContent.initMetaObserver(fragment.requireContext());
        holder.itemContent.observeMeta(fragment.getViewLifecycleOwner());
        holder.itemContent.observeCachedLiveData(
                cachedEntriesLiveData,
                fragment.getViewLifecycleOwner()
        );
        holder.itemContent.observeFetchStateLiveData(
                fetchStateLiveData,
                fragment.getViewLifecycleOwner()
        );
        holder.actionsView.setAudioBrowser(fragment.getRemote());
        holder.actionsView.setFragmentManager(fragment.requireActivity().getSupportFragmentManager());
        holder.actionsView.setEntryIDSupplier(() -> holder.entryIDLiveData.getValue());
        holder.actionsView.setActions(
                new int[] {
                        ACTION_QUEUE_ADD,
                        ACTION_PLAYLIST_ENTRY_ADD,
                        ACTION_INFO
                },
                new int[] {
                        ACTION_PLAY,
                        ACTION_QUEUE_ADD,
                        ACTION_PLAYLIST_ENTRY_ADD,
                        ACTION_CACHE,
                        ACTION_CACHE_DELETE,
                        ACTION_INFO
                },
                new int[] {}
        );
        holder.actionsView.initialize();
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull final SongViewHolder holder, int position) {
        holder.item.setBackgroundResource(position % 2 == 0 ?
                R.color.background_active_accent : R.color.backgroundalternate_active_accent
        );
        EntryID entryID = getItem(position);
        holder.update(entryID);
        holder.itemContent.setEntryID(entryID);
        holder.item.setActivated(isSelected(holder.getKey()));
        holder.updateHighlight(currentEntryLiveData.getValue());
    }

    private void setDataset(List<QueryEntry> items) {
        setDataSet(items.stream().map(item -> item.entryID).collect(Collectors.toList()));
    }

    @Override
    public long getItemId(int position) {
        return getItem(position).hashCode();
    }
}
