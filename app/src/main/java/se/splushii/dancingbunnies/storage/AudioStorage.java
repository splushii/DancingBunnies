package se.splushii.dancingbunnies.storage;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import se.splushii.dancingbunnies.backend.AudioDataDownloadHandler;
import se.splushii.dancingbunnies.musiclibrary.AudioDataSource;
import se.splushii.dancingbunnies.musiclibrary.EntryID;

public class AudioStorage {
    private final HashMap<EntryID, AudioDataSource> audioMap;
    private final HashMap<EntryID, List<AudioDataDownloadHandler>> handlerMap;

    public AudioStorage() {
        audioMap = new HashMap<>();
        handlerMap = new HashMap<>();
    }

    public synchronized AudioDataSource get(EntryID entryID) {
        return audioMap.get(entryID);
    }

    public synchronized AudioDataSource put(EntryID entryID, AudioDataSource audioDataSource) {
        return audioMap.put(entryID, audioDataSource);
    }

    public void download(EntryID entryID, AudioDataDownloadHandler handler) {
        if (!audioMap.containsKey(entryID)) {
            handler.onFailure("EntryID to download not found in AudioStorage");
            return;
        }
        AudioDataSource audioDataSource = audioMap.get(entryID);
        synchronized (handlerMap) {
            List<AudioDataDownloadHandler> handlers = handlerMap.get(entryID);
            if (handlers == null) {
                handlers = new LinkedList<>();
                handlerMap.put(entryID, handlers);
                // TODO: Change to audioDataSource.buffer, and use a callback to play when buffered enough
                audioDataSource.download(new AudioDataSource.Handler() {
                    @Override
                    public void onStart() {
                        onDownloadStartEvent(entryID);
                    }

                    @Override
                    public void onFailure(String message) {
                        onDownloadFailureEvent(entryID, message);
                    }

                    @Override
                    public void onSuccess() {
                        onDownloadSuccessEvent(entryID);
                    }

                    @Override
                    public void onProgress(long i, long max) {
                        onDownloadProgressEvent(entryID, i, max);
                    }
                });
            }
            handlers.add(handler);
        }
    }

    private void onDownloadStartEvent(EntryID entryID) {
        synchronized (handlerMap) {
            List<AudioDataDownloadHandler> handlers = handlerMap.get(entryID);
            if (handlers != null) {
                handlers.forEach(AudioDataDownloadHandler::onStart);
            }
        }
    }

    private void onDownloadProgressEvent(EntryID entryID, long i, long max) {
        synchronized (handlerMap) {
            List<AudioDataDownloadHandler> handlers = handlerMap.get(entryID);
            if (handlers != null) {
                handlers.forEach(handler -> handler.onProgress(i, max));
            }
        }
    }

    private void onDownloadSuccessEvent(EntryID entryID) {
        synchronized (handlerMap) {
            List<AudioDataDownloadHandler> handlers = handlerMap.get(entryID);
            if (handlers != null) {
                AudioDataSource audioDataSource = audioMap.get(entryID);
                if (audioDataSource == null) {
                    handlers.forEach(handler -> handler.onFailure(
                            "AudioDataSource not present in AudioStorage."
                    ));
                } else {
                    handlers.forEach(handler -> handler.onSuccess(audioDataSource));
                }
            }
            handlerMap.remove(entryID);
        }
    }

    private void onDownloadFailureEvent(EntryID entryID, String message) {
        synchronized (handlerMap) {
            List<AudioDataDownloadHandler> handlers = handlerMap.get(entryID);
            if (handlers != null) {
                handlers.forEach(handler -> handler.onFailure(message));
            }
            handlerMap.remove(entryID);
        }
    }
}
