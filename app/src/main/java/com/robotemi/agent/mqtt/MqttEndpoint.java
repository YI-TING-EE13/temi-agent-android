package com.robotemi.agent.mqtt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Validated, credential-free identity of the one active MQTT endpoint. */
public final class MqttEndpoint {
    private static final Pattern HOST_PATTERN =
            Pattern.compile("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?");
    private static final Pattern ROBOT_ID_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private final String host;
    private final int port;
    private final String robotId;
    private final String fingerprint;

    private MqttEndpoint(String host, int port, String robotId) {
        this.host = host;
        this.port = port;
        this.robotId = robotId;
        this.fingerprint = sha256(host + "\n" + port + "\n" + robotId);
    }

    public static MqttEndpoint create(String hostValue, int port, String robotIdValue) {
        String host = normalizeHost(hostValue);
        String robotId = robotIdValue == null ? "" : robotIdValue.trim();
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("mqtt_endpoint_invalid_port");
        }
        if (!ROBOT_ID_PATTERN.matcher(robotId).matches()) {
            throw new IllegalArgumentException("mqtt_endpoint_invalid_robot_id");
        }
        return new MqttEndpoint(host, port, robotId);
    }

    private static String normalizeHost(String value) {
        String host = value == null ? "" : value.trim().toLowerCase(Locale.US);
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isEmpty()
                || host.length() > 253
                || host.contains("..")
                || host.contains("://")
                || host.contains("/")
                || host.contains("\\")
                || host.contains("@")
                || host.contains(",")
                || !HOST_PATTERN.matcher(host).matches()) {
            throw new IllegalArgumentException("mqtt_endpoint_invalid_host");
        }
        return host;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String robotId() {
        return robotId;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public String brokerUrl() {
        return "tcp://" + host + ":" + port;
    }

    public String displayName() {
        return host + ":" + port + " / " + robotId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MqttEndpoint)) {
            return false;
        }
        MqttEndpoint endpoint = (MqttEndpoint) other;
        return port == endpoint.port
                && host.equals(endpoint.host)
                && robotId.equals(endpoint.robotId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port, robotId);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder(digest.length * 2);
            for (byte part : digest) {
                encoded.append(String.format(Locale.US, "%02x", part & 0xff));
            }
            return encoded.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("sha256_unavailable", e);
        }
    }
}
