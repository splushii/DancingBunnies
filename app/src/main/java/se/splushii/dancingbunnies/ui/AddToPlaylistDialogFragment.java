package se.splushii.dancingbunnies.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProviders;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import se.splushii.dancingbunnies.MainActivity;
import se.splushii.dancingbunnies.R;
import se.splushii.dancingbunnies.musiclibrary.EntryID;
import se.splushii.dancingbunnies.musiclibrary.MusicLibraryService;
import se.splushii.dancingbunnies.musiclibrary.PlaylistID;
import se.splushii.dancingbunnies.storage.MetaStorage;
import se.splushii.dancingbunnies.storage.PlaylistStorage;
import se.splushii.dancingbunnies.storage.db.Playlist;
import se.splushii.dancingbunnies.ui.playlist.PlaylistAdapter;
import se.splushii.dancingbunnies.ui.playlist.PlaylistFragmentModel;
import se.splushii.dancingbunnies.util.Util;

public class AddToPlaylistDialogFragment extends DialogFragment {
    private static final String LC = Util.getLogContext(AddToPlaylistDialogFragment.class);

    private static final String TAG = "dancingbunnies.splushii.se.fragment_tag.add_to_playlist_dialog";
    private static final String BUNDLE_KEY_ENTRY_IDS = "dancingbunnies.bundle.key.addtoplaylistdialog.entryids";
    private static final String BUNDLE_KEY_QUERY = "dancingbunnies.bundle.key.addtoplaylistdialog.query";

    private ArrayList<EntryID> entryIDs;
    private Bundle query;
    private PlaylistAdapter recViewAdapter;
    private View addToNewPlaylistView;

    public static void showDialog(Fragment fragment,
                                  ArrayList<EntryID> entryIDs,
                                  Bundle query) {
        if (entryIDs == null || entryIDs.isEmpty()) {
            return;
        }
        FragmentTransaction ft = fragment.getFragmentManager().beginTransaction();
        Fragment prev = fragment.getFragmentManager().findFragmentByTag(AddToPlaylistDialogFragment.TAG);
        if (prev != null) {
            ft.remove(prev);
        }
        ft.addToBackStack(null);
        DialogFragment dialogFragment = new AddToPlaylistDialogFragment();
        dialogFragment.setTargetFragment(fragment, MainActivity.REQUEST_CODE_ADD_TO_PLAYLIST_DIALOG);
        Bundle args = new Bundle();
        args.putParcelableArrayList(BUNDLE_KEY_ENTRY_IDS, entryIDs);
        args.putBundle(BUNDLE_KEY_QUERY, query);
        dialogFragment.setArguments(args);
        dialogFragment.show(ft, AddToPlaylistDialogFragment.TAG);
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        PlaylistFragmentModel model = ViewModelProviders.of(requireActivity()).get(PlaylistFragmentModel.class);
        recViewAdapter.setModel(model, playlists -> {
            List<Playlist> applicablePlaylists = new ArrayList<>();
            for (Playlist playlist: playlists) {
                boolean applicable = true;
                for (EntryID entryID: entryIDs) {
                    if (!canBeAdded(entryID, playlist)) {
                        applicable = false;
                        break;
                    }
                }
                if (applicable) {
                    applicablePlaylists.add(playlist);
                }
            }
            return applicablePlaylists;
        });
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        Bundle args = getArguments();
        ArrayList<EntryID> entryIDs = args.getParcelableArrayList(BUNDLE_KEY_ENTRY_IDS);
        Bundle query = args.getBundle(BUNDLE_KEY_QUERY);
        this.entryIDs = entryIDs == null ? new ArrayList<>() : entryIDs;
        this.query = query;
        super.onCreate(savedInstanceState);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.add_to_playlist_dialog_fragment_layout, container, false);
        RecyclerView recyclerView = rootView.findViewById(R.id.add_to_playlist_dialog_recyclerview);
        LinearLayoutManager recViewLayoutManager = new LinearLayoutManager(requireContext());
        recyclerView.setLayoutManager(recViewLayoutManager);
        recViewAdapter = new PlaylistAdapter(this);
        recViewAdapter.setOnItemClickListener(playlist ->
                MetaStorage.getInstance(requireContext())
                        .getSongEntriesOnce(entryIDs, query)
                        .thenCompose(songEntryIDs ->
                                PlaylistStorage.getInstance(requireContext())
                                        .addToPlaylist(new PlaylistID(playlist), songEntryIDs))
                        .thenRun(this::dismiss)
        );
        recyclerView.setAdapter(recViewAdapter);
        addToNewPlaylistView = rootView.findViewById(R.id.add_to_playlist_dialog_new);
        addToNewPlaylistView.setOnClickListener(v ->
                AddToNewPlaylistDialogFragment.showDialog(this, entryIDs, query)
        );
        return rootView;
    }

    private boolean canBeAdded(EntryID entryID, Playlist playlist) {
        if (playlist.type != PlaylistID.TYPE_STUPID) {
            return false;
        }
        if (MusicLibraryService.API_ID_DANCINGBUNNIES.equals(playlist.api)) {
            return true;
        }
        return playlist.api.equals(entryID.src) &&
                MusicLibraryService.checkAPISupport(
                        playlist.api,
                        MusicLibraryService.PLAYLIST_ENTRY_ADD);
    }
}
