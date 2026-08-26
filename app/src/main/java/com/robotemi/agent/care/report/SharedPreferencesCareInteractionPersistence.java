package com.robotemi.agent.care.report;

import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

/** Atomic single-key store; report bodies are never persisted. */
public final class SharedPreferencesCareInteractionPersistence
        implements CareInteractionPersistence {
    private static final String KEY_SNAPSHOT = "care_interaction_outbox";
    private final SharedPreferences preferences;
    private final Gson gson = new Gson();

    public SharedPreferencesCareInteractionPersistence(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    @Override
    public synchronized Snapshot load() throws StoreException {
        String payload = preferences.getString(KEY_SNAPSHOT, null);
        if (payload == null) return new Snapshot();
        try {
            Snapshot snapshot = gson.fromJson(payload, Snapshot.class);
            if (snapshot == null || snapshot.outbox == null) {
                throw new StoreException("care_outbox_invalid_shape");
            }
            return snapshot;
        } catch (JsonParseException | IllegalStateException e) {
            throw new StoreException("care_outbox_read_failed", e);
        }
    }

    @Override
    public synchronized void save(Snapshot snapshot) throws StoreException {
        final String payload;
        try {
            payload = gson.toJson(snapshot);
        } catch (RuntimeException e) {
            throw new StoreException("care_outbox_serialization_failed", e);
        }
        if (!preferences.edit().putString(KEY_SNAPSHOT, payload).commit()) {
            throw new StoreException("care_outbox_commit_failed");
        }
    }
}
