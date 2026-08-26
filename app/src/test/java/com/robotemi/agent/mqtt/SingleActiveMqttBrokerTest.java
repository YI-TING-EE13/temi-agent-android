package com.robotemi.agent.mqtt;

import androidx.annotation.Nullable;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SingleActiveMqttBrokerTest {
    private FakeFactory factory;
    private RecordingListener listener;
    private SingleActiveMqttBroker broker;
    private MqttEndpoint first;
    private MqttEndpoint second;

    @Before
    public void setUp() {
        factory = new FakeFactory();
        listener = new RecordingListener();
        broker = new SingleActiveMqttBroker(factory, "client", listener);
        first = MqttEndpoint.create("first.example", 1883, "temi-01");
        second = MqttEndpoint.create("second.example", 1883, "temi-01");
    }

    @Test
    public void ownsOnlyOneConnectionAndUsesItForReceiveAndPublish() {
        assertEquals(SingleActiveMqttBroker.ApplyResult.APPLIED,
                broker.apply(MqttEndpointSelection.valid(first), 0, null));
        broker.connect();
        FakeConnection connection = factory.created.get(0);
        connection.connected = true;
        connection.emitConnected();
        connection.emitMessage("temi/temi-01/cmd/request", "request");
        broker.publish("temi/temi-01/cmd/result", "result", null);

        assertEquals(1, factory.created.size());
        assertEquals(1, connection.connectCalls);
        assertEquals(1, listener.messages.size());
        assertEquals("temi/temi-01/cmd/result", connection.lastPublishTopic);
        assertTrue(broker.isConnected());
    }

    @Test
    public void endpointChangeWithEmptyOutboxReplacesAndReconnects() {
        broker.apply(MqttEndpointSelection.valid(first), 0, null);
        broker.connect();
        FakeConnection old = factory.created.get(0);

        assertEquals(SingleActiveMqttBroker.ApplyResult.APPLIED,
                broker.apply(MqttEndpointSelection.valid(second), 0, null));
        FakeConnection replacement = factory.created.get(1);

        assertTrue(old.shutdown);
        assertEquals(1, replacement.connectCalls);
        assertSame(second, broker.endpoint());
    }

    @Test
    public void pendingOutboxRejectsEndpointChangeAndDisable() {
        broker.apply(MqttEndpointSelection.valid(first), 0, null);
        FakeConnection original = factory.created.get(0);

        assertEquals(SingleActiveMqttBroker.ApplyResult.REJECTED_PENDING_OUTBOX,
                broker.apply(
                        MqttEndpointSelection.valid(second),
                        1,
                        first.fingerprint()));
        assertEquals(SingleActiveMqttBroker.ApplyResult.REJECTED_PENDING_OUTBOX,
                broker.apply(MqttEndpointSelection.disabled(), 1, first.fingerprint()));
        assertSame(original, factory.created.get(0));
        assertFalse(original.shutdown);
        assertSame(first, broker.endpoint());
    }

    @Test
    public void zeroEndpointDisablesAndMultipleEndpointsFailClosed() {
        broker.apply(MqttEndpointSelection.valid(first), 0, null);
        FakeConnection firstConnection = factory.created.get(0);
        assertEquals(SingleActiveMqttBroker.ApplyResult.DISABLED,
                broker.apply(MqttEndpointSelection.disabled(), 0, null));
        assertTrue(firstConnection.shutdown);

        broker.apply(MqttEndpointSelection.valid(first), 0, null);
        FakeConnection secondConnection = factory.created.get(1);
        assertEquals(SingleActiveMqttBroker.ApplyResult.INVALID_CONFIGURATION,
                broker.apply(MqttEndpointSelection.invalidCardinality(2), 0, null));
        assertTrue(secondConnection.shutdown);
        assertEquals(null, broker.endpoint());
    }

    @Test
    public void repeatedConnectAndDisconnectAreIdempotent() {
        broker.apply(MqttEndpointSelection.valid(first), 0, null);
        FakeConnection connection = factory.created.get(0);
        broker.connect();
        broker.connect();
        broker.disconnect();
        broker.disconnect();
        broker.connect();

        assertEquals(2, connection.connectCalls);
        assertEquals(1, connection.disconnectCalls);
    }

    @Test
    public void callbacksFromReplacedConnectionAreIgnored() {
        broker.apply(MqttEndpointSelection.valid(first), 0, null);
        FakeConnection old = factory.created.get(0);
        broker.apply(MqttEndpointSelection.valid(second), 0, null);
        old.emitMessage("stale", "stale");
        old.emitConnected();

        assertTrue(listener.messages.isEmpty());
        assertEquals(0, listener.connectedCalls);
    }

    @Test
    public void identityTopicIsAddedOnlyWhenFeatureEnabled() {
        SingleActiveMqttBroker enabled =
                new SingleActiveMqttBroker(factory, "client", listener, true);
        enabled.apply(MqttEndpointSelection.valid(first), 0, null);

        String[] enabledTopics = factory.subscriptions.get(0);
        assertTrue(contains(enabledTopics, "temi/temi-01/resident/identity/result"));

        factory.subscriptions.clear();
        SingleActiveMqttBroker disabled =
                new SingleActiveMqttBroker(factory, "client", listener, false);
        disabled.apply(MqttEndpointSelection.valid(first), 0, null);
        assertFalse(contains(
                factory.subscriptions.get(0),
                "temi/temi-01/resident/identity/result"));
    }

    @Test
    public void careTopicsAreAddedOnlyWhenFeatureEnabledAndIdentityIsIncluded() {
        SingleActiveMqttBroker enabled =
                new SingleActiveMqttBroker(factory, "client", listener, false, true);
        enabled.apply(MqttEndpointSelection.valid(first), 0, null);

        String[] topics = factory.subscriptions.get(0);
        assertTrue(contains(topics, "temi/temi-01/resident/identity/result"));
        assertTrue(contains(topics, "temi/temi-01/care/report"));

        factory.subscriptions.clear();
        SingleActiveMqttBroker disabled =
                new SingleActiveMqttBroker(factory, "client", listener, false, false);
        disabled.apply(MqttEndpointSelection.valid(first), 0, null);
        assertFalse(contains(
                factory.subscriptions.get(0), "temi/temi-01/care/report"));
    }

    @Test
    public void legacyActionTopicsAreDisabledByDefaultAndExplicitlyOptIn() {
        SingleActiveMqttBroker disabled =
                new SingleActiveMqttBroker(factory, "client", listener, false, false);
        disabled.apply(MqttEndpointSelection.valid(first), 0, null);

        String[] disabledTopics = factory.subscriptions.get(0);
        assertFalse(contains(disabledTopics, MqttTopicSet.ACTION_SPEAK));
        assertFalse(contains(disabledTopics, MqttTopicSet.ACTION_NAVIGATE));
        assertFalse(contains(disabledTopics, MqttTopicSet.ACTION_WAKEUP));

        factory.subscriptions.clear();
        SingleActiveMqttBroker enabled =
                new SingleActiveMqttBroker(factory, "client", listener, false, false, true);
        enabled.apply(MqttEndpointSelection.valid(first), 0, null);

        String[] enabledTopics = factory.subscriptions.get(0);
        assertTrue(contains(enabledTopics, MqttTopicSet.ACTION_SPEAK));
        assertTrue(contains(enabledTopics, MqttTopicSet.ACTION_NAVIGATE));
        assertTrue(contains(enabledTopics, MqttTopicSet.ACTION_WAKEUP));
    }

    @Test
    public void retainedMetadataIsForwardedAndInteractionPublishIsNonRetained() {
        broker.apply(MqttEndpointSelection.valid(first), 0, null);
        FakeConnection connection = factory.created.get(0);
        connection.emitMessage("temi/temi-01/care/report", "report", true);
        broker.publish(
                "temi/temi-01/care/report/interaction/result",
                "interaction",
                false,
                null);

        assertEquals(1, listener.messages.size());
        assertTrue(listener.lastRetained);
        assertFalse(connection.lastPublishRetained);
    }

    private static boolean contains(String[] values, String target) {
        for (String value : values) {
            if (target.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static final class FakeFactory implements SingleActiveMqttBroker.Factory {
        final List<FakeConnection> created = new ArrayList<>();
        final List<String[]> subscriptions = new ArrayList<>();

        @Override
        public MqttConnection create(
                MqttEndpoint endpoint, String clientId, String[] subscribedTopics) {
            FakeConnection connection = new FakeConnection();
            created.add(connection);
            subscriptions.add(subscribedTopics);
            return connection;
        }
    }

    private static final class FakeConnection implements MqttConnection {
        @Nullable MessageListener messageListener;
        @Nullable InboundMessageListener inboundMessageListener;
        @Nullable ConnectionListener connectionListener;
        int connectCalls;
        int disconnectCalls;
        boolean shutdown;
        boolean connected;
        String lastPublishTopic;
        boolean lastPublishRetained;

        @Override
        public void setMessageListener(@Nullable MessageListener listener) {
            messageListener = listener;
        }

        @Override
        public void setInboundMessageListener(@Nullable InboundMessageListener listener) {
            inboundMessageListener = listener;
        }

        @Override
        public void setConnectionListener(@Nullable ConnectionListener listener) {
            connectionListener = listener;
        }

        @Override
        public void connect() {
            connectCalls++;
        }

        @Override
        public void disconnect() {
            disconnectCalls++;
            connected = false;
        }

        @Override
        public void shutdown() {
            shutdown = true;
            connected = false;
        }

        @Override
        public void publish(
                String topic, String jsonPayload, @Nullable PublishCallback callback) {
            lastPublishTopic = topic;
            if (callback != null) {
                callback.onComplete(connected);
            }
        }

        @Override
        public void publish(
                String topic,
                String jsonPayload,
                boolean retained,
                @Nullable PublishCallback callback) {
            lastPublishRetained = retained;
            publish(topic, jsonPayload, callback);
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        void emitConnected() {
            if (connectionListener != null) {
                connectionListener.onConnected();
            }
        }

        void emitMessage(String topic, String payload) {
            emitMessage(topic, payload, false);
        }

        void emitMessage(String topic, String payload, boolean retained) {
            if (inboundMessageListener != null) {
                inboundMessageListener.onMessage(topic, payload, retained);
            } else if (messageListener != null) {
                messageListener.onMessage(topic, payload);
            }
        }
    }

    private static final class RecordingListener
            implements SingleActiveMqttBroker.Listener {
        final List<String> messages = new ArrayList<>();
        int connectedCalls;
        boolean lastRetained;

        @Override
        public void onMessage(String topic, String payload) {
            messages.add(topic + "=" + payload);
        }

        @Override
        public void onMessage(String topic, String payload, boolean retained) {
            lastRetained = retained;
            onMessage(topic, payload);
        }

        @Override
        public void onConnected() {
            connectedCalls++;
        }

        @Override
        public void onDisconnected(String reason) {}
    }
}
