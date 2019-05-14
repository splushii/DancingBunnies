package se.splushii.dancingbunnies.ui.nowplaying;

import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProviders;
import androidx.recyclerview.selection.StorageStrategy;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import se.splushii.dancingbunnies.MainActivity;
import se.splushii.dancingbunnies.R;
import se.splushii.dancingbunnies.audioplayer.AudioBrowserFragment;
import se.splushii.dancingbunnies.audioplayer.AudioPlayerService;
import se.splushii.dancingbunnies.musiclibrary.EntryID;
import se.splushii.dancingbunnies.musiclibrary.Meta;
import se.splushii.dancingbunnies.storage.AudioStorage;
import se.splushii.dancingbunnies.storage.MetaStorage;
import se.splushii.dancingbunnies.ui.MetaDialogFragment;
import se.splushii.dancingbunnies.ui.selection.RecyclerViewActionModeSelectionTracker;
import se.splushii.dancingbunnies.util.Util;

public class NowPlayingFragment extends AudioBrowserFragment {
    private static final String LC = Util.getLogContext(NowPlayingFragment.class);
    private static final long PROGRESS_UPDATE_INITIAL_INTERVAL = 100;
    private static final long PROGRESS_UPDATE_INTERNAL = 1000;

    private TextView nowPlayingTitle;
    private TextView nowPlayingArtist;
    private TextView nowPlayingAlbum;
    private ImageButton playPauseBtn;
    private boolean isPlaying = false;
    private SeekBar seekBar;
    private PlaybackStateCompat playbackState;
    private TextView positionText;
    private TextView durationText;
    private TextView mediaInfoText;
    private TextView bufferingText;
    private TextView sizeText;
    private Meta currentMeta = Meta.UNKNOWN_ENTRY;

    private final NowPlayingEntriesAdapter recViewAdapter;
    private RecyclerViewActionModeSelectionTracker<QueueEntry, NowPlayingEntriesAdapter, NowPlayingEntriesAdapter.ViewHolder> selectionTracker;

    public NowPlayingFragment() {
        recViewAdapter = new NowPlayingEntriesAdapter(this);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        final View rootView = inflater.inflate(R.layout.nowplaying_fragment_layout, container,
                false);

        RecyclerView recView = rootView.findViewById(R.id.nowplaying_recyclerview);
        LinearLayoutManager recViewLayoutManager = new LinearLayoutManager(this.getContext());
        recViewLayoutManager.setReverseLayout(true);
        recView.setLayoutManager(recViewLayoutManager);
        recView.setAdapter(recViewAdapter);
        selectionTracker = new RecyclerViewActionModeSelectionTracker<>(
                getActivity(),
                R.menu.nowplaying_queue_actionmode_menu,
                MainActivity.SELECTION_ID_NOWPLAYING,
                recView,
                recViewAdapter,
                StorageStrategy.createParcelableStorage(QueueEntry.class),
                savedInstanceState
        );

        View nowPlayingInfo = rootView.findViewById(R.id.nowplaying_info);
        nowPlayingInfo.setOnClickListener(v -> MetaDialogFragment.showMeta(this, currentMeta));
        nowPlayingTitle = rootView.findViewById(R.id.nowplaying_title);
        nowPlayingArtist = rootView.findViewById(R.id.nowplaying_artist);
        nowPlayingAlbum = rootView.findViewById(R.id.nowplaying_album);
        ImageButton previousBtn = rootView.findViewById(R.id.nowplaying_previous);
        previousBtn.setOnClickListener(view -> previous());
        isPlaying = false;
        playPauseBtn = rootView.findViewById(R.id.nowplaying_play_pause);
        playPauseBtn.setOnClickListener(view -> {
            if (isPlaying) {
                pause();
                stopProgressUpdate();
            } else {
                play();
                scheduleProgressUpdate();
            }
        });
        ImageButton nextBtn = rootView.findViewById(R.id.nowplaying_next);
        nextBtn.setOnClickListener(view -> next());
        seekBar = rootView.findViewById(R.id.nowplaying_seekbar);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    positionText.setText(Util.getDurationString(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                Log.d(LC, "seekbar onstart");
                stopProgressUpdate();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                Log.d(LC, "seekbar onstop: " + seekBar.getProgress());
                seekTo(seekBar.getProgress());
            }
        });
        positionText = rootView.findViewById(R.id.nowplaying_position);
        durationText = rootView.findViewById(R.id.nowplaying_duration);
        mediaInfoText = rootView.findViewById(R.id.nowplaying_media_info);
        bufferingText = rootView.findViewById(R.id.nowplaying_buffering);
        sizeText = rootView.findViewById(R.id.nowplaying_size);
        return rootView;
    }

    @Override
    public void onStart() {
        super.onStart();
        refreshView();
        Log.d(LC, "onStart");
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        Log.d(LC, "onActivityCreated");
        super.onActivityCreated(savedInstanceState);
        NowPlayingFragmentModel model = ViewModelProviders.of(requireActivity()).get(NowPlayingFragmentModel.class);
        model.getFetchState(requireContext()).observe(getViewLifecycleOwner(), audioDataFetchStates -> {
            boolean showSize = false;
            AudioStorage.AudioDataFetchState state = audioDataFetchStates.get(currentMeta.entryID);
            if (state != null) {
                sizeText.setText(state.getStatusMsg());
                showSize = true;
            }
            if (!showSize && currentMeta.has(Meta.FIELD_FILE_SIZE)) {
                String formattedFileSize = getFormattedFileSize(currentMeta);
                if (formattedFileSize != null) {
                    sizeText.setText(formattedFileSize);
                    showSize = true;
                }
            }
            sizeText.setVisibility(showSize ? View.VISIBLE : View.INVISIBLE);
        });
        recViewAdapter.setModel(model);
    }

    private String getFormattedFileSize(Meta currentMeta) {
        long size = currentMeta.getFirstLong(Meta.FIELD_FILE_SIZE, -1);
        return size < 0 ?
                null : String.format(Locale.getDefault(),"%d MB", size / 1_000_000L);
    }

    @Override
    public void onStop() {
        super.onStop();
        Log.d(LC, "onStop");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopProgressUpdate();
        executor.shutdown();
    }

    @Override
    protected void onSessionReady() {
        super.onSessionReady();
        refreshView();
    }

    private void refreshView() {
        if (mediaController == null || !mediaController.isSessionReady()) {
            Log.w(LC, "Media session not ready");
            return;
        }
        // TODO: Show current playlist
        // TODO: Show upcoming tracks from playlist
        // TODO: Implement playbackhistory in AudioPlayerService/PlaybackController, then in UI.
        //getPlaybackHistory().thenAccept(opt -> opt.ifPresent(recViewAdapter::setPlaybackHistory));
        List<QueueEntry> queueEntries = getQueue();
        Log.d(LC, "refreshView: queue(" + queueEntries.size() + ")");
        recViewAdapter.setQueueEntries(queueEntries);
    }

    @Override
    protected void onPlaybackStateChanged(PlaybackStateCompat state) {
        updatePlaybackState(state);
    }

    private void updatePlaybackState(PlaybackStateCompat state) {
        Drawable playDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.quantum_ic_play_arrow_grey600_36);
        playDrawable.setTint(ContextCompat.getColor(requireContext(), R.color.bluegrey900));
        Drawable pauseDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.quantum_ic_pause_grey600_36);
        pauseDrawable.setTint(ContextCompat.getColor(requireContext(), R.color.bluegrey900));

        playbackState = state;
        switch (state.getState()) { // TODO: Refactor to avoid repetition
            case PlaybackStateCompat.STATE_PLAYING:
            case PlaybackStateCompat.STATE_FAST_FORWARDING:
            case PlaybackStateCompat.STATE_REWINDING:
                Log.d(LC, "state: playing");
                seekBar.setEnabled(true);
                playPauseBtn.setImageDrawable(pauseDrawable);
                bufferingText.setVisibility(View.INVISIBLE);
                isPlaying = true;
                scheduleProgressUpdate();
                break;
            case PlaybackStateCompat.STATE_SKIPPING_TO_NEXT:
            case PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS:
            case PlaybackStateCompat.STATE_SKIPPING_TO_QUEUE_ITEM:
            case PlaybackStateCompat.STATE_PAUSED:
                seekBar.setEnabled(true);
                playPauseBtn.setImageDrawable(playDrawable);
                bufferingText.setVisibility(View.INVISIBLE);
                isPlaying = false;
                updateProgress();
                stopProgressUpdate();
                break;
            case PlaybackStateCompat.STATE_CONNECTING:
            case PlaybackStateCompat.STATE_BUFFERING:
                seekBar.setEnabled(true);
                playPauseBtn.setImageDrawable(playDrawable);
                bufferingText.setVisibility(View.VISIBLE);
                isPlaying = false;
                updateProgress();
                stopProgressUpdate();
                break;
            default:
                Log.w(LC, "Unknown playbackstate.\n"
                        + "contents: " + state.describeContents() + " actions: " + state.getActions()
                        + " queue id: " + state.getActiveQueueItemId() + " state: " + state.getState());
            case PlaybackStateCompat.STATE_NONE:
            case PlaybackStateCompat.STATE_STOPPED:
            case PlaybackStateCompat.STATE_ERROR:
                seekBar.setEnabled(false);
                playPauseBtn.setImageDrawable(playDrawable);
                bufferingText.setVisibility(View.INVISIBLE);
                isPlaying = false;
                stopProgressUpdate();
                break;
        }
    }

    @Override
    protected void onMetadataChanged(EntryID entryID) {
        MetaStorage.getInstance(requireContext()).getMeta(entryID)
                .thenAcceptAsync(meta -> {
                    currentMeta = meta;
                    updateMeta(meta);
                    updateProgress();
                }, Util.getMainThreadExecutor());
    }

    private void updateMeta(Meta meta) {
        updateDescription(meta);
        updateMediaInfo(meta);
        updateDuration(meta);
    }

    private void updateDescription(Meta metadata) {
        String title = metadata.getAsString(Meta.FIELD_TITLE);
        String album = metadata.getAsString(Meta.FIELD_ALBUM);
        String artist = metadata.getAsString(Meta.FIELD_ARTIST);
        if (title == null || title.isEmpty()) {
            title = "Unknown title";
        }
        if (album == null || album.isEmpty()) {
            album = "Unknown album";
        } else {
            String year = metadata.getAsString(Meta.FIELD_YEAR);
            if (!year.isEmpty()) {
                album = String.format(Locale.getDefault(), "%s - %s", album, year);
            }
        }
        if (artist == null || artist.isEmpty()) {
            artist = "Unknown artist";
        }
        nowPlayingTitle.setText(title);
        nowPlayingAlbum.setText(album);
        nowPlayingArtist.setText(artist);
    }

    private void updateMediaInfo(Meta metadata) {
        String formattedFileSize = getFormattedFileSize(metadata);
        if (formattedFileSize != null) {
            sizeText.setText(formattedFileSize);
            sizeText.setVisibility(View.VISIBLE);
        } else {
            sizeText.setVisibility(View.INVISIBLE);
        }
        String contentType = metadata.getFirstString(Meta.FIELD_CONTENT_TYPE);
        String suffix = metadata.getFirstString(Meta.FIELD_FILE_SUFFIX);
        long bitrate = metadata.getFirstLong(Meta.FIELD_BITRATE, -1);
        ArrayList<String> info = new ArrayList<>();
        if (!contentType.isEmpty()) {
            info.add(contentType);
        }
        if (!suffix.isEmpty()) {
            info.add(suffix);
        }
        if (bitrate >= 0) {
            info.add(String.format(Locale.getDefault(), "%dkbps", bitrate));
        }
        mediaInfoText.setText(String.join(" ", info));
    }

    @Override
    protected void onMediaBrowserConnected() {
        PlaybackStateCompat state = mediaController.getPlaybackState();
        updatePlaybackState(state);
        MediaMetadataCompat mediaMetadataCompat = mediaController.getMetadata();
        if (mediaMetadataCompat != null) {
            MetaStorage.getInstance(requireContext()).getMeta(EntryID.from(mediaMetadataCompat))
                    .thenAcceptAsync(meta -> {
                        currentMeta = meta;
                        updateMeta(meta);
                    }, Util.getMainThreadExecutor());
        }
        updateProgress();
        if (playbackState.getState() == PlaybackStateCompat.STATE_PLAYING) {
            scheduleProgressUpdate();
        }
    }

    private void updateProgress() {
        long pos = playbackState.getPosition();
        if (playbackState.getState() == PlaybackStateCompat.STATE_PLAYING) {
            long elapsed = SystemClock.elapsedRealtime() -
                    playbackState.getLastPositionUpdateTime();
            pos += elapsed * playbackState.getPlaybackSpeed();
        }
        if (pos > seekBar.getMax()) {
            seekBar.setProgress(seekBar.getMax());
            positionText.setText(Util.getDurationString(seekBar.getMax()));
            stopProgressUpdate();
        }
        seekBar.setProgress((int) pos);
        positionText.setText(Util.getDurationString(pos));
    }

    private void updateDuration(Meta metadata) {
        int duration = (int) metadata.getFirstLong(Meta.FIELD_DURATION, 0);
        seekBar.setMax(duration);
        durationText.setText(Util.getDurationString(duration));
    }

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> scheduledFuture;
    private final Handler handler = new Handler();
    private final Runnable updateProgressTask = this::updateProgress;

    private void scheduleProgressUpdate() {
        stopProgressUpdate();
        if (!executor.isShutdown()) {
            Log.d(LC, "schedule progress update");
            scheduledFuture = executor.scheduleAtFixedRate(
                    () -> handler.post(updateProgressTask),
                    PROGRESS_UPDATE_INITIAL_INTERVAL,
                    PROGRESS_UPDATE_INTERNAL,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    private void stopProgressUpdate() {
        if (scheduledFuture != null) {
            Log.d(LC, "stop progress update");
            scheduledFuture.cancel(false);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        selectionTracker.onSaveInstanceState(outState);
    }

    @Override
    protected void onQueueChanged(List<MediaSessionCompat.QueueItem> queue) {
        refreshView();
    }

    @Override
    protected void onSessionEvent(String event, Bundle extras) {
        switch (event) {
            case AudioPlayerService.SESSION_EVENT_PLAYLIST_POSITION_CHANGED:
            case AudioPlayerService.SESSION_EVENT_PLAYLIST_CHANGED:
                refreshView();
                break;
        }
    }

    public void clearSelection() {
        if (selectionTracker != null) {
            selectionTracker.clearSelection();
        }
    }
}