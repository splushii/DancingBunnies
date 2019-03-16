package se.splushii.dancingbunnies.ui.musiclibrary;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.support.v4.media.MediaBrowserCompat;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.recyclerview.selection.ItemDetailsLookup;
import androidx.recyclerview.selection.SelectionTracker;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import se.splushii.dancingbunnies.R;
import se.splushii.dancingbunnies.audioplayer.AudioPlayerService;
import se.splushii.dancingbunnies.musiclibrary.EntryID;
import se.splushii.dancingbunnies.musiclibrary.LibraryEntry;
import se.splushii.dancingbunnies.musiclibrary.Meta;
import se.splushii.dancingbunnies.ui.MetaDialogFragment;

public class MusicLibraryAdapter extends RecyclerView.Adapter<MusicLibraryAdapter.SongViewHolder> {
    private final MusicLibraryFragment fragment;
    private final LinearLayoutManager layoutManager;
    private List<MediaBrowserCompat.MediaItem> dataset;
    private SongViewHolder selectedHolder;
    private SelectionTracker<EntryID> selectionTracker;

    MusicLibraryAdapter(MusicLibraryFragment fragment,
                        LinearLayoutManager recViewLayoutManager) {
        this.dataset = new ArrayList<>();
        this.fragment = fragment;
        this.layoutManager = recViewLayoutManager;
    }

    void setModel(MusicLibraryFragmentModel model) {
        model.getDataSet().observe(fragment.getViewLifecycleOwner(), dataset -> {
            MusicLibraryUserState state = model.getUserState().getValue();
            if(!state.query.isSearchQuery()
                    && !Meta.METADATA_KEY_MEDIA_ID.equals(
                    state.query.toBundle().getString(Meta.METADATA_KEY_TYPE))) {
                dataset.add(0, AudioPlayerService.generateMediaItem(
                        new LibraryEntry(EntryID.from(Meta.UNKNOWN_ENTRY), "All entries...")
                ));
            }
            setDataset(dataset);
            layoutManager.scrollToPositionWithOffset(state.pos, state.pad);
        });
    }

    MediaBrowserCompat.MediaItem getItemData(int childPosition) {
        return dataset.get(childPosition);
    }

    void setSelectionTracker(SelectionTracker<EntryID> selectionTracker) {
        this.selectionTracker = selectionTracker;
        selectionTracker.addObserver(new SelectionTracker.SelectionObserver() {
            @Override
            public void onSelectionChanged() {
                if (selectionTracker.hasSelection() && selectedHolder != null) {
                    selectedHolder.animateMoreActions(false);
                    selectedHolder = null;
                }
            }
        });
    }

    public static class SongViewHolder extends RecyclerView.ViewHolder {
        private final View libraryEntry;
        private final TextView libraryEntryTitle;
        private final TextView libraryEntryArtist;
        private final TextView libraryEntryAlbum;
        private final View playAction;
        private final View queueAction;
        private final View addToPlaylistAction;
        private final ImageButton overflowMenu;
        private final View moreActions;
        private EntryID entryId;
        public Meta meta;

        private final ItemDetailsLookup.ItemDetails<EntryID> itemDetails = new ItemDetailsLookup.ItemDetails<EntryID>() {
            @Override
            public int getPosition() {
                return getAdapterPosition();
            }

            @Nullable
            @Override
            public EntryID getSelectionKey() {
                return getEntryId();
            }
        };

        SongViewHolder(View view) {
            super(view);
            libraryEntry = view.findViewById(R.id.library_entry);
            libraryEntryTitle = view.findViewById(R.id.library_entry_title);
            libraryEntryAlbum = view.findViewById(R.id.library_entry_album);
            libraryEntryArtist = view.findViewById(R.id.library_entry_artist);
            playAction = view.findViewById(R.id.library_entry_action_play);
            queueAction = view.findViewById(R.id.library_entry_action_queue);
            addToPlaylistAction = view.findViewById(R.id.library_entry_action_add_to_playlist);
            overflowMenu = view.findViewById(R.id.library_entry_overflow_menu);
            moreActions = view.findViewById(R.id.library_entry_more_actions);
        }

        public ItemDetailsLookup.ItemDetails<EntryID> getItemDetails() {
            return itemDetails;
        }

        void setEntryId(EntryID entryId) {
            this.entryId = entryId;
        }

        EntryID getEntryId() {
            return entryId;
        }

        void animateMoreActions(boolean show) {
            if (show) {
                moreActions.animate()
                        .translationX(0)
                        .setDuration(200)
                        .alpha(1)
                        .setListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationStart(Animator animation) {
                                moreActions.setVisibility(View.VISIBLE);
                            }
                        })
                        .start();
            } else {
                moreActions.animate()
                        .translationX(moreActions.getWidth())
                        .alpha(0)
                        .setDuration(200)
                        .setListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                moreActions.setVisibility(View.INVISIBLE);
                            }
                        })
                        .start();
            }
        }

    }

    @NonNull
    @Override
    public SongViewHolder onCreateViewHolder(@NonNull ViewGroup parent,
                                             int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.musiclibrary_item, parent, false);
        return new SongViewHolder(v);
    }

    private void setDataset(List<MediaBrowserCompat.MediaItem> items) {
        this.dataset = items;
        notifyDataSetChanged();
    }

    EntryID getEntryId(int position) {
        return EntryID.from(dataset.get(position));
    }

    int getEntryIdPosition(@NonNull EntryID entryID) {
        int index = 0;
        for (MediaBrowserCompat.MediaItem item: dataset) {
            if (entryID.equals(EntryID.from(item))) {
                return index;
            }
            index++;
        }
        return RecyclerView.NO_POSITION;
    }

    @Override
    public void onBindViewHolder(@NonNull final SongViewHolder holder, int position) {
        final MediaBrowserCompat.MediaItem item = dataset.get(position);
        final String title = item.getDescription().getTitle() + "";
        EntryID entryID = EntryID.from(item);
        holder.setEntryId(entryID);
        final boolean browsable = item.isBrowsable();
        holder.moreActions.setVisibility(View.INVISIBLE);
        holder.moreActions.setTranslationX(holder.moreActions.getWidth());
        holder.moreActions.setAlpha(0);
        holder.libraryEntryTitle.setText(title);
        holder.meta = null;
        if (Meta.METADATA_KEY_MEDIA_ID.equals(entryID.type)) {
            fragment.getSongMeta(entryID).thenAccept(meta -> {
                holder.meta = meta;
                String artist = meta.getString(Meta.METADATA_KEY_ARTIST);
                String album = meta.getString(Meta.METADATA_KEY_ALBUM);
                holder.libraryEntryArtist.setText(artist);
                holder.libraryEntryAlbum.setText(album);
                holder.libraryEntryArtist.setVisibility(View.VISIBLE);
                holder.libraryEntryAlbum.setVisibility(View.VISIBLE);
            });
        } else {
            holder.libraryEntryArtist.setVisibility(View.GONE);
            holder.libraryEntryAlbum.setVisibility(View.GONE);
        }
        if (position % 2 == 0) {
            holder.libraryEntry.setBackgroundResource(R.drawable.musiclibrary_item_drawable);
        } else {
            holder.libraryEntry.setBackgroundResource(R.drawable.musiclibrary_item_drawable_odd);
        }
        boolean selected = selectionTracker != null
                && selectionTracker.isSelected(holder.getItemDetails().getSelectionKey());
        holder.libraryEntry.setActivated(selected);
        holder.libraryEntry.setOnClickListener(view -> {
            if (browsable) {
                fragment.browse(entryID);
            } else {
                if (selectedHolder != null && selectedHolder != holder) {
                    selectedHolder.animateMoreActions(false);
                }
                selectedHolder = holder;
                boolean show = !selectionTracker.hasSelection()
                        && holder.moreActions.getVisibility() != View.VISIBLE;
                holder.animateMoreActions(show);
            }
        });
        holder.playAction.setOnClickListener(v -> {
            fragment.play(entryID);
            holder.animateMoreActions(false);
        });
        holder.queueAction.setOnClickListener(v -> {
            fragment.queue(entryID);
            holder.animateMoreActions(false);
        });
        holder.addToPlaylistAction.setOnClickListener(v -> {
            fragment.addToPlaylist(Collections.singletonList(entryID));
            holder.animateMoreActions(false);
        });
        holder.overflowMenu.setOnClickListener(v -> {
//            holder.animateMoreActions(false);
            FragmentTransaction ft = fragment.getFragmentManager().beginTransaction();
            Fragment prev = fragment.getFragmentManager().findFragmentByTag(MetaDialogFragment.TAG);
            if (prev != null) {
                ft.remove(prev);
            }
            ft.addToBackStack(null);
            DialogFragment dialogFragment = new MetaDialogFragment();
            dialogFragment.setTargetFragment(fragment, MetaDialogFragment.REQUEST_CODE);
            if (holder.meta != null) {
                dialogFragment.setArguments(holder.meta.getBundle());
            }
            dialogFragment.show(ft, MetaDialogFragment.TAG);
        });
    }

    Pair<Integer, Integer> getCurrentPosition() {
        RecyclerView rv = fragment.getView().findViewById(R.id.musiclibrary_recyclerview);
        LinearLayoutManager llm = (LinearLayoutManager) rv.getLayoutManager();
        int hPos = llm.findFirstCompletelyVisibleItemPosition();
        View v = llm.getChildAt(0);
        int hPad = v == null ? 0 : v.getTop() - llm.getPaddingTop();
        if (hPad < 0 && hPos > 0) {
            hPos--;
        }
        return new Pair<>(hPos, hPad);
    }

    @Override
    public int getItemCount() {
        return dataset.size();
    }
}
