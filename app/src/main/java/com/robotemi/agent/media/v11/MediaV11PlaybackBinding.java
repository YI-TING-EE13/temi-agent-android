package com.robotemi.agent.media.v11;

/**
 * Activity-owned rendering adapter. It contains no MQTT, ledger, outbox, or command state.
 * Every callback carries the service execution lease and binding generation.
 */
public interface MediaV11PlaybackBinding {
    interface Callback {
        void onStarted(long generation, String leaseId, String sessionId);
        void onCompleted(long generation, String leaseId, String sessionId);
        void onCancelled(long generation, String leaseId, String sessionId);
        void onFailed(long generation, String leaseId, String sessionId, String message);
        void onControlSucceeded(long generation, String leaseId, String sessionId,
                                MediaV11Command.Action action);
        void onControlFailed(long generation, String leaseId, String sessionId,
                             MediaV11Command.Action action, String message);
    }

    void start(MediaV11Command command, String sessionId, String leaseId,
               long generation, Callback callback);

    void pause(String sessionId, String leaseId, long generation, Callback callback);

    void resume(String sessionId, String leaseId, long generation, Callback callback);

    void stop(String sessionId, String leaseId, long generation, Callback callback);

    void detach(long generation);
}
