package com.robotemi.agent.mqtt;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Small connection boundary used by the single-active-broker owner. */
public interface MqttConnection {
    enum ConnectionState {
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        RECONNECTING,
        DEGRADED
    }

    interface MessageListener {
        void onMessage(@NonNull String topic, @NonNull String payload);
    }

    interface InboundMessageListener {
        void onMessage(
                @NonNull String topic, @NonNull String payload, boolean retained);
    }

    interface ConnectionListener {
        void onConnected();
        void onDisconnected(String reason);
        default void onStateChanged(ConnectionState state, String reason) {}
    }

    interface PublishCallback {
        void onComplete(boolean success);
    }

    void setMessageListener(@Nullable MessageListener listener);
    default void setInboundMessageListener(@Nullable InboundMessageListener listener) {
        setMessageListener(listener == null
                ? null : (topic, payload) -> listener.onMessage(topic, payload, false));
    }
    void setConnectionListener(@Nullable ConnectionListener listener);
    void connect();
    void disconnect();
    void shutdown();
    void publish(
            @NonNull String topic,
            @NonNull String jsonPayload,
            @Nullable PublishCallback callback);
    default void publish(
            @NonNull String topic,
            @NonNull String jsonPayload,
            boolean retained,
            @Nullable PublishCallback callback) {
        if (retained) throw new IllegalArgumentException("retained_publish_not_supported");
        publish(topic, jsonPayload, callback);
    }
    boolean isConnected();
}
