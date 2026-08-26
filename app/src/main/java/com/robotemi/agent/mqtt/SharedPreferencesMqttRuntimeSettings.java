package com.robotemi.agent.mqtt;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

/** Private device-local MQTT endpoint and pending-outbox ownership settings. */
public final class SharedPreferencesMqttRuntimeSettings {
    private static final String KEY_ENDPOINT_COUNT = "endpoint_count";
    private static final String KEY_HOST = "host";
    private static final String KEY_PORT = "port";
    private static final String KEY_ROBOT_ID = "robot_id";
    private static final String KEY_OUTBOX_OWNER = "outbox_owner_fingerprint";

    private final SharedPreferences preferences;

    public SharedPreferencesMqttRuntimeSettings(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    public synchronized MqttEndpointSelection loadEndpoint() {
        int count = preferences.getInt(KEY_ENDPOINT_COUNT, 0);
        if (count == 0) {
            return MqttEndpointSelection.disabled();
        }
        if (count != 1) {
            return MqttEndpointSelection.invalidCardinality(count);
        }
        try {
            return MqttEndpointSelection.valid(MqttEndpoint.create(
                    preferences.getString(KEY_HOST, ""),
                    preferences.getInt(KEY_PORT, 0),
                    preferences.getString(KEY_ROBOT_ID, "")));
        } catch (IllegalArgumentException e) {
            return MqttEndpointSelection.invalidValue(e.getMessage());
        }
    }

    public synchronized boolean saveEndpoint(MqttEndpoint endpoint) {
        return preferences.edit()
                .putInt(KEY_ENDPOINT_COUNT, 1)
                .putString(KEY_HOST, endpoint.host())
                .putInt(KEY_PORT, endpoint.port())
                .putString(KEY_ROBOT_ID, endpoint.robotId())
                .commit();
    }

    public synchronized boolean disableEndpoint() {
        return preferences.edit()
                .putInt(KEY_ENDPOINT_COUNT, 0)
                .remove(KEY_HOST)
                .remove(KEY_PORT)
                .remove(KEY_ROBOT_ID)
                .commit();
    }

    @Nullable
    public synchronized String outboxOwnerFingerprint() {
        return preferences.getString(KEY_OUTBOX_OWNER, null);
    }

    public synchronized boolean bindOutboxOwner(MqttEndpoint endpoint) {
        String owner = outboxOwnerFingerprint();
        if (owner != null && !owner.equals(endpoint.fingerprint())) {
            return false;
        }
        return owner != null
                || preferences.edit()
                        .putString(KEY_OUTBOX_OWNER, endpoint.fingerprint())
                        .commit();
    }

    public synchronized boolean clearOutboxOwner() {
        return preferences.edit().remove(KEY_OUTBOX_OWNER).commit();
    }
}
