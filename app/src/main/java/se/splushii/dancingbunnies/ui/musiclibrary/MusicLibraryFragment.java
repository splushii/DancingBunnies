package se.splushii.dancingbunnies.ui.musiclibrary;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProviders;
import androidx.recyclerview.selection.MutableSelection;
import androidx.recyclerview.selection.SelectionPredicates;
import androidx.recyclerview.selection.SelectionTracker;
import androidx.recyclerview.selection.StorageStrategy;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import se.splushii.dancingbunnies.MainActivity;
import se.splushii.dancingbunnies.R;
import se.splushii.dancingbunnies.audioplayer.AudioBrowserFragment;
import se.splushii.dancingbunnies.audioplayer.AudioPlayerService;
import se.splushii.dancingbunnies.musiclibrary.EntryID;
import se.splushii.dancingbunnies.musiclibrary.Meta;
import se.splushii.dancingbunnies.musiclibrary.MusicLibraryQuery;
import se.splushii.dancingbunnies.storage.MetaStorage;
import se.splushii.dancingbunnies.ui.EntryIDDetailsLookup;
import se.splushii.dancingbunnies.util.Util;

public class MusicLibraryFragment extends AudioBrowserFragment {
    private static final String LC = Util.getLogContext(MusicLibraryFragment.class);

    private MusicLibraryAdapter recyclerViewAdapter;
    private SelectionTracker<EntryID> selectionTracker;
    private ActionMode actionMode;

    private FastScroller fastScroller;
    private FastScrollerBubble fastScrollerBubble;

    private View searchInfo;
    private TextView searchText;

    private ChipGroup filterChips;

    private View entryTypeSelect;
    private Spinner entryTypeSelectSpinner;
    private int entryTypeSelectionPos;

    private View filterEdit;
    private TextView filterEditType;
    private EditText filterEditInput;

    private View filterNew;
    private Spinner filterNewType;

    private MusicLibraryFragmentModel model;

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        Log.d(LC, "onActivityCreated");
        super.onActivityCreated(savedInstanceState);
        model = ViewModelProviders.of(getActivity()).get(MusicLibraryFragmentModel.class);
        model.getUserState().observe(getViewLifecycleOwner(), state -> {
            refreshView(state);
            model.query(mediaBrowser);
        });
        recyclerViewAdapter.setModel(model);
    }

    @Override
    public void onStop() {
        Log.d(LC, "onStop");
        model.updateUserState(recyclerViewAdapter.getCurrentPosition());
        super.onStop();
    }

    @Override
    protected void onMediaBrowserConnected() {
        if (model != null) {
            refreshView(model.getUserState().getValue());
            model.query(mediaBrowser);
        }
    }

    private void refreshView(final MusicLibraryUserState newUserState) {
        Log.d(LC, "refreshView");
        searchInfo.setVisibility(View.GONE);
        entryTypeSelect.setVisibility(View.GONE);
        filterEdit.setVisibility(View.GONE);
        filterNew.setVisibility(View.GONE);
        clearFilterView();
        if (newUserState.query.isSearchQuery()) {
            fastScroller.enableBubble(false);
            searchText.setText(newUserState.query.getSearchQuery());
            searchInfo.setVisibility(View.VISIBLE);
        } else {
            fastScroller.enableBubble(true);
            Chip chip = new Chip(requireContext());
            chip.setChipIconResource(R.drawable.ic_add_black_24dp);
            chip.setTextStartPadding(0.0f);
            chip.setTextEndPadding(0.0f);
            chip.setChipEndPadding(chip.getChipStartPadding());
            chip.setOnClickListener(v -> {
                entryTypeSelect.setVisibility(View.GONE);
                filterEdit.setVisibility(View.GONE);
                filterNew.setVisibility(filterNew.getVisibility() == View.VISIBLE ?
                        View.GONE : View.VISIBLE
                );
            });
            filterChips.addView(chip);
            String showType = newUserState.query.getShowType();
            addFilterToView(SHOW_TYPE_KEY, showType);
            Bundle b = newUserState.query.getQueryBundle();
            for (String metaKey: b.keySet()) {
                String filterValue = b.getString(metaKey);
                addFilterToView(metaKey, filterValue);
            }
        }
        if (filterChips.getChildCount() > 0) {
            filterChips.setVisibility(View.VISIBLE);
        } else {
            filterChips.setVisibility(View.GONE);
        }
    }

    @Override
    public void onDestroyView() {
        Log.d(LC, "onDestroyView");
        fastScroller.onDestroy();
        fastScroller = null;
        fastScrollerBubble = null;
        super.onDestroyView();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (selectionTracker != null) {
            selectionTracker.onSaveInstanceState(outState);
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        final View rootView = inflater.inflate(R.layout.musiclibrary_fragment_layout, container,
                false);

        RecyclerView recyclerView = rootView.findViewById(R.id.musiclibrary_recyclerview);
        recyclerView.setHasFixedSize(true);
        LinearLayoutManager recViewLayoutManager =
                new LinearLayoutManager(this.getContext());
        recyclerView.setLayoutManager(recViewLayoutManager);
        recyclerView.getRecycledViewPool().setMaxRecycledViews(0, 50);

        fastScroller = rootView.findViewById(R.id.musiclibrary_fastscroller);
        fastScroller.setRecyclerView(recyclerView);
        fastScrollerBubble = rootView.findViewById(R.id.musiclibrary_fastscroller_bubble);
        fastScroller.setBubble(fastScrollerBubble);

        recyclerViewAdapter = new MusicLibraryAdapter(
                this,
                recViewLayoutManager,
                recyclerView,
                fastScrollerBubble
        );
        recyclerView.setAdapter(recyclerViewAdapter);

        selectionTracker = new SelectionTracker.Builder<>(
                MainActivity.SELECTION_ID_MUSICLIBRARY,
                recyclerView,
                new MusicLibraryKeyProvider(recyclerViewAdapter),
                new EntryIDDetailsLookup(recyclerView),
                StorageStrategy.createParcelableStorage(EntryID.class)
        ).withSelectionPredicate(
                SelectionPredicates.createSelectAnything()
        ).build();
        selectionTracker.addObserver(new SelectionTracker.SelectionObserver() {
            @Override
            public void onItemStateChanged(@NonNull Object key, boolean selected) {}

            @Override
            public void onSelectionRefresh() {}

            @Override
            public void onSelectionChanged() {
                if (selectionTracker.hasSelection() && actionMode == null) {
                    actionMode = getActivity().startActionMode(actionModeCallback);
                }
                if (!selectionTracker.hasSelection() && actionMode != null) {
                    actionMode.finish();
                }
                if (actionMode != null && selectionTracker.hasSelection()) {
                    actionMode.setTitle(selectionTracker.getSelection().size() + " entries.");
                }
            }

            @Override
            public void onSelectionRestored() {}
        });
        recyclerViewAdapter.setSelectionTracker(selectionTracker);
        if (savedInstanceState != null) {
            selectionTracker.onRestoreInstanceState(savedInstanceState);
        }

        searchInfo = rootView.findViewById(R.id.musiclibrary_search);
        searchText = rootView.findViewById(R.id.musiclibrary_search_query);

        entryTypeSelect = rootView.findViewById(R.id.musiclibrary_entry_type);
        entryTypeSelectSpinner = rootView.findViewById(R.id.musiclibrary_entry_type_spinner);
        filterNew = rootView.findViewById(R.id.musiclibrary_filter_new);
        filterNewType = rootView.findViewById(R.id.musiclibrary_filter_new_type);
        MetaStorage.getInstance(requireContext())
                .getMetaFields()
                .observe(getViewLifecycleOwner(), fields -> {
                    Collections.sort(fields, (f1, f2) -> {
                        if (f1.equals(f2)) {
                            return 0;
                        }
                        for (String field: Meta.FIELD_ORDER) {
                            if (f1.equals(field)) {
                                return -1;
                            }
                            if (f2.equals(field)) {
                                return 1;
                            }
                        }
                        return f1.compareTo(f2);
                    });
                    int initialSelectionPos = 0;
                    for (int i = 0; i < fields.size(); i++) {
                        if (fields.get(i).equals(MusicLibraryQuery.DEFAULT_SHOW_TYPE)) {
                            initialSelectionPos = i;
                        }
                    }
                    ArrayAdapter<String> fieldsAdapter = new ArrayAdapter<>(
                            requireContext(),
                            android.R.layout.simple_spinner_dropdown_item,
                            fields
                    );
                    entryTypeSelectSpinner.setAdapter(fieldsAdapter);
                    entryTypeSelectSpinner.setSelection(initialSelectionPos);
                    entryTypeSelectionPos = initialSelectionPos;
                    entryTypeSelectSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                        @Override
                        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                            if (entryTypeSelectionPos == position) {
                                return;
                            }
                            String field = fieldsAdapter.getItem(position);
                            Log.d(LC, "Showing entries of type: " + field);
                            Toast.makeText(
                                    requireContext(),
                                    "Showing entries of type: " + field,
                                    Toast.LENGTH_SHORT
                            ).show();
                            displayType(field);
                            entryTypeSelect.setVisibility(View.GONE);
                        }

                        @Override
                        public void onNothingSelected(AdapterView<?> parent) {}
                    });
                    filterNewType.setAdapter(fieldsAdapter);
                    EditText filterNewInput = rootView.findViewById(R.id.musiclibrary_filter_new_text);
                    filterNewInput.setOnEditorActionListener((v, actionId, event) -> {
                        if (actionId == EditorInfo.IME_ACTION_DONE) {
                            int pos = filterNewType.getSelectedItemPosition();
                            String field = fields.get(pos);
                            String filterString = filterNewInput.getText().toString();
                            Log.d(LC, "Applying filter: " + field + "(" + filterString + ")");
                            Toast.makeText(
                                    this.requireContext(),
                                    "Applying filter: " + field + "(" + filterString + ")",
                                    Toast.LENGTH_SHORT
                            ).show();
                            filter(field, filterString);
                            return true;
                        }
                        return false;
                    });
                });

        filterChips = rootView.findViewById(R.id.musiclibrary_filter_chips);

        filterEdit = rootView.findViewById(R.id.musiclibrary_filter_edit);
        filterEditType = rootView.findViewById(R.id.musiclibrary_filter_edit_type);
        filterEditInput = rootView.findViewById(R.id.musiclibrary_filter_edit_input);

        return rootView;
    }

    public boolean onBackPressed() {
        return model.popBackStack();
    }

    private void displayType(String displayType) {
        model.addBackStackHistory(recyclerViewAdapter.getCurrentPosition());
        model.displayType(displayType);
    }

    private void filter(String filterType, String filter) {
        model.addBackStackHistory(recyclerViewAdapter.getCurrentPosition());
        model.filter(filterType, filter);
    }

    void showOnly(String filterType, String filter) {
        model.addBackStackHistory(recyclerViewAdapter.getCurrentPosition());
        model.showOnly(filterType, filter);
        model.displayType(Meta.FIELD_SPECIAL_MEDIA_ID);
    }

    void browse(EntryID entryID) {
        model.addBackStackHistory(recyclerViewAdapter.getCurrentPosition());
        model.browse(entryID);
    }

    private void setEntryTypeSelectSpinnerSelection(String filterType) {
        for (int i = 0; i < entryTypeSelectSpinner.getCount(); i++) {
            if (filterType.equals(entryTypeSelectSpinner.getItemAtPosition(i))) {
                entryTypeSelectSpinner.setSelection(i);
                entryTypeSelectionPos = i;
                break;
            }
        }
    }

    private void clearFilterView() {
        filterChips.removeAllViews();
    }

    // TODO: Remove key SHOW_TYPE_KEY and its logic when showType is removed from the filter chips
    private static final String SHOW_TYPE_KEY = "dancingbunnies.musiclibraryfragment.show_type_key";
    private void addFilterToView(String metaKey, String filter) {
        String text = String.format("%s: %s", metaKey, filter);
        Chip newChip = new Chip(requireContext());
        newChip.setEllipsize(TextUtils.TruncateAt.END);
        newChip.setChipBackgroundColorResource(R.color.colorAccent);
        newChip.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
        newChip.setText(text);
        if (metaKey.equals(SHOW_TYPE_KEY)) {
            newChip.setOnClickListener(v -> {
                filterEdit.setVisibility(View.GONE);
                filterNew.setVisibility(View.GONE);
                if (entryTypeSelect.getVisibility() == View.VISIBLE) {
                    entryTypeSelect.setVisibility(View.GONE);
                } else {
                    entryTypeSelect.setVisibility(View.VISIBLE);
                    entryTypeSelectSpinner.performClick();
                }
            });
            filterChips.addView(newChip, 0);
            setEntryTypeSelectSpinnerSelection(filter);
        } else {
            newChip.setOnClickListener(v -> {
                entryTypeSelect.setVisibility(View.GONE);
                filterNew.setVisibility(View.GONE);
                String filterEditTypeText = metaKey + ':';
                if (chipHasSameFilter(text, filterEditType.getText().toString(),
                        filterEditInput.getText().toString())) {
                    filterEdit.setVisibility(filterEdit.getVisibility() == View.VISIBLE ?
                            View.GONE : View.VISIBLE
                    );
                } else {
                    filterEditInput.setText(filter);
                    filterEditInput.setOnEditorActionListener((v1, actionId, event) -> {
                        if (actionId == EditorInfo.IME_ACTION_DONE) {
                            String filterString = filterEditInput.getText().toString();
                            Log.d(LC, "Applying filter: " + metaKey + "(" + filterString + ")");
                            Toast.makeText(
                                    this.requireContext(),
                                    "Applying filter: " + metaKey + "(" + filterString + ")",
                                    Toast.LENGTH_SHORT
                            ).show();
                            filter(metaKey, filterString);
                            return true;
                        }
                        return false;
                    });
                    filterEditType.setText(filterEditTypeText);
                    filterEdit.setVisibility(View.VISIBLE);
                }
            });
            newChip.setOnCloseIconClickListener(v -> clearFilter(metaKey));
            newChip.setCloseIconVisible(true);
            int index = filterChips.getChildCount() <= 0 ? 0 : filterChips.getChildCount() - 1;
            filterChips.addView(newChip, index);
        }
    }

    private boolean chipHasSameFilter(String chipText, String filterType, String filter) {
        return chipText.equals(filterType + " " + filter);
    }

    private void clearFilter(String filterType) {
        model.addBackStackHistory(recyclerViewAdapter.getCurrentPosition());
        model.clearFilter(filterType);
    }

    private final ActionMode.Callback actionModeCallback = new ActionMode.Callback() {
        // Called when the action mode is created; startActionMode() was called
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            // Inflate a menu resource providing context menu items
            MenuInflater inflater = mode.getMenuInflater();
            inflater.inflate(R.menu.musiclibrary_actionmode_menu, menu);
            return true;
        }

        // Called each time the action mode is shown. Always called after onCreateActionMode, but
        // may be called multiple times if the mode is invalidated.
        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false; // Return false if nothing is done
        }

        // Called when the user selects a contextual menu item
        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            MutableSelection<EntryID> selection = new MutableSelection<>();
            selectionTracker.copySelection(selection);
            List<EntryID> selectionList = new LinkedList<>();
            selection.forEach(selectionList::add);
            switch (item.getItemId()) {
                case R.id.musiclibrary_actionmode_action_play_now:
                    queue(
                            selectionList,
                            0
                    ).thenAccept(success -> {
                        if (success) {
                            next();
                            play();
                        }
                    });
                    break;
                case R.id.musiclibrary_actionmode_action_queue:
                    queue(selectionList, AudioPlayerService.QUEUE_LAST);
                    break;
                case R.id.musiclibrary_actionmode_action_add_to_playlist:
                    addToPlaylist(selectionList);
                    break;
                default:
                    return false;
            }
            mode.finish();
            return true;
        }

        // Called when the user exits the action mode
        @Override
        public void onDestroyActionMode(ActionMode mode) {
            selectionTracker.clearSelection();
            actionMode = null;
        }
    };

    public void clearSelection() {
        if (selectionTracker != null) {
            selectionTracker.clearSelection();
        }
    }
}
