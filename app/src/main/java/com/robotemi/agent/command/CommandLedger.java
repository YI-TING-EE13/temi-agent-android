package com.robotemi.agent.command;

import com.robotemi.agent.command.CanonicalCommandValidator.CanonicalAction;
import com.robotemi.agent.command.CanonicalCommandValidator.CanonicalCommand;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Durable command idempotency and result-delivery ledger.
 *
 * <p>The ledger stores only bounded action summaries, correlation fields, a payload digest,
 * and the cached result. It deliberately does not persist speech or resident text.</p>
 */
public final class CommandLedger {
    public static final int MAX_RECORDS = 1024;
    public static final long DEFAULT_MAX_PENDING_AGE_MS = 5 * 60 * 1000L;

    public enum State {
        RECEIVED,
        EXECUTING,
        COMPLETED,
        FAILED,
        RESULT_PENDING
    }

    public enum ResultState {
        NONE,
        PENDING,
        DELIVERED
    }

    public enum AcceptState {
        FIRST_DELIVERY,
        DUPLICATE_PENDING,
        DUPLICATE_CACHED_RESULT,
        PAYLOAD_CONFLICT,
        CAPACITY_REJECTED,
        STORE_ERROR
    }

    public enum RecoveryState {
        CACHED_RESULT,
        SAFE_RETRY,
        EXECUTION_UNKNOWN,
        UNSAFE_RETRY,
        EXPIRED
    }

    public interface Persistence {
        Snapshot load() throws StoreException;
        boolean save(Snapshot snapshot) throws StoreException;
    }

    public static final class Snapshot {
        public LinkedHashMap<String, Record> records = new LinkedHashMap<>();
        /** Serialized Media v1.1 policy snapshot, kept in the same ledger record. */
        public String mediaV11SnapshotJson;
        /** Monotonic migration marker for the legacy canonical_media_v11 preference. */
        public int mediaV11MigrationVersion;

        public Snapshot copy() {
            Snapshot copy = new Snapshot();
            copy.mediaV11SnapshotJson = mediaV11SnapshotJson;
            copy.mediaV11MigrationVersion = mediaV11MigrationVersion;
            if (records != null) {
                for (Map.Entry<String, Record> entry : records.entrySet()) {
                    copy.records.put(entry.getKey(), entry.getValue().copy());
                }
            }
            return copy;
        }
    }

    public static final class Record {
        public String commandId;
        public String requestId;
        public String robotId;
        public String action;
        public List<ActionSummary> actions = new ArrayList<>();
        public String payloadDigest;
        public long receivedAtMs;
        public long updatedAtMs;
        public State state;
        public State terminalState;
        public ResultState resultState = ResultState.NONE;
        public String resultPayload;
        public String videoId;
        public String mediaState;
        public String mediaOperation;
        public String mediaCommandJson;
        public String mediaSessionId;
        public String mediaLeaseId;
        public long mediaBindingGeneration;
        public long mediaDeadlineAtMs;

        public Record copy() {
            Record copy = new Record();
            copy.commandId = commandId;
            copy.requestId = requestId;
            copy.robotId = robotId;
            copy.action = action;
            copy.actions = new ArrayList<>();
            if (actions != null) {
                for (ActionSummary summary : actions) {
                    copy.actions.add(summary.copy());
                }
            }
            copy.payloadDigest = payloadDigest;
            copy.receivedAtMs = receivedAtMs;
            copy.updatedAtMs = updatedAtMs;
            copy.state = state;
            copy.terminalState = terminalState;
            copy.resultState = resultState;
            copy.resultPayload = resultPayload;
            copy.videoId = videoId;
            copy.mediaState = mediaState;
            copy.mediaOperation = mediaOperation;
            copy.mediaCommandJson = mediaCommandJson;
            copy.mediaSessionId = mediaSessionId;
            copy.mediaLeaseId = mediaLeaseId;
            copy.mediaBindingGeneration = mediaBindingGeneration;
            copy.mediaDeadlineAtMs = mediaDeadlineAtMs;
            return copy;
        }
    }

    public static final class ActionSummary {
        public String actionId;
        public String type;

        public ActionSummary() {}

        private ActionSummary(String actionId, String type) {
            this.actionId = actionId;
            this.type = type;
        }

        private ActionSummary copy() {
            return new ActionSummary(actionId, type);
        }
    }

    public static final class AcceptResult {
        private final AcceptState state;
        private final Record record;

        private AcceptResult(AcceptState state, Record record) {
            this.state = state;
            this.record = record;
        }

        public AcceptState state() { return state; }
        public Record record() { return record == null ? null : record.copy(); }
    }

    public static final class RecoveryItem {
        private final RecoveryState state;
        private final Record record;

        private RecoveryItem(RecoveryState state, Record record) {
            this.state = state;
            this.record = record;
        }

        public RecoveryState state() { return state; }
        public Record record() { return record.copy(); }
    }

    public static final class StoreException extends Exception {
        public StoreException(String message) { super(message); }
        public StoreException(String message, Throwable cause) { super(message, cause); }
    }

    private final Persistence persistence;
    private final long maxPendingAgeMs;

    public CommandLedger(Persistence persistence) {
        this(persistence, DEFAULT_MAX_PENDING_AGE_MS);
    }

    public CommandLedger(Persistence persistence, long maxPendingAgeMs) {
        if (persistence == null || maxPendingAgeMs < 1) {
            throw new IllegalArgumentException("invalid_command_ledger_configuration");
        }
        this.persistence = persistence;
        this.maxPendingAgeMs = maxPendingAgeMs;
    }

    public synchronized AcceptResult accept(
            CanonicalCommand command, String rawPayload, long receivedAtMs) {
        try {
            Snapshot snapshot = normalized(persistence.load());
            String digest = sha256(rawPayload);
            Record existing = snapshot.records.get(command.getCommandId());
            if (existing != null) {
                if (!digest.equals(existing.payloadDigest)) {
                    return new AcceptResult(AcceptState.PAYLOAD_CONFLICT, existing.copy());
                }
                if (existing.state == State.RESULT_PENDING
                        || existing.resultState == ResultState.DELIVERED
                        || existing.state == State.COMPLETED
                        || existing.state == State.FAILED) {
                    return new AcceptResult(
                            AcceptState.DUPLICATE_CACHED_RESULT, existing.copy());
                }
                return new AcceptResult(AcceptState.DUPLICATE_PENDING, existing.copy());
            }
            if (snapshot.records.size() >= MAX_RECORDS) {
                return new AcceptResult(AcceptState.CAPACITY_REJECTED, null);
            }
            Record record = new Record();
            record.commandId = command.getCommandId();
            record.requestId = command.getEventId();
            record.robotId = command.getRobotId();
            record.actions = summaries(command.getActions());
            record.action = record.actions.size() == 1
                    ? record.actions.get(0).type : "multiple";
            record.payloadDigest = digest;
            record.receivedAtMs = receivedAtMs;
            record.updatedAtMs = receivedAtMs;
            record.state = State.RECEIVED;
            record.resultState = ResultState.NONE;
            snapshot.records.put(record.commandId, record);
            if (!persistence.save(snapshot)) {
                return new AcceptResult(AcceptState.STORE_ERROR, null);
            }
            return new AcceptResult(AcceptState.FIRST_DELIVERY, record.copy());
        } catch (StoreException | RuntimeException e) {
            return new AcceptResult(AcceptState.STORE_ERROR, null);
        }
    }

    /**
     * Accepts a Media v1.1 command without coupling the generic ledger to the media parser.
     * The media runtime supplies the already-validated bounded action summary.
     */
    public synchronized AcceptResult acceptMedia(
            String commandId,
            String requestId,
            String robotId,
            String action,
            String videoId,
            String rawPayload,
            long receivedAtMs) {
        try {
            Snapshot snapshot = normalized(persistence.load());
            String digest = sha256(rawPayload);
            Record existing = snapshot.records.get(commandId);
            if (existing != null) {
                if (!digest.equals(existing.payloadDigest)) {
                    return new AcceptResult(AcceptState.PAYLOAD_CONFLICT, existing.copy());
                }
                if (existing.state == State.RESULT_PENDING
                        || existing.resultState == ResultState.DELIVERED
                        || existing.state == State.COMPLETED
                        || existing.state == State.FAILED) {
                    return new AcceptResult(
                            AcceptState.DUPLICATE_CACHED_RESULT, existing.copy());
                }
                return new AcceptResult(AcceptState.DUPLICATE_PENDING, existing.copy());
            }
            if (snapshot.records.size() >= MAX_RECORDS) {
                return new AcceptResult(AcceptState.CAPACITY_REJECTED, null);
            }
            Record record = new Record();
            record.commandId = commandId;
            record.requestId = requestId;
            record.robotId = robotId;
            record.action = action;
            record.videoId = videoId;
            record.mediaCommandJson = rawPayload;
            record.payloadDigest = digest;
            record.receivedAtMs = receivedAtMs;
            record.updatedAtMs = receivedAtMs;
            record.state = State.RECEIVED;
            record.mediaState = "RECEIVED";
            record.mediaOperation = action;
            record.resultState = ResultState.NONE;
            snapshot.records.put(record.commandId, record);
            if (!persistence.save(snapshot)) {
                return new AcceptResult(AcceptState.STORE_ERROR, null);
            }
            return new AcceptResult(AcceptState.FIRST_DELIVERY, record.copy());
        } catch (StoreException | RuntimeException e) {
            return new AcceptResult(AcceptState.STORE_ERROR, null);
        }
    }

    public synchronized boolean markMediaWaiting(
            String commandId, String sessionId, long deadlineAtMs, long nowMs) {
        return update(commandId, record -> {
            if (record.state != State.RECEIVED) return false;
            record.mediaState = "WAITING_FOR_MEDIA_ACTIVITY";
            record.mediaSessionId = sessionId;
            record.mediaDeadlineAtMs = deadlineAtMs;
            record.updatedAtMs = nowMs;
            return true;
        });
    }

    public synchronized boolean markMediaExecuting(
            String commandId, String leaseId, long bindingGeneration, long nowMs) {
        return update(commandId, record -> {
            if (record.state != State.RECEIVED
                    && record.state != State.EXECUTING) return false;
            record.state = State.EXECUTING;
            record.mediaState = "DISPATCHING";
            record.mediaLeaseId = leaseId;
            record.mediaBindingGeneration = bindingGeneration;
            record.updatedAtMs = nowMs;
            return true;
        });
    }

    public synchronized boolean markMediaState(
            String commandId, String mediaState, long nowMs) {
        return update(commandId, record -> {
            if (record.state != State.EXECUTING) return false;
            record.mediaState = mediaState;
            record.updatedAtMs = nowMs;
            return true;
        });
    }

    public synchronized boolean markExecuting(String commandId, long nowMs) {
        return update(commandId, record -> {
            if (record.state != State.RECEIVED) return false;
            record.state = State.EXECUTING;
            record.updatedAtMs = nowMs;
            return true;
        });
    }

    public synchronized boolean markResultPending(
            String commandId, String resultPayload, State terminalState, long nowMs) {
        if (terminalState != State.COMPLETED && terminalState != State.FAILED) {
            throw new IllegalArgumentException("invalid_terminal_state");
        }
        return update(commandId, record -> {
            if (record.state == State.COMPLETED || record.state == State.FAILED) {
                return false;
            }
            record.terminalState = terminalState;
            record.state = State.RESULT_PENDING;
            record.resultState = ResultState.PENDING;
            record.resultPayload = resultPayload;
            if (record.mediaState != null) record.mediaState = "RESULT_PENDING";
            record.updatedAtMs = nowMs;
            return true;
        });
    }

    public synchronized boolean markResultDelivered(String commandId, long nowMs) {
        return update(commandId, record -> {
            if (record.state != State.RESULT_PENDING || record.terminalState == null) {
                return false;
            }
            record.state = record.terminalState;
            record.resultState = ResultState.DELIVERED;
            if (record.mediaState != null) record.mediaState = "TERMINAL";
            record.updatedAtMs = nowMs;
            return true;
        });
    }

    public synchronized List<Record> pendingResults() {
        try {
            Snapshot snapshot = normalized(persistence.load());
            List<Record> pending = new ArrayList<>();
            for (Record record : snapshot.records.values()) {
                if (record.state == State.RESULT_PENDING
                        && record.resultPayload != null) {
                    pending.add(record.copy());
                }
            }
            return pending;
        } catch (StoreException | RuntimeException e) {
            return Collections.emptyList();
        }
    }

    public synchronized int pendingResultCount() {
        return pendingResults().size();
    }

    public synchronized List<Record> records() {
        try {
            Snapshot snapshot = normalized(persistence.load());
            List<Record> copy = new ArrayList<>();
            for (Record record : snapshot.records.values()) copy.add(record.copy());
            return copy;
        } catch (StoreException | RuntimeException e) {
            return Collections.emptyList();
        }
    }

    public synchronized List<RecoveryItem> recover(long nowMs) {
        try {
            Snapshot snapshot = normalized(persistence.load());
            List<RecoveryItem> recovered = new ArrayList<>();
            for (Record record : snapshot.records.values()) {
                if (record.state == State.RESULT_PENDING && record.resultPayload != null) {
                    recovered.add(new RecoveryItem(RecoveryState.CACHED_RESULT, record.copy()));
                    continue;
                }
                if (record.state == State.EXECUTING) {
                    recovered.add(new RecoveryItem(RecoveryState.EXECUTION_UNKNOWN, record.copy()));
                    continue;
                }
                if (record.state != State.RECEIVED) continue;
                if (nowMs - record.receivedAtMs > maxPendingAgeMs) {
                    recovered.add(new RecoveryItem(RecoveryState.EXPIRED, record.copy()));
                } else if (CommandRecoveryPolicy.classify(record.actions)
                        == CommandRecoveryPolicy.Classification.SAFE_RETRY) {
                    recovered.add(new RecoveryItem(RecoveryState.SAFE_RETRY, record.copy()));
                } else {
                    recovered.add(new RecoveryItem(RecoveryState.UNSAFE_RETRY, record.copy()));
                }
            }
            return recovered;
        } catch (StoreException | RuntimeException e) {
            return Collections.emptyList();
        }
    }

    private interface RecordUpdate {
        boolean apply(Record record);
    }

    private boolean update(String commandId, RecordUpdate update) {
        try {
            Snapshot snapshot = normalized(persistence.load());
            Record record = snapshot.records.get(commandId);
            if (record == null || !update.apply(record)) return false;
            return persistence.save(snapshot);
        } catch (StoreException | RuntimeException e) {
            return false;
        }
    }

    private static Snapshot normalized(Snapshot snapshot) throws StoreException {
        if (snapshot == null || snapshot.records == null) {
            throw new StoreException("command_store_invalid_shape");
        }
        for (Record record : snapshot.records.values()) {
            if (record == null || record.commandId == null || record.payloadDigest == null
                    || record.state == null) {
                throw new StoreException("command_store_invalid_record");
            }
            if (record.actions == null) record.actions = new ArrayList<>();
            if (record.resultState == null) record.resultState = ResultState.NONE;
        }
        return snapshot;
    }

    private static List<ActionSummary> summaries(List<CanonicalAction> actions) {
        List<ActionSummary> summaries = new ArrayList<>();
        for (CanonicalAction action : actions) {
            summaries.add(new ActionSummary(action.getActionId(), action.getType()));
        }
        return summaries;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder(digest.length * 2);
            for (byte part : digest) {
                encoded.append(String.format(Locale.US, "%02x", part & 0xff));
            }
            return encoded.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("sha256_unavailable", e);
        }
    }
}
