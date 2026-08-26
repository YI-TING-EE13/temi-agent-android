package com.robotemi.agent.mqtt;

/** Pure topic policy for side-effecting MQTT ingress. */
public final class MqttIngressPolicy {
    public static final String RETAINED_COMMAND_REQUEST = "retained_command_request";
    public static final String RETAINED_LEGACY_SPEAK = "retained_legacy_speak";
    public static final String RETAINED_LEGACY_NAVIGATE = "retained_legacy_navigate";
    public static final String RETAINED_LEGACY_WAKEUP = "retained_legacy_wakeup";
    public static final String LEGACY_DISABLED_SPEAK = "legacy_disabled_speak";
    public static final String LEGACY_DISABLED_NAVIGATE = "legacy_disabled_navigate";
    public static final String LEGACY_DISABLED_WAKEUP = "legacy_disabled_wakeup";

    private MqttIngressPolicy() {}

    /** Returns whether the topic can cause a robot, media, or TTS side effect. */
    public static boolean isSideEffectingTopic(MqttTopicSet topics, String topic) {
        return categoryFor(topics, topic) != null;
    }

    /** Returns whether a delivery must be rejected before any ingress handling. */
    public static boolean shouldRejectRetained(
            MqttTopicSet topics, String topic, boolean retained) {
        return retained && isSideEffectingTopic(topics, topic);
    }

    /** Returns a bounded diagnostic category for a rejected retained delivery. */
    public static String retainedRejectionCategory(
            MqttTopicSet topics, String topic, boolean retained) {
        if (!shouldRejectRetained(topics, topic, retained)) return null;
        String category = categoryFor(topics, topic);
        if ("command_request".equals(category)) return RETAINED_COMMAND_REQUEST;
        if ("legacy_speak".equals(category)) return RETAINED_LEGACY_SPEAK;
        if ("legacy_navigate".equals(category)) return RETAINED_LEGACY_NAVIGATE;
        return RETAINED_LEGACY_WAKEUP;
    }

    /** Returns a bounded category for legacy ingress rejected by the secure default. */
    public static String disabledLegacyRejectionCategory(
            MqttTopicSet topics, String topic) {
        if (topics != null && topics.legacyActionsEnabled()) return null;
        if (MqttTopicSet.ACTION_SPEAK.equals(topic)) return LEGACY_DISABLED_SPEAK;
        if (MqttTopicSet.ACTION_NAVIGATE.equals(topic)) return LEGACY_DISABLED_NAVIGATE;
        if (MqttTopicSet.ACTION_WAKEUP.equals(topic)) return LEGACY_DISABLED_WAKEUP;
        return null;
    }

    private static String categoryFor(MqttTopicSet topics, String topic) {
        if (topics != null && topics.commandRequest().equals(topic)) {
            return "command_request";
        }
        if (MqttTopicSet.ACTION_SPEAK.equals(topic)) return "legacy_speak";
        if (MqttTopicSet.ACTION_NAVIGATE.equals(topic)) return "legacy_navigate";
        if (MqttTopicSet.ACTION_WAKEUP.equals(topic)) return "legacy_wakeup";
        return null;
    }
}
