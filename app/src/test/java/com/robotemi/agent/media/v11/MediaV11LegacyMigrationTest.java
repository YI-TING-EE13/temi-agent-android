package com.robotemi.agent.media.v11;

import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.robotemi.agent.command.CommandLedger;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Legacy preference migration remains idempotent and preserves the source snapshot. */
public class MediaV11LegacyMigrationTest {
    @Test
    public void migratesNonterminalLegacySessionIntoGenericLedgerOnce() throws Exception {
        MemoryPreferences preferences = new MemoryPreferences();
        MediaV11Persistence.Snapshot legacy = new MediaV11Persistence.Snapshot();
        String payload = MediaV11Fixtures.request(
                "legacy-restart", "play_video", "serialized_execution", null);
        MediaV11Persistence.CommandRecord command = new MediaV11Persistence.CommandRecord();
        command.commandJson = payload;
        command.action = "play_video";
        command.sessionId = "legacy-session";
        command.terminal = false;
        legacy.commands.put("legacy-restart", command);
        MediaV11Persistence.ActiveSession session = new MediaV11Persistence.ActiveSession();
        session.playCommandJson = payload;
        session.sessionId = "legacy-session";
        session.playbackState = "playing";
        legacy.activeSession = session;
        preferences.edit().putString("snapshot_json", new Gson().toJson(legacy)).commit();

        MemoryLedgerPersistence ledgerPersistence = new MemoryLedgerPersistence();
        MediaV11LegacyMigration.migrate(
                ledgerPersistence,
                preferences,
                new MediaV11LegacyMigration.Clock() {
                    @Override public long nowMs() { return 10_000L; }
                    @Override public String nowTimestamp() {
                        return "2026-08-02T16:00:00.000Z";
                    }
                });

        CommandLedger.Snapshot migrated = ledgerPersistence.load();
        assertEquals(MediaV11LegacyMigration.VERSION, migrated.mediaV11MigrationVersion);
        CommandLedger.Record record = migrated.records.get("legacy-restart");
        assertEquals(CommandLedger.State.RESULT_PENDING, record.state);
        assertEquals(CommandLedger.ResultState.PENDING, record.resultState);
        assertEquals("RESULT_PENDING", record.mediaState);
        MediaV11ResultConformanceTest.assertConforms(MediaV11Result.fromJson(record.resultPayload));
        assertTrue(preferences.contains("snapshot_json"));
        String encodedAfterFirst = migrated.mediaV11SnapshotJson;

        MediaV11LegacyMigration.migrate(
                ledgerPersistence,
                preferences,
                new MediaV11LegacyMigration.Clock() {
                    @Override public long nowMs() { return 20_000L; }
                    @Override public String nowTimestamp() {
                        return "2026-08-02T16:01:00.000Z";
                    }
                });
        assertEquals(encodedAfterFirst, ledgerPersistence.load().mediaV11SnapshotJson);
        assertTrue(preferences.contains("snapshot_json"));
    }

    @Test
    public void differentDigestDoesNotOverwriteCanonicalRecordAndFailsLegacyClosed()
            throws Exception {
        MemoryPreferences preferences = new MemoryPreferences();
        String currentPayload = MediaV11Fixtures.request(
                "same-id", "play_video", "serialized_execution", null);
        String legacyPayload = currentPayload.replace(
                "elderly_hand_exercise", "elderly_leg_exercise");
        MediaV11Persistence.Snapshot legacy = new MediaV11Persistence.Snapshot();
        MediaV11Persistence.CommandRecord legacyRecord = commandRecord(
                legacyPayload, false, null);
        legacy.commands.put("same-id", legacyRecord);
        MediaV11Persistence.OutboxRecord staleConflict = new MediaV11Persistence.OutboxRecord();
        staleConflict.payload = MediaV11Result.rejected(
                MediaV11Fixtures.parsePlay("same-id"), "MEDIA_CONTROL_CONFLICT",
                "command_id_payload_conflict", null, "2026-08-02T16:00:00Z").toJson();
        staleConflict.id = sha256(staleConflict.payload);
        legacy.outbox.add(staleConflict);
        String originalLegacySnapshot = new Gson().toJson(legacy);
        preferences.edit().putString("snapshot_json", originalLegacySnapshot).commit();

        MemoryLedgerPersistence ledgerPersistence = new MemoryLedgerPersistence();
        CommandLedger.Snapshot current = new CommandLedger.Snapshot();
        CommandLedger.Record canonical = new CommandLedger.Record();
        canonical.commandId = "same-id";
        canonical.requestId = "event-same-id";
        canonical.robotId = "temi-01";
        canonical.action = "play_video";
        canonical.payloadDigest = sha256(currentPayload);
        canonical.state = CommandLedger.State.EXECUTING;
        canonical.resultState = CommandLedger.ResultState.NONE;
        canonical.mediaState = "PLAYING";
        canonical.mediaCommandJson = currentPayload;
        current.records.put(canonical.commandId, canonical);
        ledgerPersistence.seed(current);

        MediaV11LegacyMigration.migrate(ledgerPersistence, preferences, clock());

        CommandLedger.Record preserved = ledgerPersistence.load().records.get("same-id");
        assertEquals(1, ledgerPersistence.load().records.size());
        assertEquals(canonical.payloadDigest, preserved.payloadDigest);
        assertEquals(canonical.mediaCommandJson, preserved.mediaCommandJson);
        assertEquals(CommandLedger.State.EXECUTING, preserved.state);
        assertEquals("PLAYING", preserved.mediaState);

        MediaV11Persistence.Snapshot migrated =
                new Gson().fromJson(ledgerPersistence.load().mediaV11SnapshotJson,
                        MediaV11Persistence.Snapshot.class);
        assertNull(migrated.commands.get("same-id"));
        assertTrue(migrated.outbox.isEmpty());
        assertNull(migrated.activeSession);
        assertEquals(originalLegacySnapshot, preferences.getString("snapshot_json", null));

        MediaV11ServiceRuntime runtime = new MediaV11ServiceRuntime(
                new CommandLedger(ledgerPersistence), ledgerPersistence,
                new RuntimeClock(), (task, delay) -> () -> {}, () -> {},
                (phase, topic, commandId, eventId, actionId, state, outcome) -> {}, true, 10_000L);
        CountingBinding binding = new CountingBinding();
        runtime.reconcileAfterProcessRestart();
        runtime.attachBinding(binding);
        assertEquals(0, binding.startCount);
        // Collision records are intentionally audit-only: a reconstructed service runtime
        // must have no publishable result and must not dispatch the legacy command.
        assertTrue(runtime.pendingOutbox().isEmpty());

        String migratedSnapshot = ledgerPersistence.load().mediaV11SnapshotJson;
        MediaV11LegacyMigration.migrate(ledgerPersistence, preferences, clock());
        assertEquals(migratedSnapshot, ledgerPersistence.load().mediaV11SnapshotJson);
    }

    @Test
    public void missingLegacyTerminalResultFailsClosedWithSchemaValidResult() throws Exception {
        MemoryPreferences preferences = new MemoryPreferences();
        String payload = MediaV11Fixtures.request(
                "missing-terminal", "play_video", "serialized_execution", null);
        MediaV11Persistence.Snapshot legacy = new MediaV11Persistence.Snapshot();
        legacy.commands.put("missing-terminal", commandRecord(payload, true, null));
        preferences.edit().putString("snapshot_json", new Gson().toJson(legacy)).commit();

        MemoryLedgerPersistence ledgerPersistence = new MemoryLedgerPersistence();
        MediaV11LegacyMigration.migrate(ledgerPersistence, preferences, clock());

        CommandLedger.Record record = ledgerPersistence.load().records.get("missing-terminal");
        assertEquals(CommandLedger.State.RESULT_PENDING, record.state);
        MediaV11Result result = MediaV11Result.fromJson(record.resultPayload);
        MediaV11ResultConformanceTest.assertConforms(result);
        assertEquals("failed", result.getStatus());
        assertEquals("media_legacy_terminal_result_missing",
                JsonParser.parseString(result.toJson()).getAsJsonObject()
                        .get("error_message").getAsString());
    }

    @Test
    public void ambiguousPlayAndLegacyControlReconciliationResultsConform() throws Exception {
        MemoryPreferences preferences = new MemoryPreferences();
        String playPayload = MediaV11Fixtures.request(
                "ambiguous-play", "play_video", "serialized_execution", null);
        String controlPayload = MediaV11Fixtures.request(
                "legacy-control", "pause_video", "active_playback_control", "missing-session");
        MediaV11Persistence.Snapshot legacy = new MediaV11Persistence.Snapshot();
        legacy.commands.put("ambiguous-play", commandRecord(playPayload, false, null));
        legacy.commands.put("legacy-control", commandRecord(controlPayload, false, null));
        preferences.edit().putString("snapshot_json", new Gson().toJson(legacy)).commit();

        MemoryLedgerPersistence ledgerPersistence = new MemoryLedgerPersistence();
        MediaV11LegacyMigration.migrate(ledgerPersistence, preferences, clock());

        CommandLedger.Snapshot migrated = ledgerPersistence.load();
        MediaV11Result play = MediaV11Result.fromJson(
                migrated.records.get("ambiguous-play").resultPayload);
        MediaV11Result control = MediaV11Result.fromJson(
                migrated.records.get("legacy-control").resultPayload);
        MediaV11ResultConformanceTest.assertConforms(play);
        MediaV11ResultConformanceTest.assertConforms(control);
        assertEquals("APP_PROCESS_RESTART",
                JsonParser.parseString(play.toJson()).getAsJsonObject()
                        .get("error_code").getAsString());
        assertEquals("INTERNAL_ERROR",
                JsonParser.parseString(control.toJson()).getAsJsonObject()
                        .get("error_code").getAsString());
        MediaV11Persistence.Snapshot mediaSnapshot = new Gson().fromJson(
                migrated.mediaV11SnapshotJson, MediaV11Persistence.Snapshot.class);
        assertEquals(2, mediaSnapshot.outbox.size());
        for (MediaV11Persistence.OutboxRecord outbox : mediaSnapshot.outbox) {
            MediaV11ResultConformanceTest.assertConforms(
                    MediaV11Result.fromJson(outbox.payload));
        }
    }

    @Test
    public void terminalCanonicalRecordPreservesDifferentDigestLegacyAuditWithoutSideEffect()
            throws Exception {
        MemoryPreferences preferences = new MemoryPreferences();
        String currentPayload = MediaV11Fixtures.request(
                "terminal-owner", "play_video", "serialized_execution", null);
        String legacyPayload = currentPayload.replace(
                "elderly_hand_exercise", "elderly_leg_exercise");
        MediaV11Persistence.Snapshot legacy = new MediaV11Persistence.Snapshot();
        legacy.commands.put("terminal-owner", commandRecord(legacyPayload, false, null));
        String legacySnapshot = new Gson().toJson(legacy);
        preferences.edit().putString("snapshot_json", legacySnapshot).commit();

        MemoryLedgerPersistence ledgerPersistence = new MemoryLedgerPersistence();
        CommandLedger.Snapshot current = new CommandLedger.Snapshot();
        CommandLedger.Record owner = new CommandLedger.Record();
        owner.commandId = "terminal-owner";
        owner.payloadDigest = sha256(currentPayload);
        owner.state = CommandLedger.State.COMPLETED;
        owner.resultState = CommandLedger.ResultState.DELIVERED;
        owner.resultPayload = MediaV11Result.completed(
                MediaV11Fixtures.parsePlay("terminal-owner"), "session",
                "2026-08-02T16:00:00.000Z").toJson();
        current.records.put(owner.commandId, owner);
        ledgerPersistence.seed(current);

        MediaV11LegacyMigration.migrate(ledgerPersistence, preferences, clock());
        CommandLedger.Record preserved = ledgerPersistence.load().records.get("terminal-owner");
        assertEquals(owner.payloadDigest, preserved.payloadDigest);
        assertEquals(owner.resultPayload, preserved.resultPayload);
        MediaV11Persistence.Snapshot migrated = new Gson().fromJson(
                ledgerPersistence.load().mediaV11SnapshotJson, MediaV11Persistence.Snapshot.class);
        assertNull(migrated.commands.get("terminal-owner"));
        assertTrue(migrated.outbox.isEmpty());
        assertEquals(legacySnapshot, preferences.getString("snapshot_json", null));
    }

    @Test
    public void migrationSaveFailureLeavesMarkerSnapshotAndLegacyUntouchedThenRetriesOnce()
            throws Exception {
        MemoryPreferences preferences = new MemoryPreferences();
        String payload = MediaV11Fixtures.request(
                "save-retry", "play_video", "serialized_execution", null);
        MediaV11Persistence.Snapshot legacy = new MediaV11Persistence.Snapshot();
        MediaV11Persistence.ActiveSession active = new MediaV11Persistence.ActiveSession();
        active.playCommandJson = payload;
        active.sessionId = "legacy-session";
        active.playbackState = "playing";
        legacy.activeSession = active;
        legacy.commands.put("save-retry", commandRecord(payload, false, null));
        String originalLegacy = new Gson().toJson(legacy);
        preferences.edit().putString("snapshot_json", originalLegacy).commit();

        MemoryLedgerPersistence ledgerPersistence = new MemoryLedgerPersistence();
        ledgerPersistence.failWrites = true;
        try {
            MediaV11LegacyMigration.migrate(ledgerPersistence, preferences, clock());
        } catch (CommandLedger.StoreException expected) {
            assertEquals("media_legacy_migration_commit_failed", expected.getMessage());
        }
        assertEquals(0, ledgerPersistence.load().mediaV11MigrationVersion);
        assertNull(ledgerPersistence.load().mediaV11SnapshotJson);
        assertEquals(originalLegacy, preferences.getString("snapshot_json", null));

        ledgerPersistence.failWrites = false;
        MediaV11LegacyMigration.migrate(ledgerPersistence, preferences, clock());
        CommandLedger.Snapshot migrated = ledgerPersistence.load();
        assertEquals(MediaV11LegacyMigration.VERSION, migrated.mediaV11MigrationVersion);
        assertEquals(CommandLedger.State.RESULT_PENDING,
                migrated.records.get("save-retry").state);
        MediaV11ResultConformanceTest.assertConforms(
                MediaV11Result.fromJson(migrated.records.get("save-retry").resultPayload));
        MediaV11Persistence.Snapshot mediaSnapshot = new Gson().fromJson(
                migrated.mediaV11SnapshotJson, MediaV11Persistence.Snapshot.class);
        assertEquals(1, mediaSnapshot.outbox.size());
        MediaV11ResultConformanceTest.assertConforms(
                MediaV11Result.fromJson(mediaSnapshot.outbox.get(0).payload));
    }

    @Test
    public void sameDigestCurrentRecordMergesWithoutConflictAndLegacyOutboxIsRetained()
            throws Exception {
        MemoryPreferences preferences = new MemoryPreferences();
        String payload = MediaV11Fixtures.request(
                "same-digest", "play_video", "serialized_execution", null);
        MediaV11Persistence.Snapshot legacy = new MediaV11Persistence.Snapshot();
        MediaV11Persistence.CommandRecord command = commandRecord(payload, true,
                MediaV11Result.completed(
                        MediaV11Fixtures.parsePlay("same-digest"), "session", "2026-08-02T16:00:00Z")
                        .toJson());
        legacy.commands.put("same-digest", command);
        MediaV11Persistence.OutboxRecord pending = new MediaV11Persistence.OutboxRecord();
        pending.id = sha256(command.latestResultJson);
        pending.payload = command.latestResultJson;
        legacy.outbox.add(pending);
        String sourceSnapshot = new Gson().toJson(legacy);
        preferences.edit().putString("snapshot_json", sourceSnapshot).commit();

        MemoryLedgerPersistence ledgerPersistence = new MemoryLedgerPersistence();
        CommandLedger.Snapshot current = new CommandLedger.Snapshot();
        CommandLedger.Record existing = new CommandLedger.Record();
        existing.commandId = "same-digest";
        existing.payloadDigest = sha256(payload);
        existing.state = CommandLedger.State.RECEIVED;
        existing.resultState = CommandLedger.ResultState.NONE;
        current.records.put(existing.commandId, existing);
        ledgerPersistence.seed(current);

        MediaV11LegacyMigration.migrate(ledgerPersistence, preferences, clock());
        CommandLedger.Record merged = ledgerPersistence.load().records.get("same-digest");
        assertEquals(CommandLedger.State.RESULT_PENDING, merged.state);
        assertEquals(CommandLedger.ResultState.PENDING, merged.resultState);
        MediaV11ResultConformanceTest.assertConforms(MediaV11Result.fromJson(merged.resultPayload));
        MediaV11Persistence.Snapshot migrated = new Gson().fromJson(
                ledgerPersistence.load().mediaV11SnapshotJson, MediaV11Persistence.Snapshot.class);
        assertEquals(1, migrated.outbox.size());
        MediaV11ResultConformanceTest.assertConforms(
                MediaV11Result.fromJson(migrated.outbox.get(0).payload));
        assertEquals(sourceSnapshot, preferences.getString("snapshot_json", null));
    }

    @Test
    public void terminalCachedResultWithoutOutboxIsImportedAndConforms() throws Exception {
        MemoryPreferences preferences = new MemoryPreferences();
        String payload = MediaV11Fixtures.request(
                "terminal-cache", "play_video", "serialized_execution", null);
        String result = MediaV11Result.completed(
                MediaV11Fixtures.parsePlay("terminal-cache"), "legacy-session",
                "2026-08-02T16:00:00.000Z").toJson();
        MediaV11Persistence.Snapshot legacy = new MediaV11Persistence.Snapshot();
        legacy.commands.put("terminal-cache", commandRecord(payload, true, result));
        preferences.edit().putString("snapshot_json", new Gson().toJson(legacy)).commit();

        MemoryLedgerPersistence ledgerPersistence = new MemoryLedgerPersistence();
        MediaV11LegacyMigration.migrate(ledgerPersistence, preferences, clock());

        CommandLedger.Record record = ledgerPersistence.load().records.get("terminal-cache");
        assertEquals(CommandLedger.State.COMPLETED, record.state);
        assertEquals(CommandLedger.ResultState.DELIVERED, record.resultState);
        MediaV11ResultConformanceTest.assertConforms(MediaV11Result.fromJson(record.resultPayload));
        MediaV11Persistence.Snapshot migrated = new Gson().fromJson(
                ledgerPersistence.load().mediaV11SnapshotJson, MediaV11Persistence.Snapshot.class);
        assertTrue(migrated.outbox.isEmpty());
    }

    @Test
    public void successfulRetryReopensReconciliationWithoutPlayerDispatch() throws Exception {
        MemoryPreferences preferences = new MemoryPreferences();
        String payload = MediaV11Fixtures.request(
                "retry-no-player", "play_video", "serialized_execution", null);
        MediaV11Persistence.Snapshot legacy = new MediaV11Persistence.Snapshot();
        MediaV11Persistence.ActiveSession active = new MediaV11Persistence.ActiveSession();
        active.playCommandJson = payload;
        active.sessionId = "legacy-session";
        active.playbackState = "playing";
        legacy.activeSession = active;
        legacy.commands.put("retry-no-player", commandRecord(payload, false, null));
        preferences.edit().putString("snapshot_json", new Gson().toJson(legacy)).commit();

        MemoryLedgerPersistence ledgerPersistence = new MemoryLedgerPersistence();
        ledgerPersistence.failWrites = true;
        try {
            MediaV11LegacyMigration.migrate(ledgerPersistence, preferences, clock());
        } catch (CommandLedger.StoreException expected) {
            // The first attempt must leave no canonical import behind.
        }
        ledgerPersistence.failWrites = false;
        MediaV11LegacyMigration.migrate(ledgerPersistence, preferences, clock());

        MediaV11ServiceRuntime runtime = new MediaV11ServiceRuntime(
                new CommandLedger(ledgerPersistence), ledgerPersistence,
                new RuntimeClock(), (task, delay) -> () -> {}, () -> {},
                (phase, topic, command, event, action, state, outcome) -> {}, true, 10_000L);
        CountingBinding binding = new CountingBinding();
        runtime.reconcileAfterProcessRestart();
        runtime.attachBinding(binding);
        assertEquals(0, binding.startCount);
        MediaV11ResultConformanceTest.assertConforms(MediaV11Result.fromJson(
                ledgerPersistence.load().records.get("retry-no-player").resultPayload));
    }

    private static MediaV11Persistence.CommandRecord commandRecord(
            String payload, boolean terminal, String result) {
        MediaV11Persistence.CommandRecord command = new MediaV11Persistence.CommandRecord();
        command.commandJson = payload;
        command.payloadDigest = sha256(payload);
        command.action = "play_video";
        command.sessionId = "legacy-session";
        command.terminal = terminal;
        command.latestResultJson = result;
        return command;
    }

    private static MediaV11LegacyMigration.Clock clock() {
        return new MediaV11LegacyMigration.Clock() {
            @Override public long nowMs() { return 10_000L; }
            @Override public String nowTimestamp() { return "2026-08-02T16:00:00.000Z"; }
        };
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder();
            for (byte part : digest) encoded.append(String.format("%02x", part & 0xff));
            return encoded.toString();
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static final class RuntimeClock implements MediaV11ServiceRuntime.Clock {
        @Override public long nowMs() { return 10_000L; }
        @Override public String nowTimestamp() { return "2026-08-02T16:00:00.000Z"; }
    }

    private static final class CountingBinding implements MediaV11PlaybackBinding {
        int startCount;

        @Override public void start(MediaV11Command command, String sessionId, String leaseId,
                                    long generation, Callback callback) { startCount++; }
        @Override public void pause(String sessionId, String leaseId, long generation,
                                    Callback callback) {}
        @Override public void resume(String sessionId, String leaseId, long generation,
                                      Callback callback) {}
        @Override public void stop(String sessionId, String leaseId, long generation,
                                    Callback callback) {}
        @Override public void detach(long generation) {}
    }

    private static final class MemoryLedgerPersistence implements CommandLedger.Persistence {
        private CommandLedger.Snapshot snapshot = new CommandLedger.Snapshot();

        @Override public CommandLedger.Snapshot load() { return snapshot.copy(); }
        @Override public boolean save(CommandLedger.Snapshot next) {
            if (failWrites) return false;
            snapshot = next.copy();
            return true;
        }

        boolean failWrites;

        void seed(CommandLedger.Snapshot next) { snapshot = next.copy(); }
    }

    private static final class MemoryPreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();

        @Override public Map<String, ?> getAll() { return values; }
        @Override public String getString(String key, String defValue) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : defValue;
        }
        @Override public Set<String> getStringSet(String key, Set<String> defValue) {
            Object value = values.get(key);
            return value instanceof Set ? (Set<String>) value : defValue;
        }
        @Override public int getInt(String key, int defValue) { return defValue; }
        @Override public long getLong(String key, long defValue) { return defValue; }
        @Override public float getFloat(String key, float defValue) { return defValue; }
        @Override public boolean getBoolean(String key, boolean defValue) { return defValue; }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public Editor edit() { return new Editor() {
            @Override public Editor putString(String key, String value) {
                values.put(key, value); return this;
            }
            @Override public Editor putStringSet(String key, Set<String> value) {
                values.put(key, value); return this;
            }
            @Override public Editor putInt(String key, int value) { return this; }
            @Override public Editor putLong(String key, long value) { return this; }
            @Override public Editor putFloat(String key, float value) { return this; }
            @Override public Editor putBoolean(String key, boolean value) { return this; }
            @Override public Editor remove(String key) { values.remove(key); return this; }
            @Override public Editor clear() { values.clear(); return this; }
            @Override public boolean commit() { return true; }
            @Override public void apply() {}
        }; }
        @Override public void registerOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {}
        @Override public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {}
    }
}
