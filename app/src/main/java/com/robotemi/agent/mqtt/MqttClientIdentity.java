package com.robotemi.agent.mqtt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Builds a stable, credential-free MQTT client identity for one robot. */
public final class MqttClientIdentity {
    private MqttClientIdentity() {}

    public static String forRobot(String packageName, String robotId) {
        String robotPart = sanitize(robotId);
        String digest = digest(packageName + "\n" + robotId).substring(0, 10);
        String prefix = "temi-android-" + digest + "-";
        int remaining = Math.max(1, 64 - prefix.length());
        if (robotPart.length() > remaining) robotPart = robotPart.substring(0, remaining);
        return prefix + robotPart;
    }

    private static String sanitize(String value) {
        String sanitized = value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9_-]", "-");
        return sanitized.isEmpty() ? "unknown" : sanitized;
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte part : bytes) {
                result.append(String.format(Locale.US, "%02x", part & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("sha256_unavailable", e);
        }
    }
}
