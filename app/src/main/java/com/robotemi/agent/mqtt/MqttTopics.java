package com.robotemi.agent.mqtt;

/**
 * Transport constants that do not depend on the locally configured robot ID.
 * Robot-specific topics are owned by {@link MqttTopicSet}.
 */
public final class MqttTopics {
    private MqttTopics() {}

    /** Default MQTT QoS level. */
    public static final int QOS = 1;
}
