package com.robotemi.agent.media.v11;

import androidx.annotation.Nullable;

import com.robotemi.agent.command.CommandLedger;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Process-owned Media v1.1 ingress and execution runtime.
 *
 * <p>The runtime has no MQTT client. MqttLifecycleService supplies the single broker and
 * result publisher, while MainActivity supplies only a transient playback binding.</p>
 */
public final class MediaV11ServiceRuntime {
    public static final long DEFAULT_ATTACH_DEADLINE_MS = 10_000L;
    public static final long DEFAULT_DISPATCH_DEADLINE_MS = 30_000L;

    public interface Clock {
        long nowMs();
        String nowTimestamp();
    }

    public interface Cancellable {
        void cancel();
    }

    public interface Scheduler {
        Cancellable schedule(Runnable task, long delayMs);
    }

    public interface ResultSink {
        void onMediaResultsPending();
    }

    public interface Diagnostics {
        void record(String phase, String topicClass, @Nullable String commandId,
                    @Nullable String eventId, @Nullable String actionId,
                    String state, String outcome);
    }

    private final CommandLedger ledger;
    private final MediaV11Coordinator coordinator;
    private final Clock clock;
    private final Scheduler scheduler;
    private final ResultSink resultSink;
    private final Diagnostics diagnostics;
    private final boolean enabled;
    private final long attachDeadlineMs;
    private final long dispatchDeadlineMs;
    private final Map<String, Dispatch> dispatches = new HashMap<>();

    @Nullable private MediaV11PlaybackBinding binding;
    private long bindingGeneration;
    @Nullable private Cancellable nextWaitingTimeout;

    public MediaV11ServiceRuntime(
            CommandLedger ledger,
            CommandLedger.Persistence ledgerPersistence,
            Clock clock,
            Scheduler scheduler,
            ResultSink resultSink,
            Diagnostics diagnostics,
            boolean enabled,
            long attachDeadlineMs) {
        if (ledger == null || ledgerPersistence == null || clock == null || scheduler == null
                || resultSink == null || diagnostics == null) {
            throw new IllegalArgumentException("media_runtime_dependency_missing");
        }
        this.ledger = ledger;
        this.clock = clock;
        this.scheduler = scheduler;
        this.resultSink = resultSink;
        this.diagnostics = diagnostics;
        this.enabled = enabled;
        this.attachDeadlineMs = attachDeadlineMs < 1
                ? DEFAULT_ATTACH_DEADLINE_MS : attachDeadlineMs;
        this.dispatchDeadlineMs = DEFAULT_DISPATCH_DEADLINE_MS;
        this.coordinator = new MediaV11Coordinator(
                new CommandLedgerMediaV11Persistence(ledgerPersistence),
                new MediaV11Coordinator.Clock() {
                    @Override public long nowMs() { return clock.nowMs(); }
                    @Override public String nowTimestamp() { return clock.nowTimestamp(); }
                });
    }

    /** Consumes a v1.1 payload; non-v1.1 payloads are not consumed. */
    public synchronized boolean ingest(
            String payload, String robotId, @Nullable String endpointFingerprint) {
        if (!MediaV11Parser.declaresMediaV11(payload)) return false;

        final MediaV11Command command;
        try {
            command = MediaV11Parser.parse(payload, robotId);
        } catch (MediaV11Parser.ValidationException error) {
            MediaV11Command correlated = MediaV11Parser.correlateForRejection(payload, robotId);
            diagnostics.record(
                    "media_ingress", "command_request",
                    correlated == null ? null : correlated.getCommandId(),
                    correlated == null ? null : correlated.getEventId(), null,
                    "REJECTED", error.getErrorCode());
            if (correlated != null) {
                // Keep a correlated rejection in the same generic ledger before the
                // coordinator creates its schema-valid terminal result.
                ledger.acceptMedia(
                        correlated.getCommandId(), correlated.getRequestId(),
                        correlated.getRobotId(), correlated.getAction().wireValue(),
                        correlated.getVideoId(), payload, clock.nowMs());
                handleOutcome(correlated, coordinator.rejectInvalid(
                        correlated, error.getErrorCode(), error.getReason(), endpointFingerprint));
            }
            return true;
        }

        CommandLedger.AcceptResult accepted = ledger.acceptMedia(
                command.getCommandId(), command.getRequestId(), command.getRobotId(),
                command.getAction().wireValue(), command.getVideoId(), payload, clock.nowMs());
        switch (accepted.state()) {
            case FIRST_DELIVERY:
                diagnostics.record(
                        "media_ingress", "command_request", command.getCommandId(),
                        command.getEventId(), null, "RECEIVED", "accepted");
                handleOutcome(command, coordinator.submit(command, enabled, endpointFingerprint));
                return true;
            case DUPLICATE_CACHED_RESULT:
                diagnostics.record(
                        "media_ingress", "command_request", command.getCommandId(),
                        command.getEventId(), null, "CACHED_RESULT", "duplicate_replay");
                handleOutcome(command, coordinator.submitDuplicateIfKnown(
                        command, endpointFingerprint));
                return true;
            case DUPLICATE_PENDING:
                diagnostics.record(
                        "media_ingress", "command_request", command.getCommandId(),
                        command.getEventId(), null, "PENDING", "duplicate_suppressed");
                return true;
            case PAYLOAD_CONFLICT:
                handleOutcome(command, coordinator.rejectInvalid(
                        command, "MEDIA_CONTROL_CONFLICT", "command_id_payload_conflict",
                        endpointFingerprint));
                return true;
            case CAPACITY_REJECTED:
            case STORE_ERROR:
            default:
                throw new IllegalStateException("media_command_ledger_accept_failed");
        }
    }

    public synchronized long attachBinding(MediaV11PlaybackBinding nextBinding) {
        if (nextBinding == null) throw new IllegalArgumentException("media_binding_required");
        bindingGeneration++;
        if (binding != null) binding.detach(bindingGeneration - 1);
        binding = nextBinding;
        dispatchWaitingCommands();
        return bindingGeneration;
    }

    public synchronized void detachBinding(long generation) {
        if (generation != bindingGeneration || binding == null) return;
        binding.detach(generation);
        binding = null;
        bindingGeneration++;
    }

    public synchronized void reconcileAfterProcessRestart() {
        for (CommandLedger.Record record : ledger.records()) {
            if (record.mediaState == null) continue;
            if ("WAITING_FOR_MEDIA_ACTIVITY".equals(record.mediaState)) {
                if (record.mediaDeadlineAtMs <= clock.nowMs()) {
                    timeoutWaiting(record);
                }
                continue;
            }
            if ("DISPATCHING".equals(record.mediaState)
                    || "PLAYING".equals(record.mediaState)
                    || "PAUSED".equals(record.mediaState)
                    || "CONTROL_PENDING".equals(record.mediaState)) {
                reconcileAmbiguous(record);
            }
        }
        dispatchWaitingCommands();
    }

    public synchronized List<MediaV11Persistence.OutboxRecord> pendingOutbox() {
        return coordinator.pendingOutbox();
    }

    /** Acknowledge a service-published media result and update the generic ledger if terminal. */
    public synchronized boolean acknowledgeOutbox(String outboxId, String payload) {
        boolean acknowledged = coordinator.acknowledgeOutbox(outboxId);
        if (acknowledged && isTerminal(payload)) {
            String commandId = commandId(payload);
            if (commandId != null) ledger.markResultDelivered(commandId, clock.nowMs());
        }
        return acknowledged;
    }

    public synchronized int bindingGenerationForTest() {
        return (int) bindingGeneration;
    }

    private void handleOutcome(MediaV11Command command, MediaV11Coordinator.Outcome outcome) {
        if (outcome == null) throw new IllegalStateException("media_outcome_missing");
        for (MediaV11Result result : outcome.getResults()) {
            if (!result.isTerminal()) continue;
            CommandLedger.State terminalState = "failed".equals(result.getStatus())
                    || "rejected".equals(result.getStatus())
                    ? CommandLedger.State.FAILED : CommandLedger.State.COMPLETED;
            if (!ledger.markResultPending(
                    result.getCommandId(), result.toJson(), terminalState, clock.nowMs())) {
                diagnostics.record(
                        "media_terminal", "command_result", result.getCommandId(),
                        null, null, "RESULT_PENDING", "duplicate_or_missing");
            } else {
                diagnostics.record(
                        "media_terminal", "command_result", result.getCommandId(),
                        result.getEventId(), result.getAction(),
                        "RESULT_PENDING", "persisted");
            }
        }

        if (outcome.getEffect() != MediaV11Coordinator.Effect.NONE
                && !hasTerminalResult(outcome.getResults())) {
            long deadline = clock.nowMs() + attachDeadlineMs;
            ledger.markMediaWaiting(
                    command.getCommandId(), outcome.getSessionId(), deadline, clock.nowMs());
            if (binding == null) scheduleWaitingTimeout();
            else dispatchRecord(ledgerRecord(command.getCommandId()));
        }
        resultSink.onMediaResultsPending();
    }

    private void dispatchWaitingCommands() {
        for (CommandLedger.Record record : ledger.records()) {
            if ("WAITING_FOR_MEDIA_ACTIVITY".equals(record.mediaState)) {
                if (record.mediaDeadlineAtMs <= clock.nowMs()) timeoutWaiting(record);
                else dispatchRecord(record);
            }
        }
        scheduleWaitingTimeout();
    }

    private void dispatchRecord(CommandLedger.Record record) {
        if (binding == null || record == null
                || !"WAITING_FOR_MEDIA_ACTIVITY".equals(record.mediaState)) return;
        MediaV11Command command = parseStoredCommand(record.mediaCommandJson);
        if (command == null) {
            failStoredCommand(record, "media_command_reconstruction_failed");
            return;
        }
        String leaseId = UUID.randomUUID().toString();
        long generation = bindingGeneration;
        if (!ledger.markMediaExecuting(
                command.getCommandId(), leaseId, generation, clock.nowMs())) {
            return;
        }
        Dispatch dispatch = new Dispatch(command, record.mediaSessionId, leaseId, generation);
        // Register before scheduling or invoking the binding. Test bindings and a real
        // Activity adapter may complete synchronously; callbacks must see the lease.
        dispatches.put(command.getCommandId(), dispatch);
        dispatch.timeout = scheduler.schedule(
                () -> timeoutDispatch(command.getCommandId(), leaseId), dispatchDeadlineMs);
        diagnostics.record(
                "media_dispatch", "command_request", command.getCommandId(),
                command.getEventId(), null, "DISPATCHING", "lease_persisted");
        switch (command.getAction()) {
            case PLAY_VIDEO:
                binding.start(command, record.mediaSessionId, leaseId, generation, callbacks);
                break;
            case PAUSE_VIDEO:
                ledger.markMediaState(command.getCommandId(), "CONTROL_PENDING", clock.nowMs());
                binding.pause(record.mediaSessionId, leaseId, generation, callbacks);
                break;
            case RESUME_VIDEO:
                ledger.markMediaState(command.getCommandId(), "CONTROL_PENDING", clock.nowMs());
                binding.resume(record.mediaSessionId, leaseId, generation, callbacks);
                break;
            case STOP_VIDEO:
                ledger.markMediaState(command.getCommandId(), "CONTROL_PENDING", clock.nowMs());
                binding.stop(record.mediaSessionId, leaseId, generation, callbacks);
                break;
            default:
                throw new IllegalStateException("unsupported_media_effect");
        }
    }

    private final MediaV11PlaybackBinding.Callback callbacks =
            new MediaV11PlaybackBinding.Callback() {
                @Override public void onStarted(long generation, String leaseId, String sessionId) {
                    MediaV11ServiceRuntime.this.onStarted(generation, leaseId, sessionId);
                }
                @Override public void onCompleted(long generation, String leaseId, String sessionId) {
                    MediaV11ServiceRuntime.this.onCompleted(generation, leaseId, sessionId);
                }
                @Override public void onCancelled(long generation, String leaseId, String sessionId) {
                    MediaV11ServiceRuntime.this.onCancelled(generation, leaseId, sessionId);
                }
                @Override public void onFailed(long generation, String leaseId, String sessionId,
                                               String message) {
                    MediaV11ServiceRuntime.this.onFailed(generation, leaseId, sessionId, message);
                }
                @Override public void onControlSucceeded(long generation, String leaseId,
                                                         String sessionId,
                                                         MediaV11Command.Action action) {
                    MediaV11ServiceRuntime.this.onControlSucceeded(
                            generation, leaseId, sessionId, action);
                }
                @Override public void onControlFailed(long generation, String leaseId,
                                                       String sessionId,
                                                       MediaV11Command.Action action,
                                                       String message) {
                    MediaV11ServiceRuntime.this.onControlFailed(
                            generation, leaseId, sessionId, action, message);
                }
            };

    private void onStarted(long generation, String leaseId, String sessionId) {
        Dispatch dispatch = currentDispatch(leaseId, generation, sessionId, true);
        if (dispatch == null) return;
        stopTimeout(dispatch);
        ledger.markMediaState(dispatch.command.getCommandId(), "PLAYING", clock.nowMs());
        handleResults(dispatch.command, coordinator.markStarted(sessionId));
    }

    private void onCompleted(long generation, String leaseId, String sessionId) {
        Dispatch dispatch = currentDispatch(leaseId, generation, sessionId, true);
        if (dispatch == null) return;
        cancel(dispatch);
        handleResults(dispatch.command, coordinator.complete(sessionId));
    }

    private void onCancelled(long generation, String leaseId, String sessionId) {
        Dispatch dispatch = currentDispatch(leaseId, generation, sessionId, true);
        if (dispatch == null) return;
        cancel(dispatch);
        handleResults(dispatch.command, coordinator.localUserStop());
    }

    private void onFailed(long generation, String leaseId, String sessionId, String message) {
        Dispatch dispatch = currentDispatch(leaseId, generation, sessionId, true);
        if (dispatch == null) return;
        cancel(dispatch);
        handleResults(dispatch.command, coordinator.fail(
                sessionId, message == null ? "media_playback_failed" : message));
    }

    private void onControlSucceeded(
            long generation, String leaseId, String sessionId, MediaV11Command.Action action) {
        Dispatch dispatch = currentDispatch(leaseId, generation, sessionId, true);
        if (dispatch == null) return;
        cancel(dispatch);
        handleResults(dispatch.command, coordinator.controlSucceeded(dispatch.command));
    }

    private void onControlFailed(
            long generation, String leaseId, String sessionId,
            MediaV11Command.Action action, String message) {
        Dispatch dispatch = currentDispatch(leaseId, generation, sessionId, true);
        if (dispatch == null) return;
        cancel(dispatch);
        handleResults(dispatch.command, coordinator.controlFailed(
                dispatch.command, message == null ? "media_control_failed" : message));
    }

    private void handleResults(MediaV11Command command, List<MediaV11Result> results) {
        for (MediaV11Result result : results) {
            if (!result.isTerminal()) continue;
            CommandLedger.State terminalState = "failed".equals(result.getStatus())
                    || "rejected".equals(result.getStatus())
                    ? CommandLedger.State.FAILED : CommandLedger.State.COMPLETED;
            if (ledger.markResultPending(
                    result.getCommandId(), result.toJson(), terminalState, clock.nowMs())) {
                diagnostics.record(
                        "media_terminal", "command_result", result.getCommandId(),
                        result.getEventId(), result.getAction(),
                        "RESULT_PENDING", "persisted");
            }
        }
        resultSink.onMediaResultsPending();
    }

    private Dispatch currentDispatch(
            String leaseId, long generation, String sessionId, boolean callback) {
        for (Dispatch dispatch : dispatches.values()) {
            if (dispatch.leaseId.equals(leaseId) && dispatch.generation == generation
                    && (dispatch.sessionId == null
                    ? sessionId == null : dispatch.sessionId.equals(sessionId))
                    && (!callback || generation == bindingGeneration)) return dispatch;
        }
        return null;
    }

    private void timeoutWaiting(CommandLedger.Record record) {
        if (record == null || !"WAITING_FOR_MEDIA_ACTIVITY".equals(record.mediaState)) return;
        MediaV11Command command = parseStoredCommand(record.mediaCommandJson);
        if (command == null) {
            failStoredCommand(record, "media_command_reconstruction_failed");
            return;
        }
        List<MediaV11Result> results;
        if (command.getAction() == MediaV11Command.Action.PLAY_VIDEO) {
            results = coordinator.fail(record.mediaSessionId, "media_activity_attach_timeout");
        } else {
            results = coordinator.controlFailed(command, "media_activity_attach_timeout");
        }
        handleResults(command, results);
    }

    private void timeoutDispatch(String commandId, String leaseId) {
        Dispatch dispatch = dispatches.get(commandId);
        if (dispatch == null || !dispatch.leaseId.equals(leaseId)) return;
        cancel(dispatch);
        if (dispatch.command.getAction() == MediaV11Command.Action.PLAY_VIDEO) {
            handleResults(dispatch.command, coordinator.fail(
                    dispatch.sessionId, "media_player_timeout"));
        } else {
            handleResults(dispatch.command, coordinator.controlFailed(
                    dispatch.command, "media_control_timeout"));
        }
    }

    private void reconcileAmbiguous(CommandLedger.Record record) {
        MediaV11Command command = parseStoredCommand(record.mediaCommandJson);
        if (command == null) {
            failStoredCommand(record, "media_command_reconstruction_failed");
            return;
        }
        if (command.getAction() == MediaV11Command.Action.PLAY_VIDEO) {
            handleResults(command, coordinator.reconcileAfterProcessRestart());
        } else {
            handleResults(command, coordinator.controlFailed(
                    command, "media_process_restart_ambiguous"));
        }
    }

    private void failStoredCommand(CommandLedger.Record record, String message) {
        if (record == null || record.resultState == CommandLedger.ResultState.DELIVERED) return;
        MediaV11Command command = parseStoredCommand(record.mediaCommandJson);
        if (command == null) return;
        handleResults(command, coordinator.controlFailed(command, message));
    }

    private void scheduleWaitingTimeout() {
        if (nextWaitingTimeout != null) nextWaitingTimeout.cancel();
        long nearest = Long.MAX_VALUE;
        for (CommandLedger.Record record : ledger.records()) {
            if ("WAITING_FOR_MEDIA_ACTIVITY".equals(record.mediaState)) {
                nearest = Math.min(nearest, record.mediaDeadlineAtMs);
            }
        }
        if (nearest == Long.MAX_VALUE) {
            nextWaitingTimeout = null;
            return;
        }
        nextWaitingTimeout = scheduler.schedule(() -> {
            synchronized (MediaV11ServiceRuntime.this) {
                nextWaitingTimeout = null;
                for (CommandLedger.Record record : ledger.records()) {
                    if ("WAITING_FOR_MEDIA_ACTIVITY".equals(record.mediaState)
                            && record.mediaDeadlineAtMs <= clock.nowMs()) timeoutWaiting(record);
                }
                scheduleWaitingTimeout();
            }
        }, Math.max(1L, nearest - clock.nowMs()));
    }

    @Nullable
    private CommandLedger.Record ledgerRecord(String commandId) {
        for (CommandLedger.Record record : ledger.records()) {
            if (commandId.equals(record.commandId)) return record;
        }
        return null;
    }

    @Nullable
    private static MediaV11Command parseStoredCommand(String payload) {
        if (payload == null) return null;
        try {
            String robotId = JsonParser.parseString(payload).getAsJsonObject()
                    .get("robot_id").getAsString();
            return MediaV11Parser.parse(payload, robotId);
        } catch (RuntimeException | MediaV11Parser.ValidationException ignored) {
            return null;
        }
    }

    private boolean hasTerminalResult(List<MediaV11Result> results) {
        for (MediaV11Result result : results) if (result.isTerminal()) return true;
        return false;
    }

    private static boolean isTerminal(String payload) {
        try {
            JsonObject object = JsonParser.parseString(payload).getAsJsonObject();
            return object.has("terminal") && object.get("terminal").getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Nullable
    private static String commandId(String payload) {
        try {
            JsonObject object = JsonParser.parseString(payload).getAsJsonObject();
            return object.has("command_id") ? object.get("command_id").getAsString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void cancel(Dispatch dispatch) {
        stopTimeout(dispatch);
        dispatches.remove(dispatch.command.getCommandId());
    }

    private void stopTimeout(Dispatch dispatch) {
        if (dispatch.timeout != null) dispatch.timeout.cancel();
        dispatch.timeout = null;
    }

    private static final class Dispatch {
        final MediaV11Command command;
        final String sessionId;
        final String leaseId;
        final long generation;
        Cancellable timeout;

        Dispatch(MediaV11Command command, String sessionId, String leaseId, long generation) {
            this.command = command;
            this.sessionId = sessionId;
            this.leaseId = leaseId;
            this.generation = generation;
        }
    }
}
