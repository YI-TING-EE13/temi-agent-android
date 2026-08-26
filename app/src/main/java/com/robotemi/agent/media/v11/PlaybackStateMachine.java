package com.robotemi.agent.media.v11;

/** Pure state machine used to validate playback transitions before player side effects. */
public final class PlaybackStateMachine {
    public enum State {
        IDLE, STARTING, PLAYING, PAUSED, STOPPING, COMPLETED, CANCELLED, FAILED
    }

    private State state = State.IDLE;
    private String sessionId;

    public synchronized void start(String newSessionId) {
        require(state == State.IDLE, "playback_not_idle");
        require(newSessionId != null && !newSessionId.isEmpty(), "missing_session_id");
        sessionId = newSessionId;
        state = State.STARTING;
    }

    public synchronized void started(String targetSessionId) {
        requireTarget(targetSessionId);
        require(state == State.STARTING, "playback_not_starting");
        state = State.PLAYING;
    }

    public synchronized void pause(String targetSessionId) {
        requireTarget(targetSessionId);
        require(state == State.PLAYING, "playback_not_playing");
        state = State.PAUSED;
    }

    public synchronized void resume(String targetSessionId) {
        requireTarget(targetSessionId);
        require(state == State.PAUSED, "playback_not_paused");
        state = State.PLAYING;
    }

    public synchronized void beginStop(String targetSessionId) {
        requireTarget(targetSessionId);
        require(state == State.PLAYING || state == State.PAUSED,
                "playback_not_stoppable");
        state = State.STOPPING;
    }

    public synchronized boolean complete(String targetSessionId) {
        requireTarget(targetSessionId);
        if (isTerminal()) {
            return false;
        }
        require(state == State.PLAYING, "playback_not_playing");
        state = State.COMPLETED;
        return true;
    }

    public synchronized boolean cancel(String targetSessionId) {
        requireTarget(targetSessionId);
        if (isTerminal()) {
            return false;
        }
        require(state == State.STARTING || state == State.PLAYING || state == State.PAUSED
                        || state == State.STOPPING,
                "playback_not_cancellable");
        state = State.CANCELLED;
        return true;
    }

    public synchronized boolean fail(String targetSessionId) {
        requireTarget(targetSessionId);
        if (isTerminal()) {
            return false;
        }
        require(state != State.IDLE, "playback_not_active");
        state = State.FAILED;
        return true;
    }

    public synchronized void clearTerminal() {
        require(isTerminal(), "playback_not_terminal");
        state = State.IDLE;
        sessionId = null;
    }

    public synchronized State getState() { return state; }
    public synchronized String getSessionId() { return sessionId; }
    public synchronized boolean isActive() {
        return state == State.STARTING || state == State.PLAYING || state == State.PAUSED
                || state == State.STOPPING;
    }
    public synchronized boolean isTerminal() {
        return state == State.COMPLETED || state == State.CANCELLED || state == State.FAILED;
    }

    private void requireTarget(String targetSessionId) {
        require(sessionId != null && sessionId.equals(targetSessionId), "session_mismatch");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
