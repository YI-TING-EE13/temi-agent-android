package com.robotemi.agent.mqtt;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Local wire-level instrumentation using a loopback MQTT broker; no device is required. */
public class MqttWireHandshakeInstrumentedTest {
    @Test
    public void completesConnectConnackSubscribeAndConnected() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(5_000);
            String topic = "temi/test/handshake";
            String clientId = "wire-handshake-client";
            CountDownLatch connectReceived = new CountDownLatch(1);
            CountDownLatch subscribeReceived = new CountDownLatch(1);
            CountDownLatch connected = new CountDownLatch(1);
            CountDownLatch allowSuback = new CountDownLatch(1);
            CountDownLatch allowBrokerClose = new CountDownLatch(1);
            AtomicReference<Throwable> fakeBrokerFailure = new AtomicReference<>();
            ExecutorService fakeBroker = Executors.newSingleThreadExecutor();
            MqttManager manager = new MqttManager(
                    "tcp://127.0.0.1:" + server.getLocalPort(),
                    clientId,
                    new String[] {topic},
                    null,
                    MqttManager.NO_OP_LOG_SINK,
                    (brokerUrl, configuredClientId) -> new org.eclipse.paho.client.mqttv3.MqttClient(
                            brokerUrl,
                            configuredClientId,
                            new org.eclipse.paho.client.mqttv3.persist.MemoryPersistence()),
                    MqttManager.JVM_RUNTIME_METADATA);
            manager.setConnectionListener(new MqttConnection.ConnectionListener() {
                @Override
                public void onConnected() {
                    connected.countDown();
                }

                @Override
                public void onDisconnected(String reason) {}
            });

            try {
                fakeBroker.submit(() -> completeHandshake(
                        server, topic, clientId, connectReceived, subscribeReceived, allowSuback,
                        allowBrokerClose,
                        fakeBrokerFailure));

                manager.connect();

                assertTrue("Paho must send MQTT CONNECT (0x10)",
                        connectReceived.await(3, TimeUnit.SECONDS));
                assertTrue("Paho must send MQTT SUBSCRIBE after CONNACK",
                        subscribeReceived.await(3, TimeUnit.SECONDS));
                assertEquals("CONNECTED must not be observed before SUBACK", 1L, connected.getCount());
                allowSuback.countDown();
                assertTrue("manager must report CONNECTED after subscription acknowledgement",
                        connected.await(3, TimeUnit.SECONDS));
                assertTrue(manager.isConnected());
                assertNull(fakeBrokerFailure.get());
                allowBrokerClose.countDown();
            } finally {
                allowSuback.countDown();
                allowBrokerClose.countDown();
                manager.shutdown();
                fakeBroker.shutdownNow();
            }
        }
    }

    private static void completeHandshake(
            ServerSocket server,
            String expectedTopic,
            String expectedClientId,
            CountDownLatch connectReceived,
            CountDownLatch subscribeReceived,
            CountDownLatch allowSuback,
            CountDownLatch allowBrokerClose,
            AtomicReference<Throwable> fakeBrokerFailure) {
        try (Socket socket = server.accept()) {
            socket.setSoTimeout(3_000);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();

            assertEquals(0x10, input.read());
            byte[] connectBody = readMqttPacketBody(input);
            int protocolNameLength = ((connectBody[0] & 0xff) << 8) | (connectBody[1] & 0xff);
            assertEquals("MQTT", new String(connectBody, 2, protocolNameLength, "UTF-8"));
            int protocolVersionIndex = 2 + protocolNameLength;
            assertEquals(4, connectBody[protocolVersionIndex] & 0xff);
            int clientIdLengthIndex = protocolVersionIndex + 4;
            int clientIdLength = ((connectBody[clientIdLengthIndex] & 0xff) << 8)
                    | (connectBody[clientIdLengthIndex + 1] & 0xff);
            assertEquals(expectedClientId, new String(
                    connectBody, clientIdLengthIndex + 2, clientIdLength, "UTF-8"));
            connectReceived.countDown();

            output.write(new byte[] {0x20, 0x02, 0x00, 0x00});
            output.flush();

            assertEquals(0x82, input.read());
            byte[] subscribeBody = readMqttPacketBody(input);
            int packetId = ((subscribeBody[0] & 0xff) << 8) | (subscribeBody[1] & 0xff);
            int topicLength = ((subscribeBody[2] & 0xff) << 8) | (subscribeBody[3] & 0xff);
            assertEquals(expectedTopic, new String(subscribeBody, 4, topicLength, "UTF-8"));
            assertEquals(MqttTopics.QOS, subscribeBody[4 + topicLength] & 0xff);
            subscribeReceived.countDown();

            allowSuback.await(3, TimeUnit.SECONDS);

            output.write(new byte[] {
                    (byte) 0x90, 0x03,
                    (byte) (packetId >> 8), (byte) packetId,
                    (byte) MqttTopics.QOS
            });
            output.flush();
            allowBrokerClose.await(3, TimeUnit.SECONDS);
        } catch (Throwable error) {
            fakeBrokerFailure.set(error);
        }
    }

    private static byte[] readMqttPacketBody(InputStream input) throws IOException {
        int multiplier = 1;
        int remainingLength = 0;
        int encodedByte;
        do {
            encodedByte = input.read();
            if (encodedByte == -1) throw new IOException("unexpected_end_of_remaining_length");
            remainingLength += (encodedByte & 0x7f) * multiplier;
            multiplier *= 128;
            if (multiplier > 128 * 128 * 128 * 128) {
                throw new IOException("invalid_remaining_length");
            }
        } while ((encodedByte & 0x80) != 0);

        ByteArrayOutputStream body = new ByteArrayOutputStream(remainingLength);
        for (int remaining = remainingLength; remaining > 0; remaining--) {
            int value = input.read();
            if (value == -1) throw new IOException("unexpected_end_of_packet_body");
            body.write(value);
        }
        return body.toByteArray();
    }
}
