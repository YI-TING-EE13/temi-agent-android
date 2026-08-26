package com.robotemi.agent.mqtt;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Owns at most one MQTT connection and ignores callbacks from replaced clients. */
public final class SingleActiveMqttBroker {
    public interface Factory {
        MqttConnection create(MqttEndpoint endpoint, String clientId, String[] subscribedTopics);
    }

    public interface Listener {
        void onMessage(@NonNull String topic, @NonNull String payload);
        default void onMessage(
                @NonNull String topic, @NonNull String payload, boolean retained) {
            onMessage(topic, payload);
        }
        void onConnected();
        void onDisconnected(String reason);
        default void onStateChanged(
                MqttConnection.ConnectionState state, String reason) {}
    }

    public interface ClientIdProvider {
        String clientIdFor(MqttEndpoint endpoint);
    }

    public enum ApplyResult {
        APPLIED,
        UNCHANGED,
        DISABLED,
        REJECTED_PENDING_OUTBOX,
        INVALID_CONFIGURATION
    }

    private final Factory factory;
    private final ClientIdProvider clientIdProvider;
    private final Listener listener;
    private final boolean residentIdentityEnabled;
    private final boolean careReportEnabled;
    private final boolean legacyActionsEnabled;

    @Nullable private MqttConnection activeConnection;
    @Nullable private MqttEndpoint activeEndpoint;
    @Nullable private MqttTopicSet activeTopics;
    private int generation;
    private boolean connectionRequested;
    private boolean shutdown;

    public SingleActiveMqttBroker(Factory factory, String clientId, Listener listener) {
        this(factory, endpoint -> clientId, listener, false, false, false);
    }

    public SingleActiveMqttBroker(
            Factory factory,
            ClientIdProvider clientIdProvider,
            Listener listener) {
        this(factory, clientIdProvider, listener, false, false, false);
    }

    public SingleActiveMqttBroker(
            Factory factory,
            String clientId,
            Listener listener,
            boolean residentIdentityEnabled) {
        this(factory, endpoint -> clientId, listener,
                residentIdentityEnabled, false, false);
    }

    public SingleActiveMqttBroker(
            Factory factory,
            ClientIdProvider clientIdProvider,
            Listener listener,
            boolean residentIdentityEnabled) {
        this(factory, clientIdProvider, listener,
                residentIdentityEnabled, false, false);
    }

    public SingleActiveMqttBroker(
            Factory factory,
            String clientId,
            Listener listener,
            boolean residentIdentityEnabled,
            boolean careReportEnabled) {
        this(factory, endpoint -> clientId, listener,
                residentIdentityEnabled, careReportEnabled, false);
    }

    public SingleActiveMqttBroker(
            Factory factory,
            String clientId,
            Listener listener,
            boolean residentIdentityEnabled,
            boolean careReportEnabled,
            boolean legacyActionsEnabled) {
        this(factory, endpoint -> clientId, listener,
                residentIdentityEnabled, careReportEnabled, legacyActionsEnabled);
    }

    public SingleActiveMqttBroker(
            Factory factory,
            ClientIdProvider clientIdProvider,
            Listener listener,
            boolean residentIdentityEnabled,
            boolean careReportEnabled) {
        this(factory, clientIdProvider, listener,
                residentIdentityEnabled, careReportEnabled, false);
    }

    public SingleActiveMqttBroker(
            Factory factory,
            ClientIdProvider clientIdProvider,
            Listener listener,
            boolean residentIdentityEnabled,
            boolean careReportEnabled,
            boolean legacyActionsEnabled) {
        this.factory = factory;
        this.clientIdProvider = clientIdProvider;
        this.listener = listener;
        this.residentIdentityEnabled = residentIdentityEnabled;
        this.careReportEnabled = careReportEnabled;
        this.legacyActionsEnabled = legacyActionsEnabled;
    }

    public synchronized ApplyResult apply(
            MqttEndpointSelection selection,
            int pendingOutboxCount,
            @Nullable String outboxOwnerFingerprint
    ) {
        if (shutdown) {
            throw new IllegalStateException("mqtt_broker_owner_shutdown");
        }
        if (selection.status() == MqttEndpointSelection.Status.DISABLED) {
            if (!MqttEndpointSwitchPolicy.canDisable(pendingOutboxCount)) {
                return ApplyResult.REJECTED_PENDING_OUTBOX;
            }
            replaceConnection(null);
            return ApplyResult.DISABLED;
        }
        if (selection.status() != MqttEndpointSelection.Status.VALID
                || selection.endpoint() == null) {
            replaceConnection(null);
            return ApplyResult.INVALID_CONFIGURATION;
        }
        MqttEndpoint requested = selection.endpoint();
        if (!MqttEndpointSwitchPolicy.canActivate(
                activeEndpoint, requested, pendingOutboxCount, outboxOwnerFingerprint)) {
            return ApplyResult.REJECTED_PENDING_OUTBOX;
        }
        if (requested.equals(activeEndpoint) && activeConnection != null) {
            return ApplyResult.UNCHANGED;
        }
        replaceConnection(requested);
        return ApplyResult.APPLIED;
    }

    public synchronized void connect() {
        if (shutdown) {
            throw new IllegalStateException("mqtt_broker_owner_shutdown");
        }
        if (connectionRequested || activeConnection == null) {
            return;
        }
        connectionRequested = true;
        activeConnection.connect();
    }

    public synchronized void disconnect() {
        if (!connectionRequested) {
            return;
        }
        connectionRequested = false;
        if (activeConnection != null) {
            activeConnection.disconnect();
        }
    }

    public synchronized void shutdown() {
        if (shutdown) {
            return;
        }
        shutdown = true;
        connectionRequested = false;
        generation++;
        if (activeConnection != null) {
            activeConnection.shutdown();
            activeConnection = null;
        }
        activeEndpoint = null;
        activeTopics = null;
    }

    public synchronized void publish(
            String topic,
            String payload,
            @Nullable MqttConnection.PublishCallback callback
    ) {
        if (activeConnection == null) {
            if (callback != null) {
                callback.onComplete(false);
            }
            return;
        }
        activeConnection.publish(topic, payload, callback);
    }

    public synchronized void publish(
            String topic,
            String payload,
            boolean retained,
            @Nullable MqttConnection.PublishCallback callback
    ) {
        if (activeConnection == null) {
            if (callback != null) callback.onComplete(false);
            return;
        }
        activeConnection.publish(topic, payload, retained, callback);
    }

    public synchronized boolean isConnected() {
        return activeConnection != null && activeConnection.isConnected();
    }

    @Nullable
    public synchronized MqttEndpoint endpoint() {
        return activeEndpoint;
    }

    @Nullable
    public synchronized MqttTopicSet topics() {
        return activeTopics;
    }

    private void replaceConnection(@Nullable MqttEndpoint endpoint) {
        boolean reconnect = connectionRequested;
        generation++;
        if (activeConnection != null) {
            activeConnection.shutdown();
        }
        activeConnection = null;
        activeEndpoint = endpoint;
        activeTopics = endpoint == null ? null
                : new MqttTopicSet(
                        endpoint.robotId(), residentIdentityEnabled, careReportEnabled,
                        legacyActionsEnabled);
        if (endpoint == null) {
            return;
        }
        int currentGeneration = generation;
        MqttConnection created = factory.create(
                endpoint,
                clientIdProvider.clientIdFor(endpoint),
                activeTopics.subscribedTopics());
        created.setInboundMessageListener((topic, payload, retained) -> {
            synchronized (SingleActiveMqttBroker.this) {
                if (currentGeneration != generation || created != activeConnection) {
                    return;
                }
            }
            listener.onMessage(topic, payload, retained);
        });
        created.setConnectionListener(new MqttConnection.ConnectionListener() {
            @Override
            public void onConnected() {
                synchronized (SingleActiveMqttBroker.this) {
                    if (currentGeneration != generation || created != activeConnection) {
                        return;
                    }
                }
                listener.onConnected();
            }

            @Override
            public void onDisconnected(String reason) {
                synchronized (SingleActiveMqttBroker.this) {
                    if (currentGeneration != generation || created != activeConnection) {
                        return;
                    }
                }
                listener.onDisconnected(reason);
            }

            @Override
            public void onStateChanged(
                    MqttConnection.ConnectionState state, String reason) {
                synchronized (SingleActiveMqttBroker.this) {
                    if (currentGeneration != generation || created != activeConnection) {
                        return;
                    }
                }
                listener.onStateChanged(state, reason);
            }
        });
        activeConnection = created;
        if (reconnect) {
            created.connect();
        }
    }
}
