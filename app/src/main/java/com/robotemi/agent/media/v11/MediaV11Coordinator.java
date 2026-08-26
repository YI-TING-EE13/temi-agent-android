package com.robotemi.agent.media.v11;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure orchestration core for session ownership, idempotency, state transitions,
 * terminal-result caching, and the durable MQTT result outbox.
 */
public final class MediaV11Coordinator {
    public static final int MAX_COMMAND_RECORDS = 256;
    public static final long RETENTION_MS = 7L * 24L * 60L * 60L * 1000L;

    public enum Effect { NONE, PLAY, PAUSE, RESUME, STOP }

    public interface Clock {
        long nowMs();
        String nowTimestamp();
    }

    public interface SessionIds {
        String next();
    }

    public static final class Outcome {
        private final Effect effect;
        private final MediaV11Command command;
        private final String sessionId;
        private final List<MediaV11Result> results;
        private final boolean durable;

        private Outcome(
                Effect effect, MediaV11Command command, String sessionId,
                List<MediaV11Result> results, boolean durable) {
            this.effect = effect;
            this.command = command;
            this.sessionId = sessionId;
            this.results = results;
            this.durable = durable;
        }

        public Effect getEffect() { return effect; }
        public MediaV11Command getCommand() { return command; }
        public String getSessionId() { return sessionId; }
        public List<MediaV11Result> getResults() { return new ArrayList<>(results); }
        public boolean isDurable() { return durable; }
    }

    private final MediaV11Persistence persistence;
    private final Clock clock;
    private final SessionIds sessionIds;

    public MediaV11Coordinator(MediaV11Persistence persistence, Clock clock) {
        this(persistence, clock, () -> UUID.randomUUID().toString());
    }

    public MediaV11Coordinator(
            MediaV11Persistence persistence, Clock clock, SessionIds sessionIds) {
        this.persistence = persistence;
        this.clock = clock;
        this.sessionIds = sessionIds;
    }

    public synchronized Outcome submit(MediaV11Command command, boolean enabled) {
        return submit(command, enabled, null);
    }

    public synchronized Outcome submit(
            MediaV11Command command, boolean enabled, String endpointFingerprint) {
        MediaV11Persistence.Snapshot snapshot;
        try {
            snapshot = persistence.load();
        } catch (MediaV11Persistence.StoreException e) {
            return nonDurableRejection(command, "INTERNAL_ERROR", "media_store_read_failed");
        }

        MediaV11Persistence.CommandRecord existing = snapshot.commands.get(command.getCommandId());
        if (existing != null) {
            return handleExisting(snapshot, command, existing, endpointFingerprint,
                    endpointFingerprint != null);
        }

        if (!enabled) {
            return durableRejection(snapshot, command, "UNSUPPORTED_MEDIA_ACTION",
                    "media_v1_1_feature_disabled", null, endpointFingerprint);
        }

        if (command.getAction() == MediaV11Command.Action.PLAY_VIDEO) {
            if (snapshot.activeSession != null) {
                return durableRejection(snapshot, command, "MEDIA_SESSION_ACTIVE",
                        "another_playback_session_is_active",
                        snapshot.activeSession.sessionId, endpointFingerprint);
            }
            String sessionId = sessionIds.next();
            MediaV11Result accepted = MediaV11Result.accepted(
                    command, sessionId, clock.nowTimestamp());
            MediaV11Persistence.CommandRecord record =
                    record(command, sessionId, endpointFingerprint, false, accepted);
            snapshot.commands.put(command.getCommandId(), record);
            MediaV11Persistence.ActiveSession active = new MediaV11Persistence.ActiveSession();
            active.playCommandJson = command.toJson();
            active.sessionId = sessionId;
            active.playbackState = "starting";
            snapshot.activeSession = active;
            enqueue(snapshot, accepted);
            if (!save(snapshot)) {
                return nonDurableRejection(command, "INTERNAL_ERROR", "media_store_write_failed");
            }
            return outcome(Effect.PLAY, command, sessionId, accepted, true);
        }

        if (snapshot.activeSession == null) {
            return durableRejection(snapshot, command, "MEDIA_SESSION_NOT_FOUND",
                    "no_active_playback_session", null, endpointFingerprint);
        }
        if (!snapshot.activeSession.sessionId.equals(command.getTargetPlaybackSessionId())) {
            return durableRejection(snapshot, command, "MEDIA_SESSION_NOT_FOUND",
                    "target_playback_session_mismatch", null, endpointFingerprint);
        }
        MediaV11Command playCommand = storedCommand(snapshot.activeSession.playCommandJson);
        if (playCommand == null || !playCommand.getVideoId().equals(command.getVideoId())) {
            return durableRejection(snapshot, command, "MEDIA_CONTROL_CONFLICT",
                    "video_id_does_not_match_active_session", null, endpointFingerprint);
        }

        String state = snapshot.activeSession.playbackState;
        String stateError = controlStateError(command.getAction(), state);
        if (stateError != null) {
            return durableRejection(snapshot, command, stateError,
                    "control_not_valid_for_playback_state_" + state,
                    null, endpointFingerprint);
        }
        snapshot.commands.put(command.getCommandId(),
                record(command, snapshot.activeSession.sessionId,
                        endpointFingerprint, false, null));
        if (!save(snapshot)) {
            return nonDurableRejection(command, "INTERNAL_ERROR", "media_store_write_failed");
        }
        Effect effect = command.getAction() == MediaV11Command.Action.PAUSE_VIDEO
                ? Effect.PAUSE : command.getAction() == MediaV11Command.Action.RESUME_VIDEO
                ? Effect.RESUME : Effect.STOP;
        return new Outcome(effect, command, snapshot.activeSession.sessionId,
                new ArrayList<>(), true);
    }

    /**
     * Resolves an already-known command without entering the serialized play queue.
     *
     * <p>Returns {@code null} only when the durable store was read successfully and
     * the command ID is unknown. Store failures fail closed with a non-durable
     * rejection. The regular {@link #submit(MediaV11Command, boolean)} path remains
     * authoritative for first delivery.</p>
     */
    public synchronized Outcome submitDuplicateIfKnown(MediaV11Command command) {
        return submitDuplicateIfKnown(command, null);
    }

    public synchronized Outcome submitDuplicateIfKnown(
            MediaV11Command command, String endpointFingerprint) {
        final MediaV11Persistence.Snapshot snapshot;
        try {
            snapshot = persistence.load();
        } catch (MediaV11Persistence.StoreException e) {
            return nonDurableRejection(command, "INTERNAL_ERROR", "media_store_read_failed");
        }
        MediaV11Persistence.CommandRecord existing =
                snapshot.commands.get(command.getCommandId());
        return existing == null ? null : handleExisting(
                snapshot, command, existing, endpointFingerprint,
                endpointFingerprint != null);
    }

    public synchronized Outcome rejectInvalid(
            MediaV11Command command, String errorCode, String message) {
        return rejectInvalid(command, errorCode, message, null);
    }

    public synchronized Outcome rejectInvalid(
            MediaV11Command command, String errorCode, String message,
            String endpointFingerprint) {
        MediaV11Persistence.Snapshot snapshot = safeLoad();
        if (snapshot == null) {
            return nonDurableRejection(command, "INTERNAL_ERROR", "media_store_read_failed");
        }
        MediaV11Persistence.CommandRecord existing = snapshot.commands.get(command.getCommandId());
        if (existing != null) {
            if (!digest(command).equals(existing.payloadDigest)) {
                return durablePayloadConflict(snapshot, command);
            }
            if (existing.terminal && existing.latestResultJson != null) {
                MediaV11Result replay = MediaV11Result.fromJson(existing.latestResultJson)
                        .withDelivery("cached_replay", clock.nowTimestamp());
                enqueue(snapshot, replay);
                if (!save(snapshot)) {
                    return nonDurableRejection(
                            command, "INTERNAL_ERROR", "media_store_write_failed");
                }
                return outcome(Effect.NONE, command, existing.sessionId, replay, true);
            }
        }
        return durableRejection(
                snapshot, command, errorCode, message, null, endpointFingerprint);
    }

    public synchronized Outcome rejectConcurrentLocalPlayback(
            MediaV11Command command, String activeSessionId) {
        return rejectConcurrentLocalPlayback(command, activeSessionId, null);
    }

    public synchronized Outcome rejectConcurrentLocalPlayback(
            MediaV11Command command, String activeSessionId, String endpointFingerprint) {
        MediaV11Persistence.Snapshot snapshot = safeLoad();
        if (snapshot == null) {
            return nonDurableRejection(command, "INTERNAL_ERROR", "media_store_read_failed");
        }
        return durableRejection(snapshot, command, "MEDIA_SESSION_ACTIVE",
                "local_playback_session_is_active", activeSessionId, endpointFingerprint);
    }

    public synchronized List<MediaV11Result> markStarted(String sessionId) {
        return updatePlay(sessionId, "playing", "started", null, null);
    }

    public synchronized List<MediaV11Result> complete(String sessionId) {
        return updatePlay(sessionId, "completed", "completed", null, null);
    }

    public synchronized List<MediaV11Result> fail(String sessionId, String message) {
        return updatePlay(sessionId, "failed", "failed", "INTERNAL_ERROR",
                message == null ? "media_playback_failed" : message);
    }

    public synchronized List<MediaV11Result> controlSucceeded(MediaV11Command control) {
        MediaV11Persistence.Snapshot snapshot = safeLoad();
        if (snapshot == null || snapshot.activeSession == null
                || !snapshot.activeSession.sessionId.equals(control.getTargetPlaybackSessionId())) {
            return new ArrayList<>();
        }
        MediaV11Command play = storedCommand(snapshot.activeSession.playCommandJson);
        if (play == null) {
            return new ArrayList<>();
        }
        List<MediaV11Result> results = new ArrayList<>();
        String sessionId = snapshot.activeSession.sessionId;
        String playbackState;
        if (control.getAction() == MediaV11Command.Action.PAUSE_VIDEO) {
            playbackState = "paused";
        } else if (control.getAction() == MediaV11Command.Action.RESUME_VIDEO) {
            playbackState = "playing";
        } else {
            playbackState = "cancelled";
        }
        MediaV11Result controlResult = MediaV11Result.controlSucceeded(
                control, sessionId, playbackState, clock.nowTimestamp());
        finishRecord(snapshot, control, controlResult, sessionId);
        enqueue(snapshot, controlResult);
        results.add(controlResult);

        if (control.getAction() == MediaV11Command.Action.STOP_VIDEO) {
            MediaV11Result cancellation = MediaV11Result.cancelled(
                    play, sessionId, control.getCommandId(), "remote_stop", "remote_command",
                    "original", clock.nowTimestamp());
            finishRecord(snapshot, play, cancellation, sessionId);
            enqueue(snapshot, cancellation);
            results.add(cancellation);
            snapshot.activeSession = null;
        } else {
            snapshot.activeSession.playbackState = playbackState;
        }
        return save(snapshot) ? results : new ArrayList<>();
    }

    public synchronized List<MediaV11Result> controlFailed(
            MediaV11Command control, String message) {
        MediaV11Persistence.Snapshot snapshot = safeLoad();
        if (snapshot == null) {
            return new ArrayList<>();
        }
        MediaV11Result result = MediaV11Result.failed(
                control, control.getTargetPlaybackSessionId(), "INTERNAL_ERROR",
                message == null ? "media_control_failed" : message, clock.nowTimestamp());
        finishRecord(snapshot, control, result, control.getTargetPlaybackSessionId());
        enqueue(snapshot, result);
        List<MediaV11Result> results = singleton(result);
        return save(snapshot) ? results : new ArrayList<>();
    }

    public synchronized List<MediaV11Result> localUserStop() {
        return cancelActive(null, "local_user_stop", "local_user", "original");
    }

    public synchronized List<MediaV11Result> reconcileAfterProcessRestart() {
        return cancelActive(null, "app_process_restart", "app_process",
                "restart_reconciliation");
    }

    public synchronized List<MediaV11Persistence.OutboxRecord> pendingOutbox() {
        MediaV11Persistence.Snapshot snapshot = safeLoad();
        List<MediaV11Persistence.OutboxRecord> copy = new ArrayList<>();
        if (snapshot != null) {
            for (MediaV11Persistence.OutboxRecord record : snapshot.outbox) {
                copy.add(record.copy());
            }
        }
        return copy;
    }

    public synchronized boolean acknowledgeOutbox(String id) {
        MediaV11Persistence.Snapshot snapshot = safeLoad();
        if (snapshot == null) {
            return false;
        }
        boolean removed = false;
        Iterator<MediaV11Persistence.OutboxRecord> iterator = snapshot.outbox.iterator();
        while (iterator.hasNext()) {
            if (id.equals(iterator.next().id)) {
                iterator.remove();
                removed = true;
            }
        }
        return !removed || save(snapshot);
    }

    /** Explicitly discards pending delivery records without changing command history. */
    public synchronized boolean discardPendingOutbox() {
        MediaV11Persistence.Snapshot snapshot = safeLoad();
        if (snapshot == null) {
            return false;
        }
        if (snapshot.outbox.isEmpty()) {
            return true;
        }
        snapshot.outbox.clear();
        return save(snapshot);
    }

    public synchronized String activeSessionId() {
        MediaV11Persistence.Snapshot snapshot = safeLoad();
        return snapshot == null || snapshot.activeSession == null
                ? null : snapshot.activeSession.sessionId;
    }

    private List<MediaV11Result> updatePlay(
            String sessionId, String state, String status, String errorCode, String errorMessage) {
        MediaV11Persistence.Snapshot snapshot = safeLoad();
        if (snapshot == null || snapshot.activeSession == null
                || !snapshot.activeSession.sessionId.equals(sessionId)) {
            return new ArrayList<>();
        }
        MediaV11Command play = storedCommand(snapshot.activeSession.playCommandJson);
        if (play == null) {
            return new ArrayList<>();
        }
        MediaV11Result result;
        if ("started".equals(status)) {
            result = MediaV11Result.started(play, sessionId, clock.nowTimestamp());
        } else if ("completed".equals(status)) {
            result = MediaV11Result.completed(play, sessionId, clock.nowTimestamp());
        } else {
            result = MediaV11Result.failed(
                    play, sessionId, errorCode, errorMessage, clock.nowTimestamp());
        }
        boolean terminal = result.isTerminal();
        MediaV11Persistence.CommandRecord record = snapshot.commands.get(play.getCommandId());
        if (record == null || record.terminal) {
            return new ArrayList<>();
        }
        record.latestResultJson = result.toJson();
        record.terminal = terminal;
        record.updatedAtMs = clock.nowMs();
        enqueue(snapshot, result);
        if (terminal) {
            snapshot.activeSession = null;
        } else {
            snapshot.activeSession.playbackState = state;
        }
        return save(snapshot) ? singleton(result) : new ArrayList<>();
    }

    private List<MediaV11Result> cancelActive(
            String cancelledBy, String reason, String actor, String delivery) {
        MediaV11Persistence.Snapshot snapshot = safeLoad();
        if (snapshot == null || snapshot.activeSession == null) {
            return new ArrayList<>();
        }
        MediaV11Command play = storedCommand(snapshot.activeSession.playCommandJson);
        if (play == null) {
            return new ArrayList<>();
        }
        MediaV11Result result = MediaV11Result.cancelled(
                play, snapshot.activeSession.sessionId, cancelledBy, reason, actor, delivery,
                clock.nowTimestamp());
        finishRecord(snapshot, play, result, snapshot.activeSession.sessionId);
        enqueue(snapshot, result);
        snapshot.activeSession = null;
        return save(snapshot) ? singleton(result) : new ArrayList<>();
    }

    private Outcome durableRejection(
            MediaV11Persistence.Snapshot snapshot, MediaV11Command command, String errorCode,
            String message, String activeSessionId) {
        return durableRejection(
                snapshot, command, errorCode, message, activeSessionId, null);
    }

    private Outcome durableRejection(
            MediaV11Persistence.Snapshot snapshot, MediaV11Command command, String errorCode,
            String message, String activeSessionId, String endpointFingerprint) {
        MediaV11Result result = MediaV11Result.rejected(
                command, errorCode, message, activeSessionId, clock.nowTimestamp());
        finishRecord(snapshot, command, result, null, endpointFingerprint);
        enqueue(snapshot, result);
        if (!save(snapshot)) {
            return nonDurableRejection(command, "INTERNAL_ERROR", "media_store_write_failed");
        }
        return outcome(Effect.NONE, command, null, result, true);
    }

    private Outcome nonDurableRejection(
            MediaV11Command command, String errorCode, String message) {
        MediaV11Result result = MediaV11Result.rejected(
                command, errorCode, message, null, clock.nowTimestamp());
        return outcome(Effect.NONE, command, null, result, false);
    }

    private Outcome durablePayloadConflict(
            MediaV11Persistence.Snapshot snapshot, MediaV11Command command) {
        MediaV11Result result = MediaV11Result.rejected(
                command, "MEDIA_CONTROL_CONFLICT", "command_id_payload_conflict", null,
                clock.nowTimestamp());
        enqueue(snapshot, result);
        if (!save(snapshot)) {
            return nonDurableRejection(command, "INTERNAL_ERROR", "media_store_write_failed");
        }
        return outcome(Effect.NONE, command, null, result, true);
    }

    private Outcome handleExisting(
            MediaV11Persistence.Snapshot snapshot,
            MediaV11Command command,
            MediaV11Persistence.CommandRecord existing,
            String endpointFingerprint,
            boolean enforceEndpoint
    ) {
        if (!digest(command).equals(existing.payloadDigest)) {
            return durablePayloadConflict(snapshot, command);
        }
        if (enforceEndpoint && (existing.endpointFingerprint == null
                || !existing.endpointFingerprint.equals(endpointFingerprint))) {
            return nonDurableRejection(
                    command, "MEDIA_CONTROL_CONFLICT", "command_endpoint_mismatch");
        }
        if (existing.terminal && existing.latestResultJson != null) {
            MediaV11Result replay = MediaV11Result.fromJson(existing.latestResultJson)
                    .withDelivery("cached_replay", clock.nowTimestamp());
            enqueue(snapshot, replay);
            if (!save(snapshot)) {
                return nonDurableRejection(
                        command, "INTERNAL_ERROR", "media_store_write_failed");
            }
            return outcome(Effect.NONE, command, existing.sessionId, replay, true);
        }
        if ("play_video".equals(existing.action) && existing.latestResultJson != null) {
            MediaV11Result reference = MediaV11Result.fromJson(existing.latestResultJson)
                    .withDelivery("active_reference", clock.nowTimestamp());
            enqueue(snapshot, reference);
            if (!save(snapshot)) {
                return nonDurableRejection(
                        command, "INTERNAL_ERROR", "media_store_write_failed");
            }
            return outcome(Effect.NONE, command, existing.sessionId, reference, true);
        }
        return durableRejection(snapshot, command, "MEDIA_CONTROL_CONFLICT",
                "duplicate_control_in_progress", null);
    }

    private static String controlStateError(MediaV11Command.Action action, String state) {
        if (action == MediaV11Command.Action.PAUSE_VIDEO && !"playing".equals(state)) {
            return "MEDIA_SESSION_NOT_PLAYING";
        }
        if (action == MediaV11Command.Action.RESUME_VIDEO && !"paused".equals(state)) {
            return "MEDIA_SESSION_NOT_PAUSED";
        }
        if (action == MediaV11Command.Action.STOP_VIDEO
                && !("playing".equals(state) || "paused".equals(state))) {
            return "MEDIA_CONTROL_CONFLICT";
        }
        return null;
    }

    private MediaV11Command storedCommand(String json) {
        try {
            String robotId = com.google.gson.JsonParser.parseString(json).getAsJsonObject()
                    .get("robot_id").getAsString();
            return MediaV11Parser.parse(json, robotId);
        } catch (RuntimeException | MediaV11Parser.ValidationException e) {
            return null;
        }
    }

    private MediaV11Persistence.Snapshot safeLoad() {
        try {
            return persistence.load();
        } catch (MediaV11Persistence.StoreException e) {
            return null;
        }
    }

    private boolean save(MediaV11Persistence.Snapshot snapshot) {
        prune(snapshot);
        try {
            persistence.save(snapshot);
            return true;
        } catch (MediaV11Persistence.StoreException e) {
            return false;
        }
    }

    private void prune(MediaV11Persistence.Snapshot snapshot) {
        long cutoff = clock.nowMs() - RETENTION_MS;
        Iterator<Map.Entry<String, MediaV11Persistence.CommandRecord>> iterator =
                snapshot.commands.entrySet().iterator();
        while (iterator.hasNext()) {
            MediaV11Persistence.CommandRecord record = iterator.next().getValue();
            if (record.terminal && record.updatedAtMs < cutoff) {
                iterator.remove();
            }
        }
        iterator = snapshot.commands.entrySet().iterator();
        while (snapshot.commands.size() > MAX_COMMAND_RECORDS && iterator.hasNext()) {
            if (iterator.next().getValue().terminal) {
                iterator.remove();
            }
        }
    }

    private MediaV11Persistence.CommandRecord record(
            MediaV11Command command, String sessionId, String endpointFingerprint,
            boolean terminal,
            MediaV11Result latestResult) {
        MediaV11Persistence.CommandRecord record = new MediaV11Persistence.CommandRecord();
        record.commandJson = command.toJson();
        record.payloadDigest = digest(command);
        record.action = command.getAction().wireValue();
        record.sessionId = sessionId;
        record.endpointFingerprint = endpointFingerprint;
        record.terminal = terminal;
        record.latestResultJson = latestResult == null ? null : latestResult.toJson();
        record.updatedAtMs = clock.nowMs();
        return record;
    }

    private void finishRecord(
            MediaV11Persistence.Snapshot snapshot, MediaV11Command command,
            MediaV11Result result, String sessionId) {
        MediaV11Persistence.CommandRecord existing =
                snapshot.commands.get(command.getCommandId());
        String endpointFingerprint =
                existing == null ? null : existing.endpointFingerprint;
        finishRecord(snapshot, command, result, sessionId, endpointFingerprint);
    }

    private void finishRecord(
            MediaV11Persistence.Snapshot snapshot, MediaV11Command command,
            MediaV11Result result, String sessionId, String endpointFingerprint) {
        MediaV11Persistence.CommandRecord record =
                record(command, sessionId, endpointFingerprint, true, result);
        snapshot.commands.put(command.getCommandId(), record);
    }

    private static void enqueue(
            MediaV11Persistence.Snapshot snapshot, MediaV11Result result) {
        String payload = result.toJson();
        String id = sha256(payload);
        for (MediaV11Persistence.OutboxRecord record : snapshot.outbox) {
            if (id.equals(record.id)) {
                return;
            }
        }
        MediaV11Persistence.OutboxRecord record = new MediaV11Persistence.OutboxRecord();
        record.id = id;
        record.payload = payload;
        snapshot.outbox.add(record);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder();
            for (byte item : digest) {
                output.append(String.format("%02x", item & 0xff));
            }
            return output.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String digest(MediaV11Command command) {
        return sha256(command.toJson());
    }

    private static Outcome outcome(
            Effect effect, MediaV11Command command, String sessionId, MediaV11Result result,
            boolean durable) {
        return new Outcome(effect, command, sessionId, singleton(result), durable);
    }

    private static List<MediaV11Result> singleton(MediaV11Result result) {
        List<MediaV11Result> results = new ArrayList<>();
        results.add(result);
        return results;
    }
}
