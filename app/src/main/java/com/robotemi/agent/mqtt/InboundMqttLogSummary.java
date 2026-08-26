package com.robotemi.agent.mqtt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Produces non-sensitive inbound MQTT log metadata without topic or payload text. */
public final class InboundMqttLogSummary {
    public enum Category {
        TRANSPORT,
        COMMAND_REQUEST,
        RESIDENT_IDENTITY,
        CARE_REPORT,
        LEGACY_ACTION,
        OTHER
    }

    private InboundMqttLogSummary() {}

    public static String describe(
            Category category, boolean retained, String payload) {
        byte[] bytes = payload == null
                ? new byte[0] : payload.getBytes(StandardCharsets.UTF_8);
        return describe(category, retained, bytes);
    }

    public static String describe(
            Category category, boolean retained, byte[] payload) {
        byte[] bytes = payload == null ? new byte[0] : payload;
        return "MQTT inbound type="
                + category.name().toLowerCase(Locale.US)
                + " retained=" + retained
                + " bytes=" + bytes.length
                + " sha256=" + sha256(bytes);
    }

    private static String sha256(byte[] payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] value = digest.digest(payload);
            StringBuilder result = new StringBuilder(value.length * 2);
            for (byte item : value) {
                result.append(String.format(Locale.US, "%02x", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("sha256_unavailable", e);
        }
    }
}
