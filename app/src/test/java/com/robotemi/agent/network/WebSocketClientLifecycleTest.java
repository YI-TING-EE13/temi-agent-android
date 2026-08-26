package com.robotemi.agent.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class WebSocketClientLifecycleTest {
    private static final long WAIT_TIMEOUT_MS = 2_000L;
    private static final WebSocketClient.Logger NO_OP_LOGGER =
            new WebSocketClient.Logger() {
                @Override
                public void debug(String message) {}

                @Override
                public void info(String message) {}

                @Override
                public void warn(String message) {}
            };

    private FakeSocketFactory socketFactory;
    private ScheduledExecutorService reconnectExecutor;
    private WebSocketClient client;

    @Before
    public void setUp() {
        socketFactory = new FakeSocketFactory();
        reconnectExecutor = Executors.newSingleThreadScheduledExecutor();
        client = new WebSocketClient(
                "ws://localhost:8080",
                socketFactory,
                reconnectExecutor,
                new Random(0),
                NO_OP_LOGGER,
                50,
                50);
    }

    @After
    public void tearDown() throws Exception {
        client.shutdown();
        assertTrue(reconnectExecutor.awaitTermination(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    public void connectDisconnectReconnectReusesClient() throws Exception {
        connectAndOpen(1);
        client.disconnect();

        assertFalse(client.isConnected());
        assertFalse(client.isReconnectExecutorShutdownForTest());

        connectAndOpen(2);
        assertTrue(client.isConnected());
    }

    @Test
    public void repeatedDisconnectIsIdempotent() throws Exception {
        connectAndOpen(1);

        client.disconnect();
        client.disconnect();

        assertEquals(1, socketFactory.socket(0).closeCount);
        assertFalse(client.isConnected());
        assertFalse(client.hasScheduledReconnectForTest());
    }

    @Test
    public void repeatedConnectCreatesOneSocket() throws Exception {
        client.connect();
        client.connect();
        client.connect();

        socketFactory.awaitOpenCount(1);
        Thread.sleep(100);

        assertEquals(1, socketFactory.openCount());
    }

    @Test
    public void connectWhileAlreadyConnectedCreatesNoSecondSocket() throws Exception {
        connectAndOpen(1);

        client.connect();
        Thread.sleep(100);

        assertEquals(1, socketFactory.openCount());
        assertTrue(client.isConnected());
    }

    @Test
    public void disconnectCancelsPendingReconnect() throws Exception {
        client.connect();
        socketFactory.awaitOpenCount(1);
        socketFactory.failLatest();
        assertTrue(client.hasScheduledReconnectForTest());

        client.disconnect();
        Thread.sleep(150);

        assertEquals(1, socketFactory.openCount());
        assertFalse(client.hasScheduledReconnectForTest());
    }

    @Test
    public void backgroundForegroundLifecycleCanRepeatWithoutRejectedExecution() throws Exception {
        for (int i = 1; i <= 20; i++) {
            connectAndOpen(i);
            client.disconnect();
        }

        assertEquals(20, socketFactory.openCount());
        assertFalse(client.isReconnectExecutorShutdownForTest());
    }

    @Test
    public void shutdownTerminatesExecutorAndFactory() throws Exception {
        connectAndOpen(1);

        client.shutdown();

        assertTrue(client.isShutdownForTest());
        assertTrue(client.isReconnectExecutorShutdownForTest());
        assertEquals(1, socketFactory.shutdownCount);
        assertTrue(reconnectExecutor.awaitTermination(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    public void connectAfterShutdownFailsClearly() {
        client.shutdown();

        try {
            client.connect();
            fail("Expected connect() to reject permanent shutdown");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("shut down"));
        }
    }

    @Test
    public void rapidLifecycleChangesNeverCreateParallelReconnectLoops() throws Exception {
        for (int i = 0; i < 50; i++) {
            client.connect();
            client.connect();
            client.disconnect();
        }

        client.connect();
        socketFactory.awaitAtLeastOneOpen();
        Thread.sleep(100);

        assertTrue(socketFactory.maxConcurrentOpenCalls <= 1);
        assertFalse(client.isReconnectExecutorShutdownForTest());
    }

    @Test
    public void staleSocketCallbackCannotDisconnectNewConnection() throws Exception {
        connectAndOpen(1);
        FakeWebSocket staleSocket = socketFactory.socket(0);
        WebSocketListener staleListener = socketFactory.listener(0);

        client.disconnect();
        connectAndOpen(2);
        staleListener.onFailure(staleSocket, new IllegalStateException("stale"), null);

        assertTrue(client.isConnected());
        assertEquals(2, socketFactory.openCount());
        assertFalse(client.hasScheduledReconnectForTest());
    }

    private void connectAndOpen(int expectedOpenCount) throws Exception {
        client.connect();
        socketFactory.awaitOpenCount(expectedOpenCount);
        socketFactory.openLatest();
        assertTrue(client.isConnected());
    }

    private static final class FakeSocketFactory implements WebSocketClient.SocketFactory {
        private final List<FakeWebSocket> sockets = new ArrayList<>();
        private final List<WebSocketListener> listeners = new ArrayList<>();
        private int activeOpenCalls;
        private int maxConcurrentOpenCalls;
        private int shutdownCount;

        @Override
        public synchronized WebSocket open(Request request, WebSocketListener listener) {
            activeOpenCalls++;
            maxConcurrentOpenCalls = Math.max(maxConcurrentOpenCalls, activeOpenCalls);
            try {
                FakeWebSocket socket = new FakeWebSocket(request);
                sockets.add(socket);
                listeners.add(listener);
                notifyAll();
                return socket;
            } finally {
                activeOpenCalls--;
            }
        }

        @Override
        public synchronized void shutdown() {
            shutdownCount++;
        }

        synchronized int openCount() {
            return sockets.size();
        }

        synchronized FakeWebSocket socket(int index) {
            return sockets.get(index);
        }

        synchronized WebSocketListener listener(int index) {
            return listeners.get(index);
        }

        synchronized void openLatest() {
            int index = sockets.size() - 1;
            listeners.get(index).onOpen(sockets.get(index), switchingProtocolsResponse());
        }

        synchronized void failLatest() {
            int index = sockets.size() - 1;
            listeners.get(index).onFailure(
                    sockets.get(index), new IllegalStateException("connection lost"), null);
        }

        synchronized void awaitOpenCount(int expected) throws Exception {
            long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
            while (sockets.size() < expected && System.currentTimeMillis() < deadline) {
                wait(Math.max(1, deadline - System.currentTimeMillis()));
            }
            assertEquals(expected, sockets.size());
        }

        synchronized void awaitAtLeastOneOpen() throws Exception {
            long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
            while (sockets.isEmpty() && System.currentTimeMillis() < deadline) {
                wait(Math.max(1, deadline - System.currentTimeMillis()));
            }
            assertFalse(sockets.isEmpty());
        }

        private static Response switchingProtocolsResponse() {
            return new Response.Builder()
                    .request(new Request.Builder().url("http://localhost").build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(101)
                    .message("Switching Protocols")
                    .build();
        }
    }

    private static final class FakeWebSocket implements WebSocket {
        private final Request request;
        private int closeCount;

        private FakeWebSocket(Request request) {
            this.request = request;
        }

        @Override
        public Request request() {
            return request;
        }

        @Override
        public long queueSize() {
            return 0;
        }

        @Override
        public boolean send(String text) {
            return true;
        }

        @Override
        public boolean send(ByteString bytes) {
            return true;
        }

        @Override
        public boolean close(int code, String reason) {
            closeCount++;
            return true;
        }

        @Override
        public void cancel() {}
    }
}
