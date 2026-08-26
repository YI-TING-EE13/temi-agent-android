package com.robotemi.agent.mqtt;

import androidx.annotation.Nullable;

/** Fail-closed result of loading the locally configured active endpoint. */
public final class MqttEndpointSelection {
    public enum Status {
        DISABLED,
        VALID,
        INVALID_CARDINALITY,
        INVALID_VALUE
    }

    private final Status status;
    @Nullable private final MqttEndpoint endpoint;
    private final int configuredCount;
    @Nullable private final String error;

    private MqttEndpointSelection(
            Status status,
            @Nullable MqttEndpoint endpoint,
            int configuredCount,
            @Nullable String error
    ) {
        this.status = status;
        this.endpoint = endpoint;
        this.configuredCount = configuredCount;
        this.error = error;
    }

    public static MqttEndpointSelection disabled() {
        return new MqttEndpointSelection(Status.DISABLED, null, 0, null);
    }

    public static MqttEndpointSelection valid(MqttEndpoint endpoint) {
        return new MqttEndpointSelection(Status.VALID, endpoint, 1, null);
    }

    public static MqttEndpointSelection invalidCardinality(int count) {
        return new MqttEndpointSelection(
                Status.INVALID_CARDINALITY, null, count, "mqtt_endpoint_cardinality_" + count);
    }

    public static MqttEndpointSelection invalidValue(String error) {
        return new MqttEndpointSelection(Status.INVALID_VALUE, null, 1, error);
    }

    public Status status() {
        return status;
    }

    @Nullable
    public MqttEndpoint endpoint() {
        return endpoint;
    }

    public int configuredCount() {
        return configuredCount;
    }

    @Nullable
    public String error() {
        return error;
    }
}
