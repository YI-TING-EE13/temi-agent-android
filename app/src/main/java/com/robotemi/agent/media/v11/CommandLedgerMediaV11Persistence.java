package com.robotemi.agent.media.v11;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.robotemi.agent.command.CommandLedger;

/**
 * Media policy storage backed by the same physical CommandLedger snapshot.
 * It is an adapter, not a second command ledger or a second SharedPreferences file.
 */
public final class CommandLedgerMediaV11Persistence implements MediaV11Persistence {
    private final CommandLedger.Persistence persistence;
    private final Gson gson = new Gson();

    public CommandLedgerMediaV11Persistence(CommandLedger.Persistence persistence) {
        if (persistence == null) throw new IllegalArgumentException("ledger_persistence_required");
        this.persistence = persistence;
    }

    @Override
    public synchronized Snapshot load() throws StoreException {
        final CommandLedger.Snapshot ledgerSnapshot;
        try {
            ledgerSnapshot = persistence.load();
        } catch (CommandLedger.StoreException e) {
            throw new StoreException("command_ledger_read_failed", e);
        }
        if (ledgerSnapshot.mediaV11SnapshotJson == null) return new Snapshot();
        try {
            Snapshot snapshot = gson.fromJson(ledgerSnapshot.mediaV11SnapshotJson, Snapshot.class);
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
        final String encoded;
        try {
            encoded = gson.toJson(snapshot);
        } catch (RuntimeException e) {
            throw new StoreException("media_store_serialization_failed", e);
        }
        final CommandLedger.Snapshot ledgerSnapshot;
        try {
            ledgerSnapshot = persistence.load();
            ledgerSnapshot.mediaV11SnapshotJson = encoded;
            if (!persistence.save(ledgerSnapshot)) {
                throw new StoreException("command_ledger_commit_failed");
            }
        } catch (CommandLedger.StoreException e) {
            throw new StoreException("command_ledger_commit_failed", e);
        }
    }
}
