package com.robotemi.agent.command;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Deterministic lifecycle and recovery coverage for the process-owned TTS runtime. */
public class CanonicalCommandRuntimeTest {
    private static final String ROBOT_ID = "temi-01";

    @Test
    public void b91c128ActivityStopReproducesExecutingWithoutTerminalResult() {
        MemoryPersistence persistence = new MemoryPersistence();
        CommandLedger ledger = new CommandLedger(persistence);
        String payload = command("evt_android_mqtt_20260801_r2_01",
                "cmd_android_mqtt_20260801_r2_01", "speak", "R2 background continuity");
        CanonicalCommandValidator.CanonicalCommand command = parse(payload);
        ledger.accept(command, payload, 1L);
        ledger.markExecuting(command.getCommandId(), 2L);

        CanonicalTtsTracker oldActivityTracker = new CanonicalTtsTracker();
        UUID requestId = UUID.randomUUID();
        oldActivityTracker.begin(requestId);
        boolean activityTtsListenerAttached = true;
        activityTtsListenerAttached = false; // MainActivity.onStop() in b91c128.
        if (activityTtsListenerAttached) oldActivityTracker.resolve(requestId, true);

        assertTrue(oldActivityTracker.isPending());
        assertEquals(CommandLedger.State.EXECUTING, persistence.record(command.getCommandId()).state);
        assertEquals(CommandLedger.ResultState.NONE,
                persistence.record(command.getCommandId()).resultState);
    }

    @Test
    public void backgroundCommandCompletesAndPersistsBeforePublish() {
        MemoryPersistence persistence = new MemoryPersistence();
        CommandLedger ledger = new CommandLedger(persistence);
        CanonicalCommandValidator.CanonicalCommand command = prepareExecuting(
                ledger, persistence, "cmd-r1", "speak", "background command");
        FakeScheduler scheduler = new FakeScheduler();
        CanonicalCommandRuntime runtime = new CanonicalCommandRuntime(scheduler, 100L);
        List<CanonicalCommandRuntime.Resolution> resolutions = new ArrayList<>();
        runtime.attachListener(resolutions::add);

        assertTrue(runtime.beginTts(UUID.randomUUID(), command.getCommandId(), "action-cmd-r1"));
        runtime.onTtsStatusChanged(runtime.pendingRequestId(), true);

        CanonicalCommandRuntime.Resolution resolution = resolutions.get(0);
        assertEquals("completed", resolution.getStatus());
        String result = resultPayload(command, resolution, null);
        assertTrue(ledger.markResultPending(command.getCommandId(), result,
                CommandLedger.State.COMPLETED, 3L));
        assertEquals(CommandLedger.State.RESULT_PENDING,
                persistence.record(command.getCommandId()).state);
        assertTrue(ledger.markResultDelivered(command.getCommandId(), 4L));
        assertEquals(CommandLedger.State.COMPLETED,
                persistence.record(command.getCommandId()).state);
        assertEquals(CommandLedger.ResultState.DELIVERED,
                persistence.record(command.getCommandId()).resultState);
    }

    @Test
    public void missingCallbackTimesOutToFailedTerminalResult() {
        MemoryPersistence persistence = new MemoryPersistence();
        CommandLedger ledger = new CommandLedger(persistence);
        CanonicalCommandValidator.CanonicalCommand command = prepareExecuting(
                ledger, persistence, "cmd-timeout", "speak", "missing callback");
        FakeScheduler scheduler = new FakeScheduler();
        CanonicalCommandRuntime runtime = new CanonicalCommandRuntime(scheduler, 10L);
        List<CanonicalCommandRuntime.Resolution> resolutions = new ArrayList<>();
        runtime.attachListener(resolutions::add);

        assertTrue(runtime.beginTts(UUID.randomUUID(), command.getCommandId(), "action-timeout"));
        scheduler.runAll();

        assertEquals(1, resolutions.size());
        CanonicalCommandRuntime.Resolution resolution = resolutions.get(0);
        assertEquals("failed", resolution.getStatus());
        assertEquals("tts_callback_timeout", resolution.getError());
        assertTrue(resolution.isTimeout());
        assertTrue(ledger.markResultPending(command.getCommandId(),
                resultPayload(command, resolution, resolution.getError()),
                CommandLedger.State.FAILED, 11L));
        assertEquals(CommandLedger.State.RESULT_PENDING,
                persistence.record(command.getCommandId()).state);
    }

    @Test
    public void duplicateTerminalCallbackProducesOneResolution() {
        FakeScheduler scheduler = new FakeScheduler();
        CanonicalCommandRuntime runtime = new CanonicalCommandRuntime(scheduler, 100L);
        List<CanonicalCommandRuntime.Resolution> resolutions = new ArrayList<>();
        runtime.attachListener(resolutions::add);
        UUID requestId = UUID.randomUUID();

        assertTrue(runtime.beginTts(requestId, "cmd-duplicate-callback", "action-1"));
        runtime.onTtsStatusChanged(requestId, true);
        runtime.onTtsStatusChanged(requestId, false);
        scheduler.runAll();

        assertEquals(1, resolutions.size());
        assertEquals("completed", resolutions.get(0).getStatus());
    }

    @Test
    public void duplicateCommandWhileExecutingCannotDispatchSecondTts() {
        MemoryPersistence persistence = new MemoryPersistence();
        CommandLedger ledger = new CommandLedger(persistence);
        String payload = command("event-duplicate", "cmd-duplicate", "speak", "once");
        CanonicalCommandValidator.CanonicalCommand command = parse(payload);
        assertEquals(CommandLedger.AcceptState.FIRST_DELIVERY,
                ledger.accept(command, payload, 1L).state());
        ledger.markExecuting(command.getCommandId(), 2L);
        assertEquals(CommandLedger.AcceptState.DUPLICATE_PENDING,
                ledger.accept(command, payload, 3L).state());

        FakeScheduler scheduler = new FakeScheduler();
        CanonicalCommandRuntime runtime = new CanonicalCommandRuntime(scheduler, 100L);
        assertTrue(runtime.beginTts(UUID.randomUUID(), command.getCommandId(), "action-duplicate"));
        assertFalse(runtime.beginTts(UUID.randomUUID(), command.getCommandId(), "action-duplicate"));
    }

    @Test
    public void detachingActivityObserverDoesNotDropProcessOwnedResolution() {
        FakeScheduler scheduler = new FakeScheduler();
        CanonicalCommandRuntime runtime = new CanonicalCommandRuntime(scheduler, 100L);
        List<CanonicalCommandRuntime.Resolution> resolutions = new ArrayList<>();
        runtime.attachListener(resolutions::add);
        UUID requestId = UUID.randomUUID();
        assertTrue(runtime.beginTts(requestId, "cmd-detach", "action-detach"));

        runtime.detachListener();
        runtime.onTtsStatusChanged(requestId, true);
        assertTrue(resolutions.isEmpty());
        assertFalse(runtime.isPending());

        runtime.attachListener(resolutions::add);
        assertEquals(1, resolutions.size());
        assertEquals("completed", resolutions.get(0).getStatus());
    }

    @Test
    public void detachedObserverStillPersistsTerminalResultBeforeReplay() {
        FakeScheduler scheduler = new FakeScheduler();
        List<String> order = new ArrayList<>();
        CanonicalCommandRuntime runtime = new CanonicalCommandRuntime(
                scheduler, 100L, resolution -> order.add("persisted:" + resolution.getCommandId()));
        List<CanonicalCommandRuntime.Resolution> resolutions = new ArrayList<>();
        runtime.attachListener(resolution -> {
            order.add("observed");
            resolutions.add(resolution);
        });
        UUID requestId = UUID.randomUUID();
        assertTrue(runtime.beginTts(requestId, "cmd-detached-persist", "event-detached",
                "temi-01", "action-detached", "speak", true));

        runtime.detachListener();
        runtime.onTtsStatusChanged(requestId, true);
        assertEquals(1, order.size());
        assertEquals("persisted:cmd-detached-persist", order.get(0));
        assertTrue(resolutions.isEmpty());

        runtime.attachListener(resolution -> {
            order.add("replayed");
            resolutions.add(resolution);
        });
        assertEquals(2, order.size());
        assertEquals("replayed", order.get(1));
        assertEquals(1, resolutions.size());
    }

    @Test
    public void pendingResultSurvivesPublishFailureAndIsReplayable() {
        MemoryPersistence persistence = new MemoryPersistence();
        CommandLedger ledger = new CommandLedger(persistence);
        CanonicalCommandValidator.CanonicalCommand command = prepareExecuting(
                ledger, persistence, "cmd-replay", "speak", "replay result");
        String result = "{\"command_id\":\"cmd-replay\",\"status\":\"success\"}";
        assertTrue(ledger.markResultPending(command.getCommandId(), result,
                CommandLedger.State.COMPLETED, 3L));
        assertEquals(1, ledger.pendingResults().size());
        // A failed publish does not mutate the ledger; the next connection replays this payload.
        assertEquals(result, ledger.pendingResults().get(0).resultPayload);
        assertTrue(ledger.markResultDelivered(command.getCommandId(), 4L));
        assertTrue(ledger.pendingResults().isEmpty());
    }

    @Test
    public void processRestartFromExecutingIsFailClosed() {
        MemoryPersistence persistence = new MemoryPersistence();
        CommandLedger ledger = new CommandLedger(persistence);
        CanonicalCommandValidator.CanonicalCommand command = prepareExecuting(
                ledger, persistence, "cmd-restart", "speak", "never replay hardware");
        List<CommandLedger.RecoveryItem> recovered = ledger.recover(5L);
        assertEquals(1, recovered.size());
        assertEquals(CommandLedger.RecoveryState.EXECUTION_UNKNOWN,
                recovered.get(0).state());
        assertEquals(CommandLedger.State.EXECUTING,
                persistence.record(command.getCommandId()).state);
    }

    private static CanonicalCommandValidator.CanonicalCommand prepareExecuting(
            CommandLedger ledger,
            MemoryPersistence persistence,
            String commandId,
            String type,
            String text) {
        String payload = command("event-" + commandId, commandId, type, text);
        CanonicalCommandValidator.CanonicalCommand command = parse(payload);
        assertEquals(CommandLedger.AcceptState.FIRST_DELIVERY,
                ledger.accept(command, payload, 1L).state());
        assertTrue(ledger.markExecuting(command.getCommandId(), 2L));
        assertNotNull(persistence.record(command.getCommandId()));
        return command;
    }

    private static String resultPayload(
            CanonicalCommandValidator.CanonicalCommand command,
            CanonicalCommandRuntime.Resolution resolution,
            String error) {
        return "{\"command_id\":\"" + command.getCommandId()
                + "\",\"event_id\":\"" + command.getEventId()
                + "\",\"status\":\"" + ("completed".equals(resolution.getStatus())
                ? "success" : "failed") + "\",\"error\":\""
                + (error == null ? "" : error) + "\"}";
    }

    private static CanonicalCommandValidator.CanonicalCommand parse(String payload) {
        try {
            return CanonicalCommandValidator.validate(payload, ROBOT_ID);
        } catch (CanonicalCommandValidator.ValidationException e) {
            throw new AssertionError(e);
        }
    }

    private static String command(String eventId, String commandId,
                                  String type, String text) {
        return "{\"schema_version\":\"1.0\",\"command_id\":\"" + commandId
                + "\",\"event_id\":\"" + eventId + "\",\"robot_id\":\""
                + ROBOT_ID + "\",\"actions\":[{\"action_id\":\"action-"
                + commandId + "\",\"type\":\"" + type
                + "\",\"text\":\"" + text + "\",\"language\":\"zh-TW\"}]}";
    }

    private static final class FakeScheduler implements CanonicalCommandRuntime.Scheduler {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public CanonicalCommandRuntime.Cancellable schedule(Runnable task, long delayMs) {
            tasks.add(task);
            return () -> tasks.remove(task);
        }

        private void runAll() {
            List<Runnable> pending = new ArrayList<>(tasks);
            tasks.clear();
            for (Runnable task : pending) task.run();
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

        private CommandLedger.Record record(String commandId) {
            return snapshot.records.get(commandId);
        }
    }
}
