package com.robotemi.agent.media.v11;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persistence boundary for durable command idempotency and result delivery. */
public interface MediaV11Persistence {
    Snapshot load() throws StoreException;
    void save(Snapshot snapshot) throws StoreException;

    final class Snapshot {
        public LinkedHashMap<String, CommandRecord> commands = new LinkedHashMap<>();
        public List<OutboxRecord> outbox = new ArrayList<>();
        public ActiveSession activeSession;

        public Snapshot copy() {
            Snapshot copy = new Snapshot();
            for (Map.Entry<String, CommandRecord> entry : commands.entrySet()) {
                copy.commands.put(entry.getKey(), entry.getValue().copy());
            }
            for (OutboxRecord record : outbox) {
                copy.outbox.add(record.copy());
            }
            copy.activeSession = activeSession == null ? null : activeSession.copy();
            return copy;
        }
    }

    final class CommandRecord {
        public String commandJson;
        public String payloadDigest;
        public String action;
        public String sessionId;
        public String endpointFingerprint;
        public boolean terminal;
        public String latestResultJson;
        public long updatedAtMs;

        CommandRecord copy() {
            CommandRecord copy = new CommandRecord();
            copy.commandJson = commandJson;
            copy.payloadDigest = payloadDigest;
            copy.action = action;
            copy.sessionId = sessionId;
            copy.endpointFingerprint = endpointFingerprint;
            copy.terminal = terminal;
            copy.latestResultJson = latestResultJson;
            copy.updatedAtMs = updatedAtMs;
            return copy;
        }
    }

    final class ActiveSession {
        public String playCommandJson;
        public String sessionId;
        public String playbackState;

        ActiveSession copy() {
            ActiveSession copy = new ActiveSession();
            copy.playCommandJson = playCommandJson;
            copy.sessionId = sessionId;
            copy.playbackState = playbackState;
            return copy;
        }
    }

    final class OutboxRecord {
        public String id;
        public String payload;

        OutboxRecord copy() {
            OutboxRecord copy = new OutboxRecord();
            copy.id = id;
            copy.payload = payload;
            return copy;
        }
    }

    final class StoreException extends Exception {
        public StoreException(String message) {
            super(message);
        }

        public StoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
