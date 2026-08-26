package com.robotemi.agent.mqtt;

import androidx.annotation.Nullable;

/** Prevents pending results from crossing endpoint identities. */
public final class MqttEndpointSwitchPolicy {
    private MqttEndpointSwitchPolicy() {}

    public static boolean canActivate(
            @Nullable MqttEndpoint current,
            MqttEndpoint requested,
            int pendingOutboxCount,
            @Nullable String outboxOwnerFingerprint
    ) {
        if (current != null && current.equals(requested)) {
            return true;
        }
        if (pendingOutboxCount == 0) {
            return true;
        }
        return outboxOwnerFingerprint != null
                && outboxOwnerFingerprint.equals(requested.fingerprint());
    }

    public static boolean canDisable(int pendingOutboxCount) {
        return pendingOutboxCount == 0;
    }

    public static boolean canFlush(
            MqttEndpoint active,
            int pendingOutboxCount,
            @Nullable String outboxOwnerFingerprint
    ) {
        if (pendingOutboxCount == 0) {
            return true;
        }
        return active.fingerprint().equals(outboxOwnerFingerprint);
    }
}
