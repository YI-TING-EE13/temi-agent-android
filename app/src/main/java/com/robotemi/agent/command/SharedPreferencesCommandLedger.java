package com.robotemi.agent.command;

import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

/** Private, atomic SharedPreferences adapter for the command ledger. */
public final class SharedPreferencesCommandLedger implements CommandLedger.Persistence {
    private static final String KEY_SNAPSHOT = "snapshot_json";

    private final SharedPreferences preferences;
    private final Gson gson = new Gson();

    public SharedPreferencesCommandLedger(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    @Override
    public synchronized CommandLedger.Snapshot load() throws CommandLedger.StoreException {
        String payload = preferences.getString(KEY_SNAPSHOT, null);
        if (payload == null) return new CommandLedger.Snapshot();
        try {
            CommandLedger.Snapshot snapshot = gson.fromJson(payload, CommandLedger.Snapshot.class);
            if (snapshot == null || snapshot.records == null) {
                throw new CommandLedger.StoreException("command_store_invalid_shape");
            }
            return snapshot;
        } catch (JsonParseException | IllegalStateException e) {
            throw new CommandLedger.StoreException("command_store_read_failed", e);
        }
    }

    @Override
    public synchronized boolean save(CommandLedger.Snapshot snapshot)
            throws CommandLedger.StoreException {
        final String payload;
        try {
            payload = gson.toJson(snapshot);
        } catch (RuntimeException e) {
            throw new CommandLedger.StoreException("command_store_serialization_failed", e);
        }
        if (!preferences.edit().putString(KEY_SNAPSHOT, payload).commit()) {
            throw new CommandLedger.StoreException("command_store_commit_failed");
        }
        return true;
    }
}
