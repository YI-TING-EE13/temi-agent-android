package com.robotemi.agent.command;

import java.util.UUID;

/**
 * Process-owned correlation for canonical TTS actions.
 *
 * <p>The Android Activity is an observer of this runtime.  The runtime keeps the
 * request identity and timeout independent from Activity visibility, buffers one
 * terminal resolution while an observer is detached, and never starts a second
 * hardware side effect for a duplicate callback.</p>
 */
public final class CanonicalCommandRuntime {
    public static final long DEFAULT_TTS_TIMEOUT_MS = 30_000L;

    public interface Scheduler {
        Cancellable schedule(Runnable task, long delayMs);
    }

    public interface Cancellable {
        void cancel();
    }

    public interface Listener {
        void onTtsResolved(Resolution resolution);
    }

    /** Durable terminalization hook owned by the process runtime, not the Activity. */
    public interface Terminalizer {
        void persistAndPublish(Resolution resolution);
    }

    public static final class Resolution {
        private final UUID requestId;
        private final String commandId;
        private final String actionId;
        private final String status;
        private final String error;
        private final boolean timeout;
        private final String eventId;
        private final String robotId;
        private final String actionType;
        private final boolean terminalAction;

        private Resolution(
                UUID requestId,
                String commandId,
                String actionId,
                String status,
                String error,
                boolean timeout,
                String eventId,
                String robotId,
                String actionType,
                boolean terminalAction) {
            this.requestId = requestId;
            this.commandId = commandId;
            this.actionId = actionId;
            this.status = status;
            this.error = error;
            this.timeout = timeout;
            this.eventId = eventId;
            this.robotId = robotId;
            this.actionType = actionType;
            this.terminalAction = terminalAction;
        }

        public UUID getRequestId() { return requestId; }
        public String getCommandId() { return commandId; }
        public String getActionId() { return actionId; }
        public String getStatus() { return status; }
        public String getError() { return error; }
        public boolean isTimeout() { return timeout; }
        public String getEventId() { return eventId; }
        public String getRobotId() { return robotId; }
        public String getActionType() { return actionType; }
        public boolean isTerminalAction() { return terminalAction; }
    }

    private static final class Pending {
        private final UUID requestId;
        private final String commandId;
        private final String eventId;
        private final String robotId;
        private final String actionId;
        private final String actionType;
        private final boolean terminalAction;

        private Pending(UUID requestId, String commandId, String eventId, String robotId,
                        String actionId, String actionType, boolean terminalAction) {
            this.requestId = requestId;
            this.commandId = commandId;
            this.eventId = eventId;
            this.robotId = robotId;
            this.actionId = actionId;
            this.actionType = actionType;
            this.terminalAction = terminalAction;
        }
    }

    private final Scheduler scheduler;
    private final long timeoutMs;
    private final Terminalizer terminalizer;
    private Listener listener;
    private Pending pending;
    private Resolution bufferedResolution;
    private Cancellable timeoutTask;

    public CanonicalCommandRuntime(Scheduler scheduler) {
        this(scheduler, DEFAULT_TTS_TIMEOUT_MS, null);
    }

    public CanonicalCommandRuntime(Scheduler scheduler, long timeoutMs) {
        this(scheduler, timeoutMs, null);
    }

    public CanonicalCommandRuntime(
            Scheduler scheduler, long timeoutMs, Terminalizer terminalizer) {
        if (scheduler == null || timeoutMs < 1) {
            throw new IllegalArgumentException("invalid_canonical_runtime_configuration");
        }
        this.scheduler = scheduler;
        this.timeoutMs = timeoutMs;
        this.terminalizer = terminalizer;
    }

    /** Attach or replace the UI observer without changing the pending operation. */
    public void attachListener(Listener nextListener) {
        Resolution replay = null;
        Listener replayListener = null;
        synchronized (this) {
            listener = nextListener;
            if (listener != null && bufferedResolution != null) {
                replay = bufferedResolution;
                bufferedResolution = null;
                replayListener = listener;
            }
        }
        if (replay != null && replayListener != null) replayListener.onTtsResolved(replay);
    }

    /** Detaches only the observer; the process-owned operation remains pending. */
    public synchronized void detachListener() {
        listener = null;
    }

    /**
     * Starts one canonical TTS correlation.  A second pending request is rejected,
     * which preserves exactly-once hardware dispatch for duplicate commands.
     */
    public boolean beginTts(UUID requestId, String commandId, String actionId) {
        return beginTts(requestId, commandId, null, null, actionId, "speak", true);
    }

    public boolean beginTts(
            UUID requestId,
            String commandId,
            String eventId,
            String robotId,
            String actionId,
            String actionType,
            boolean terminalAction) {
        if (requestId == null || commandId == null || actionId == null) {
            throw new IllegalArgumentException("invalid_canonical_tts_correlation");
        }
        synchronized (this) {
            if (pending != null) return false;
            pending = new Pending(requestId, commandId, eventId, robotId,
                    actionId, actionType, terminalAction);
            timeoutTask = scheduler.schedule(
                    () -> resolveTimeout(requestId), timeoutMs);
            return true;
        }
    }

    /** Handles a Temi terminal callback; stale and duplicate callbacks are ignored. */
    public void onTtsStatusChanged(UUID requestId, boolean completed) {
        resolve(requestId, completed ? "completed" : "failed",
                completed ? null : "tts_error", false);
    }

    /** Clears a request when the SDK rejects the dispatch before speaking starts. */
    public synchronized void cancelTts(UUID requestId) {
        if (pending == null || !pending.requestId.equals(requestId)) return;
        pending = null;
        if (timeoutTask != null) {
            timeoutTask.cancel();
            timeoutTask = null;
        }
    }

    public synchronized boolean isPending() {
        return pending != null;
    }

    public synchronized UUID pendingRequestId() {
        return pending == null ? null : pending.requestId;
    }

    private void resolveTimeout(UUID requestId) {
        resolve(requestId, "failed", "tts_callback_timeout", true);
    }

    private void resolve(UUID requestId, String status, String error, boolean timeout) {
        Listener callback;
        Resolution resolution;
        synchronized (this) {
            if (pending == null || !pending.requestId.equals(requestId)) return;
            Pending completed = pending;
            pending = null;
            if (timeoutTask != null) {
                timeoutTask.cancel();
                timeoutTask = null;
            }
            resolution = new Resolution(
                    completed.requestId,
                    completed.commandId,
                    completed.actionId,
                    status,
                    error,
                    timeout,
                    completed.eventId,
                    completed.robotId,
                    completed.actionType,
                    completed.terminalAction);
            callback = listener;
            if (callback == null) {
                bufferedResolution = resolution;
            }
        }
        if (terminalizer != null) {
            try {
                terminalizer.persistAndPublish(resolution);
            } finally {
                if (callback != null) callback.onTtsResolved(resolution);
            }
        } else if (callback != null) {
            callback.onTtsResolved(resolution);
        }
    }
}
