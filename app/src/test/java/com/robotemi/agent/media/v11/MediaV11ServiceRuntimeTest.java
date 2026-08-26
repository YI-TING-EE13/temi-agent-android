package com.robotemi.agent.media.v11;

import com.robotemi.agent.command.CommandLedger;
import com.google.gson.Gson;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Deterministic service-runtime lifecycle and process-recovery coverage. */
public class MediaV11ServiceRuntimeTest {
    @Test
    public void detachedPlayTimesOutToSchemaValidPendingFailure() {
        Store store = new Store();
        TestClock clock = new TestClock();
        TestScheduler scheduler = new TestScheduler(clock);
        MediaV11ServiceRuntime runtime = runtime(store, clock, scheduler);

        assertEquals(true, runtime.ingest(
                MediaV11Fixtures.request("runtime-timeout", "play_video",
                        "serialized_execution", null), "temi-01", "endpoint"));
        assertEquals("WAITING_FOR_MEDIA_ACTIVITY",
                store.record("runtime-timeout").mediaState);

        clock.now += MediaV11ServiceRuntime.DEFAULT_ATTACH_DEADLINE_MS;
        scheduler.runDue();

        CommandLedger.Record record = store.record("runtime-timeout");
        assertEquals(CommandLedger.State.RESULT_PENDING, record.state);
        assertEquals(CommandLedger.ResultState.PENDING, record.resultState);
        assertNotNull(record.resultPayload);
        com.google.gson.JsonObject result =
                com.google.gson.JsonParser.parseString(record.resultPayload).getAsJsonObject();
        assertEquals("1.1", result.get("schema_version").getAsString());
        assertEquals("failed", result.get("status").getAsString());
        assertEquals("INTERNAL_ERROR", result.get("error_code").getAsString());
        assertEquals("media_activity_attach_timeout",
                result.get("error_message").getAsString());
        MediaV11ResultConformanceTest.assertConforms(
                MediaV11Result.fromJson(record.resultPayload));
    }

    @Test
    public void processRestartFailsClosedWithoutReplayingDispatch() {
        Store store = new Store();
        TestClock clock = new TestClock();
        TestScheduler scheduler = new TestScheduler(clock);
        MediaV11ServiceRuntime first = runtime(store, clock, scheduler);
        RecordingBinding firstBinding = new RecordingBinding();
        first.attachBinding(firstBinding);
        first.ingest(MediaV11Fixtures.request("runtime-restart", "play_video",
                "serialized_execution", null), "temi-01", "endpoint");
        assertEquals(1, firstBinding.startCount);

        MediaV11ServiceRuntime recreated = runtime(store, clock, new TestScheduler(clock));
        RecordingBinding replacement = new RecordingBinding();
        recreated.reconcileAfterProcessRestart();
        recreated.attachBinding(replacement);

        assertEquals(0, replacement.startCount);
        assertEquals(CommandLedger.State.RESULT_PENDING,
                store.record("runtime-restart").state);
        assertEquals(CommandLedger.ResultState.PENDING,
                store.record("runtime-restart").resultState);
        MediaV11ResultConformanceTest.assertConforms(MediaV11Result.fromJson(
                store.record("runtime-restart").resultPayload));
    }

    @Test
    public void staleBindingCallbackIsIgnoredAfterReplacement() {
        Store store = new Store();
        TestClock clock = new TestClock();
        MediaV11ServiceRuntime runtime = runtime(store, clock, new TestScheduler(clock));
        RecordingBinding first = new RecordingBinding();
        RecordingBinding second = new RecordingBinding();
        long firstGeneration = runtime.attachBinding(first);
        runtime.ingest(MediaV11Fixtures.request("runtime-stale", "play_video",
                "serialized_execution", null), "temi-01", "endpoint");
        runtime.attachBinding(second);
        runtime.detachBinding(firstGeneration);

        first.completeIfPresent();
        assertEquals(CommandLedger.State.EXECUTING,
                store.record("runtime-stale").state);
        assertEquals(0, second.startCount);
    }

    @Test
    public void remoteStopPersistsBothTerminalsBeforePublishAndRestartNeverRedispatches()
            throws Exception {
        Store store = new Store();
        TestClock clock = new TestClock();
        TestScheduler scheduler = new TestScheduler(clock);
        List<CommandLedger.Snapshot> publishSnapshots = new ArrayList<>();
        MediaV11ServiceRuntime runtime = new MediaV11ServiceRuntime(
                store.ledger, store.persistence, clock, scheduler,
                () -> publishSnapshots.add(store.persistence.snapshot()),
                (phase, topic, command, event, action, state, outcome) -> {}, true,
                MediaV11ServiceRuntime.DEFAULT_ATTACH_DEADLINE_MS);
        RecordingBinding binding = new RecordingBinding();
        runtime.attachBinding(binding);

        runtime.ingest(MediaV11Fixtures.request("remote-play", "play_video",
                "serialized_execution", null), "temi-01", "endpoint");
        binding.startedIfPresent();
        String sessionId = new Gson().fromJson(
                store.persistence.snapshot().mediaV11SnapshotJson,
                MediaV11Persistence.Snapshot.class).activeSession.sessionId;
        runtime.ingest(MediaV11Fixtures.request("remote-stop", "stop_video",
                "active_playback_control", sessionId), "temi-01", "endpoint");
        binding.controlSucceededIfPresent(MediaV11Command.Action.STOP_VIDEO);

        assertFalse(publishSnapshots.isEmpty());
        CommandLedger.Snapshot beforePublish = publishSnapshots.get(publishSnapshots.size() - 1);
        assertEquals(CommandLedger.State.RESULT_PENDING,
                beforePublish.records.get("remote-stop").state);
        assertEquals(CommandLedger.State.RESULT_PENDING,
                beforePublish.records.get("remote-play").state);
        MediaV11ResultConformanceTest.assertConforms(MediaV11Result.fromJson(
                beforePublish.records.get("remote-stop").resultPayload));
        MediaV11ResultConformanceTest.assertConforms(MediaV11Result.fromJson(
                beforePublish.records.get("remote-play").resultPayload));
        MediaV11Persistence.Snapshot mediaSnapshot = new Gson().fromJson(
                beforePublish.mediaV11SnapshotJson, MediaV11Persistence.Snapshot.class);
        boolean stopTerminalPending = false;
        boolean playCancelledPending = false;
        for (MediaV11Persistence.OutboxRecord outbox : mediaSnapshot.outbox) {
            MediaV11Result result = MediaV11Result.fromJson(outbox.payload);
            MediaV11ResultConformanceTest.assertConforms(result);
            if ("remote-stop".equals(result.getCommandId())
                    && "succeeded".equals(result.getStatus())) {
                stopTerminalPending = true;
            }
            if ("remote-play".equals(result.getCommandId())
                    && "cancelled".equals(result.getStatus())) {
                playCancelledPending = true;
            }
        }
        assertTrue(stopTerminalPending);
        assertTrue(playCancelledPending);

        MediaV11ServiceRuntime recreated = runtime(
                store, clock, new TestScheduler(clock));
        RecordingBinding replacement = new RecordingBinding();
        recreated.reconcileAfterProcessRestart();
        recreated.attachBinding(replacement);
        assertEquals(0, replacement.startCount);
        assertEquals(0, replacement.stopCount);
    }

    @Test
    public void terminalCachedReplayIsSchemaValidAndDoesNotDispatchAgain() {
        Store store = new Store();
        TestClock clock = new TestClock();
        TestScheduler scheduler = new TestScheduler(clock);
        MediaV11ServiceRuntime runtime = runtime(store, clock, scheduler);
        RecordingBinding binding = new RecordingBinding();
        runtime.attachBinding(binding);
        String payload = MediaV11Fixtures.request("runtime-replay", "play_video",
                "serialized_execution", null);

        runtime.ingest(payload, "temi-01", "endpoint");
        binding.completeIfPresent();
        int dispatchCount = binding.startCount;
        runtime.ingest(payload, "temi-01", "endpoint");

        boolean cachedReplayFound = false;
        for (MediaV11Persistence.OutboxRecord outbox : runtime.pendingOutbox()) {
            MediaV11Result result = MediaV11Result.fromJson(outbox.payload);
            if ("cached_replay".equals(result.getDelivery())) {
                cachedReplayFound = true;
                MediaV11ResultConformanceTest.assertConforms(result);
            }
        }
        assertTrue(cachedReplayFound);
        assertEquals(dispatchCount, binding.startCount);
    }

    private static MediaV11ServiceRuntime runtime(
            Store store, TestClock clock, TestScheduler scheduler) {
        return new MediaV11ServiceRuntime(
                store.ledger,
                store.persistence,
                clock,
                scheduler,
                () -> {},
                (phase, topic, command, event, action, state, outcome) -> {},
                true,
                MediaV11ServiceRuntime.DEFAULT_ATTACH_DEADLINE_MS);
    }

    private static final class Store {
        final MemoryPersistence persistence = new MemoryPersistence();
        final CommandLedger ledger = new CommandLedger(persistence);

        CommandLedger.Record record(String id) {
            return persistence.record(id);
        }
    }

    private static final class MemoryPersistence implements CommandLedger.Persistence {
        private CommandLedger.Snapshot snapshot = new CommandLedger.Snapshot();

        @Override
        public CommandLedger.Snapshot load() {
            return snapshot.copy();
        }

        @Override
        public boolean save(CommandLedger.Snapshot next) {
            snapshot = next.copy();
            return true;
        }

        CommandLedger.Record record(String id) {
            return snapshot.records.get(id) == null ? null : snapshot.records.get(id).copy();
        }

        CommandLedger.Snapshot snapshot() { return snapshot.copy(); }
    }

    private static final class TestClock implements MediaV11ServiceRuntime.Clock {
        long now = 1_000L;

        @Override public long nowMs() { return now; }
        @Override public String nowTimestamp() { return "2026-08-02T16:00:00.000Z"; }
    }

    private static final class TestScheduler implements MediaV11ServiceRuntime.Scheduler {
        private final TestClock clock;
        private final List<Task> tasks = new ArrayList<>();

        TestScheduler(TestClock clock) { this.clock = clock; }

        @Override
        public MediaV11ServiceRuntime.Cancellable schedule(Runnable task, long delayMs) {
            Task entry = new Task(task, clock.now + delayMs);
            tasks.add(entry);
            return () -> tasks.remove(entry);
        }

        void runDue() {
            List<Task> due = new ArrayList<>();
            for (Task task : tasks) if (task.deadline <= clock.now) due.add(task);
            tasks.removeAll(due);
            for (Task task : due) task.task.run();
        }
    }

    private static final class Task {
        final Runnable task;
        final long deadline;

        Task(Runnable task, long deadline) {
            this.task = task;
            this.deadline = deadline;
        }
    }

    private static final class RecordingBinding implements MediaV11PlaybackBinding {
        int startCount;
        int stopCount;
        Callback callback;
        Callback controlCallback;
        long generation;
        String leaseId;
        String sessionId;

        @Override
        public void start(MediaV11Command command, String sessionId, String leaseId,
                          long generation, Callback callback) {
            startCount++;
            this.callback = callback;
            this.generation = generation;
            this.leaseId = leaseId;
            this.sessionId = sessionId;
        }

        @Override public void pause(String s, String l, long g, Callback c) {}
        @Override public void resume(String s, String l, long g, Callback c) {}
        @Override public void stop(String s, String l, long g, Callback c) {
            stopCount++;
            controlCallback = c;
            generation = g;
            leaseId = l;
            sessionId = s;
        }
        @Override public void detach(long generation) { callback = null; }

        void completeIfPresent() {
            if (callback != null) callback.onCompleted(generation, leaseId, sessionId);
        }

        void startedIfPresent() {
            if (callback != null) callback.onStarted(generation, leaseId, sessionId);
        }

        void controlSucceededIfPresent(MediaV11Command.Action action) {
            if (controlCallback != null) {
                controlCallback.onControlSucceeded(generation, leaseId, sessionId, action);
            }
        }
    }
}
