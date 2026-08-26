package com.robotemi.agent.media.v11;

import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.robotemi.agent.command.CommandLedger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One-time, idempotent migration from the old canonical_media_v11 preference. */
public final class MediaV11LegacyMigration {
    public static final int VERSION = 1;

    public interface Clock {
        long nowMs();
        String nowTimestamp();
    }

    private MediaV11LegacyMigration() {}

    public static void migrate(
            CommandLedger.Persistence ledgerPersistence,
            SharedPreferences legacyPreferences,
            Clock clock) throws CommandLedger.StoreException {
        CommandLedger.Snapshot ledgerSnapshot = ledgerPersistence.load();
        if (ledgerSnapshot.mediaV11MigrationVersion >= VERSION) return;

        MediaV11Persistence legacyStore =
                new SharedPreferencesMediaV11Persistence(legacyPreferences);
        final MediaV11Persistence.Snapshot legacy;
        try {
            legacy = legacyStore.load();
        } catch (MediaV11Persistence.StoreException e) {
            throw new CommandLedger.StoreException("media_legacy_migration_read_failed", e);
        }
        MediaV11Persistence.Snapshot merged = legacy.copy();
        Set<String> auditOnlyConflictCommandIds = new HashSet<>();
        for (Map.Entry<String, MediaV11Persistence.CommandRecord> entry
                : merged.commands.entrySet()) {
            MediaV11Persistence.CommandRecord legacyRecord = entry.getValue();
            MediaV11Command command = parse(legacyRecord.commandJson);
            if (command == null) continue;

            CommandLedger.Record existing = ledgerSnapshot.records.get(command.getCommandId());
            String legacyDigest = digest(legacyRecord);
            if (existing != null && !legacyDigest.equals(existing.payloadDigest)) {
                // A command ID is immutable. Never let an older preference rewrite a
                // current record with a different payload. There is no independent generic
                // ledger/outbox owner for a second result under the same ID: the canonical
                // record may still be EXECUTING, so publishing a legacy conflict could be
                // followed by a second result for the current payload. Keep the old entry
                // in the untouched legacy preference as audit data only, and exclude it from
                // the active migrated media snapshot (including any stale outbox/session).
                auditOnlyConflictCommandIds.add(command.getCommandId());
                continue;
            }
            if (existing != null && isTerminalEstablished(existing)
                    && legacyRecord.terminal && legacyRecord.latestResultJson == null) {
                // The canonical ledger already owns a terminal result for this command.
                // Retain the obsolete preference as audit data without emitting a second
                // terminal payload for the same correlation.
                continue;
            }

            boolean hasPendingOutbox = containsPayload(merged.outbox, legacyRecord.latestResultJson);
            if (!legacyRecord.terminal) {
                MediaV11Result reconciliation = reconciliationResult(
                        command, merged.activeSession, clock.nowTimestamp());
                legacyRecord.latestResultJson = reconciliation.toJson();
                legacyRecord.terminal = true;
                enqueue(merged, reconciliation);
                if (merged.activeSession != null
                        && command.getCommandId().equals(
                                commandId(merged.activeSession.playCommandJson))) {
                    merged.activeSession = null;
                }
                hasPendingOutbox = true;
            } else if (legacyRecord.latestResultJson == null) {
                MediaV11Result missing = MediaV11Result.failed(
                        command, legacyRecord.sessionId, "INTERNAL_ERROR",
                        "media_legacy_terminal_result_missing", clock.nowTimestamp());
                legacyRecord.latestResultJson = missing.toJson();
                legacyRecord.terminal = true;
                enqueue(merged, missing);
                hasPendingOutbox = true;
            }
            mergeGenericRecord(ledgerSnapshot, command, legacyRecord, hasPendingOutbox, clock.nowMs());
        }
        removeAuditOnlyConflicts(merged, auditOnlyConflictCommandIds);
        ledgerSnapshot.mediaV11SnapshotJson = new Gson().toJson(merged);
        ledgerSnapshot.mediaV11MigrationVersion = VERSION;
        if (!ledgerPersistence.save(ledgerSnapshot)) {
            throw new CommandLedger.StoreException("media_legacy_migration_commit_failed");
        }
    }

    private static void mergeGenericRecord(
            CommandLedger.Snapshot snapshot,
            MediaV11Command command,
            MediaV11Persistence.CommandRecord legacy,
            boolean pending,
            long nowMs) {
        CommandLedger.Record existing = snapshot.records.get(command.getCommandId());
        if (existing != null && existing.state != CommandLedger.State.RECEIVED
                && existing.state != CommandLedger.State.EXECUTING) return;
        CommandLedger.Record record = existing == null
                ? new CommandLedger.Record() : existing;
        record.commandId = command.getCommandId();
        record.requestId = command.getRequestId();
        record.robotId = command.getRobotId();
        record.action = command.getAction().wireValue();
        record.videoId = command.getVideoId();
        record.mediaOperation = command.getAction().wireValue();
        record.mediaCommandJson = legacy.commandJson;
        record.mediaSessionId = legacy.sessionId;
        record.payloadDigest = legacy.payloadDigest == null
                ? sha256(legacy.commandJson) : legacy.payloadDigest;
        record.receivedAtMs = record.receivedAtMs == 0 ? nowMs : record.receivedAtMs;
        record.updatedAtMs = nowMs;
        record.resultPayload = legacy.latestResultJson;
        if (legacy.terminal && legacy.latestResultJson != null) {
            record.terminalState = terminalState(legacy.latestResultJson);
            record.state = pending
                    ? CommandLedger.State.RESULT_PENDING : record.terminalState;
            record.resultState = pending
                    ? CommandLedger.ResultState.PENDING : CommandLedger.ResultState.DELIVERED;
            record.mediaState = pending ? "RESULT_PENDING" : "TERMINAL";
        } else {
            record.state = CommandLedger.State.RECEIVED;
            record.resultState = CommandLedger.ResultState.NONE;
            record.mediaState = "WAITING_FOR_MEDIA_ACTIVITY";
        }
        snapshot.records.put(record.commandId, record);
    }

    private static boolean isTerminalEstablished(CommandLedger.Record record) {
        return record != null && record.resultPayload != null
                && (record.state == CommandLedger.State.RESULT_PENDING
                || record.state == CommandLedger.State.COMPLETED
                || record.state == CommandLedger.State.FAILED
                || record.resultState == CommandLedger.ResultState.PENDING
                || record.resultState == CommandLedger.ResultState.DELIVERED);
    }

    private static void removeAuditOnlyConflicts(
            MediaV11Persistence.Snapshot snapshot, Set<String> commandIds) {
        if (commandIds.isEmpty()) return;
        for (String commandId : commandIds) snapshot.commands.remove(commandId);
        Iterator<MediaV11Persistence.OutboxRecord> iterator = snapshot.outbox.iterator();
        while (iterator.hasNext()) {
            MediaV11Persistence.OutboxRecord record = iterator.next();
            if (commandIds.contains(commandId(record.payload))) iterator.remove();
        }
        if (snapshot.activeSession != null
                && commandIds.contains(commandId(snapshot.activeSession.playCommandJson))) {
            snapshot.activeSession = null;
        }
    }

    private static String digest(MediaV11Persistence.CommandRecord legacy) {
        if (legacy.payloadDigest != null) return legacy.payloadDigest;
        return sha256(legacy.commandJson);
    }

    private static MediaV11Result reconciliationResult(
            MediaV11Command command,
            MediaV11Persistence.ActiveSession activeSession,
            String timestamp) {
        if (command.getAction() == MediaV11Command.Action.PLAY_VIDEO
                && activeSession != null
                && command.getCommandId().equals(commandId(activeSession.playCommandJson))) {
            return MediaV11Result.cancelled(
                    command, activeSession.sessionId, null, "app_process_restart", "app_process",
                    "restart_reconciliation", timestamp);
        }
        String sessionId = command.getTargetPlaybackSessionId();
        if (command.getAction() == MediaV11Command.Action.PLAY_VIDEO) {
            return MediaV11Result.failed(
                    command, activeSession == null ? sessionId : activeSession.sessionId,
                    "APP_PROCESS_RESTART", "media_process_restart_ambiguous", timestamp);
        }
        return MediaV11Result.failed(
                command, sessionId, "INTERNAL_ERROR", "media_process_restart_ambiguous", timestamp);
    }

    private static CommandLedger.State terminalState(String payload) {
        try {
            String status = JsonParser.parseString(payload).getAsJsonObject()
                    .get("status").getAsString();
            return "failed".equals(status) || "rejected".equals(status)
                    ? CommandLedger.State.FAILED : CommandLedger.State.COMPLETED;
        } catch (RuntimeException e) {
            return CommandLedger.State.FAILED;
        }
    }

    private static boolean containsPayload(
            List<MediaV11Persistence.OutboxRecord> outbox, String payload) {
        if (payload == null) return false;
        String id = sha256(payload);
        for (MediaV11Persistence.OutboxRecord record : outbox) {
            if (id.equals(record.id) || payload.equals(record.payload)) return true;
        }
        return false;
    }

    private static void enqueue(MediaV11Persistence.Snapshot snapshot, MediaV11Result result) {
        String payload = result.toJson();
        String id = sha256(payload);
        for (MediaV11Persistence.OutboxRecord record : snapshot.outbox) {
            if (id.equals(record.id)) return;
        }
        MediaV11Persistence.OutboxRecord record = new MediaV11Persistence.OutboxRecord();
        record.id = id;
        record.payload = payload;
        snapshot.outbox.add(record);
    }

    private static MediaV11Command parse(String payload) {
        if (payload == null) return null;
        try {
            String robotId = JsonParser.parseString(payload).getAsJsonObject()
                    .get("robot_id").getAsString();
            return MediaV11Parser.parse(payload, robotId);
        } catch (RuntimeException | MediaV11Parser.ValidationException ignored) {
            return null;
        }
    }

    private static String commandId(String payload) {
        if (payload == null) return null;
        try {
            return JsonParser.parseString(payload).getAsJsonObject()
                    .get("command_id").getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder();
            for (byte item : digest) output.append(String.format("%02x", item & 0xff));
            return output.toString();
        } catch (Exception e) {
            throw new IllegalStateException("sha256_unavailable", e);
        }
    }
}
