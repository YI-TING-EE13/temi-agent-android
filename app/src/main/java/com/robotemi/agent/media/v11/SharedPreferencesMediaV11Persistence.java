package com.robotemi.agent.media.v11;

import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

/** Atomic single-key SharedPreferences store for the bounded Media v1.1 snapshot. */
public final class SharedPreferencesMediaV11Persistence implements MediaV11Persistence {
    private static final String KEY_SNAPSHOT = "snapshot_json";

    private final SharedPreferences preferences;
    private final Gson gson = new Gson();

    public SharedPreferencesMediaV11Persistence(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    @Override
    public synchronized Snapshot load() throws StoreException {
        String payload = preferences.getString(KEY_SNAPSHOT, null);
        if (payload == null) {
            return new Snapshot();
        }
        try {
            Snapshot snapshot = gson.fromJson(payload, Snapshot.class);
            if (snapshot == null || snapshot.commands == null || snapshot.outbox == null) {
                throw new StoreException("media_store_invalid_shape");
            }
            return snapshot;
        } catch (JsonParseException | IllegalStateException e) {
            throw new StoreException("media_store_read_failed", e);
        }
    }

    @Override
    public synchronized void save(Snapshot snapshot) throws StoreException {
        final String payload;
        try {
            payload = gson.toJson(snapshot);
        } catch (RuntimeException e) {
            throw new StoreException("media_store_serialization_failed", e);
        }
        if (!preferences.edit().putString(KEY_SNAPSHOT, payload).commit()) {
            throw new StoreException("media_store_commit_failed");
        }
    }
}
