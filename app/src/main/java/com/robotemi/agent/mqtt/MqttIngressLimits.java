package com.robotemi.agent.mqtt;

import java.nio.charset.StandardCharsets;

/** Pure limits for inbound MQTT payloads and detached-message buffering. */
public final class MqttIngressLimits {
    public static final int MAX_INBOUND_PAYLOAD_BYTES = 64 * 1024;
    public static final int MAX_BUFFERED_MESSAGES = 256;
    public static final long MAX_BUFFERED_BYTES = 1024L * 1024L;

    private MqttIngressLimits() {}

    /** Returns the UTF-8 byte length used by the ingress and buffer gates. */
    public static int utf8ByteLength(String payload) {
        return payload.getBytes(StandardCharsets.UTF_8).length;
    }
}
