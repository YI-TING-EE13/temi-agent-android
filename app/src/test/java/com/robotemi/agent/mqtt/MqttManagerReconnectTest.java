package com.robotemi.agent.mqtt;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MqttManagerReconnectTest {
    @Test
    public void retriesAfterPahoConnectFailsWithoutConnectionLostCallback() throws Exception {
        AtomicInteger connectCalls = new AtomicInteger();
        CountDownLatch secondConnectAttempt = new CountDownLatch(1);
        CountDownLatch retryableStateObserved = new CountDownLatch(1);
        AtomicReference<MqttConnection.ConnectionState> currentState = new AtomicReference<>();
        List<MqttConnection.ConnectionState> observedStates = new CopyOnWriteArrayList<>();
        List<String> diagnostics = new CopyOnWriteArrayList<>();
        MqttManager manager = new MqttManager(
                "tcp://unit-test.invalid:1883",
                "retry-failure-client",
                new String[0],
                null,
                new CapturingLogSink(diagnostics),
                (brokerUrl, clientId) -> new AlwaysFailingMqttClient(
                        brokerUrl, clientId, connectCalls, secondConnectAttempt),
                MqttManager.JVM_RUNTIME_METADATA);
        manager.setConnectionListener(new MqttConnection.ConnectionListener() {
            @Override
            public void onConnected() {}

            @Override
            public void onDisconnected(String reason) {}

            @Override
            public void onStateChanged(MqttConnection.ConnectionState state, String reason) {
                observedStates.add(state);
                currentState.set(state);
                if (state == MqttConnection.ConnectionState.RECONNECTING) {
                    retryableStateObserved.countDown();
                }
            }
        });

        try {
            manager.connect();

            assertTrue("a Paho connect failure without a connectionLost callback must retry",
                    secondConnectAttempt.await(3, TimeUnit.SECONDS));
            assertTrue("connect failure must leave CONNECTING in a retryable state",
                    retryableStateObserved.await(1, TimeUnit.SECONDS));
            assertTrue(observedStates.contains(MqttConnection.ConnectionState.CONNECTING));
            assertEquals(MqttConnection.ConnectionState.RECONNECTING, currentState.get());
            assertFingerprintIsStableAndNonRaw(diagnostics, "retry-failure-client");
        } finally {
            manager.shutdown();
        }
    }

    private static void assertFingerprintIsStableAndNonRaw(
            List<String> diagnostics, String rawClientId) {
        Set<String> fingerprints = new HashSet<>();
        for (String diagnostic : diagnostics) {
            assertTrue("diagnostic must not expose raw client ID", !diagnostic.contains(rawClientId));
            int fingerprintStart = diagnostic.indexOf("client_id_fingerprint=");
            if (fingerprintStart < 0) continue;
            int valueStart = fingerprintStart + "client_id_fingerprint=".length();
            int valueEnd = diagnostic.indexOf(' ', valueStart);
            fingerprints.add(valueEnd < 0 ? diagnostic.substring(valueStart)
                    : diagnostic.substring(valueStart, valueEnd));
        }
        assertTrue("diagnostics must include a client ID fingerprint", !fingerprints.isEmpty());
        assertEquals("client ID fingerprint must remain stable per manager", 1, fingerprints.size());
        assertTrue(fingerprints.iterator().next().matches("sha256:[0-9a-f]{64}"));
    }

    private static final class CapturingLogSink implements MqttManager.LogSink {
        private final List<String> diagnostics;

        CapturingLogSink(List<String> diagnostics) {
            this.diagnostics = diagnostics;
        }

        @Override public void debug(String message) { diagnostics.add(message); }
        @Override public void info(String message) { diagnostics.add(message); }
        @Override public void warn(String message) { diagnostics.add(message); }
        @Override public void error(String message) { diagnostics.add(message); }
    }

    private static final class AlwaysFailingMqttClient extends MqttClient {
        private final AtomicInteger connectCalls;
        private final CountDownLatch secondConnectAttempt;

        AlwaysFailingMqttClient(
                String brokerUrl,
                String clientId,
                AtomicInteger connectCalls,
                CountDownLatch secondConnectAttempt) throws MqttException {
            super(brokerUrl, clientId, new MemoryPersistence());
            this.connectCalls = connectCalls;
            this.secondConnectAttempt = secondConnectAttempt;
        }

        @Override
        public void connect(MqttConnectOptions options) throws MqttException {
            if (connectCalls.incrementAndGet() == 2) {
                secondConnectAttempt.countDown();
            }
            throw new MqttException(MqttException.REASON_CODE_SERVER_CONNECT_ERROR);
        }
    }
}
