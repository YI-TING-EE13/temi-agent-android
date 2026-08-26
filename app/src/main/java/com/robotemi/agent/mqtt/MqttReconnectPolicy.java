package com.robotemi.agent.mqtt;

/** Pure reconnect policy used to keep broker recovery bounded and testable. */
public final class MqttReconnectPolicy {
    public static final long BASE_DELAY_MS = 1_000L;
    public static final long MAX_DELAY_MS = 30_000L;

    private MqttReconnectPolicy() {}

    public static long delayMs(int attempt) {
        int boundedAttempt = Math.max(0, Math.min(attempt, 5));
        long delay = BASE_DELAY_MS << boundedAttempt;
        return Math.min(delay, MAX_DELAY_MS);
    }
}
