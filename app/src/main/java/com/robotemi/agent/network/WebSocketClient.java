package com.robotemi.agent.network;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Resilient WebSocket client for streaming binary video data.
 * Features: exponential backoff reconnect, PING/PONG heartbeat, thread-safe sends.
 *
 * <p>Ported from TemiStream WebSocketClient — functionally identical.</p>
 */
public class WebSocketClient {
    private static final String TAG = "WebSocketClient";
    private static final long BASE_RECONNECT_DELAY_MS = 1_000L;
    private static final long MAX_RECONNECT_DELAY_MS = 15_000L;

    interface SocketFactory {
        WebSocket open(Request request, WebSocketListener listener);

        void shutdown();
    }

    interface Logger {
        void debug(String message);

        void info(String message);

        void warn(String message);
    }

    private static final class OkHttpSocketFactory implements SocketFactory {
        private final OkHttpClient client;

        private OkHttpSocketFactory() {
            client = new OkHttpClient.Builder()
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .pingInterval(10, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build();
        }

        @Override
        public WebSocket open(Request request, WebSocketListener listener) {
            return client.newWebSocket(request, listener);
        }

        @Override
        public void shutdown() {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
    }

    private static final Logger ANDROID_LOGGER = new Logger() {
        @Override
        public void debug(String message) {
            Log.d(TAG, message);
        }

        @Override
        public void info(String message) {
            Log.i(TAG, message);
        }

        @Override
        public void warn(String message) {
            Log.w(TAG, message);
        }
    };

    private final SocketFactory socketFactory;
    private final Request request;
    private final ScheduledExecutorService reconnectExecutor;
    private final Random random;
    private final Logger logger;
    private final long baseReconnectDelayMs;
    private final long maxReconnectDelayMs;
    private final Object stateLock = new Object();

    private volatile WebSocket webSocket;
    private volatile boolean isConnected = false;
    private volatile boolean isConnecting = false;
    private volatile boolean shouldReconnect = false;

    private final AtomicInteger sendCount = new AtomicInteger(0);
    private int reconnectAttempt = 0;
    private long nextConnectionAttemptId = 0;
    private long activeConnectionAttemptId = 0;
    private ScheduledFuture<?> reconnectFuture;
    private boolean isShutdown = false;

    public WebSocketClient(String url) {
        this(
                url,
                new OkHttpSocketFactory(),
                Executors.newSingleThreadScheduledExecutor(),
                new Random(),
                ANDROID_LOGGER,
                BASE_RECONNECT_DELAY_MS,
                MAX_RECONNECT_DELAY_MS);
    }

    WebSocketClient(
            String url,
            SocketFactory socketFactory,
            ScheduledExecutorService reconnectExecutor,
            Random random,
            Logger logger,
            long baseReconnectDelayMs,
            long maxReconnectDelayMs
    ) {
        this.socketFactory = socketFactory;
        this.request = new Request.Builder().url(url).build();
        this.reconnectExecutor = reconnectExecutor;
        this.random = random;
        this.logger = logger;
        this.baseReconnectDelayMs = baseReconnectDelayMs;
        this.maxReconnectDelayMs = maxReconnectDelayMs;
    }

    public void connect() {
        synchronized (stateLock) {
            if (isShutdown) {
                throw new IllegalStateException(
                        "WebSocketClient is shut down and cannot reconnect");
            }
            shouldReconnect = true;
            if (isConnected || isConnecting || hasScheduledReconnectLocked()) return;
            scheduleConnectLocked(0);
        }
    }

    /**
     * Closes the current socket while keeping this client reusable.
     */
    public void disconnect() {
        WebSocket socketToClose;
        synchronized (stateLock) {
            if (isShutdown) return;
            shouldReconnect = false;
            isConnected = false;
            isConnecting = false;
            reconnectAttempt = 0;
            activeConnectionAttemptId = 0;
            cancelScheduledReconnectLocked();
            socketToClose = webSocket;
            webSocket = null;
        }
        if (socketToClose != null) {
            socketToClose.close(1000, "Normal Termination");
        }
    }

    /**
     * Permanently releases this client. A shut down instance cannot be reused.
     */
    public void shutdown() {
        WebSocket socketToClose;
        synchronized (stateLock) {
            if (isShutdown) return;
            isShutdown = true;
            shouldReconnect = false;
            isConnected = false;
            isConnecting = false;
            reconnectAttempt = 0;
            activeConnectionAttemptId = 0;
            cancelScheduledReconnectLocked();
            socketToClose = webSocket;
            webSocket = null;
        }
        if (socketToClose != null) {
            socketToClose.close(1000, "Client Shutdown");
        }
        reconnectExecutor.shutdown();
        socketFactory.shutdown();
    }

    /**
     * Sends a binary packet (timestamp header + H.264 payload) to the server.
     */
    public void sendVideoPacket(byte[] data) {
        WebSocket socket;
        long attemptId;
        synchronized (stateLock) {
            socket = webSocket;
            attemptId = activeConnectionAttemptId;
        }
        if (isConnected && socket != null) {
            boolean sent = socket.send(ByteString.of(data));
            if (!sent) {
                logger.warn("Outgoing buffer full; link unstable.");
                handleDisconnected(attemptId, socket, "Send rejection");
                return;
            }
            int count = sendCount.incrementAndGet();
            if (count % 100 == 0) {
                logger.info("Video packets sent: " + count);
            }
        }
    }

    public boolean isConnected() {
        return isConnected;
    }

    // ─── Internal reconnection ────────────────────────────────────────

    private void scheduleConnectLocked(long delayMs) {
        if (isShutdown
                || !shouldReconnect
                || isConnected
                || isConnecting
                || hasScheduledReconnectLocked()) {
            return;
        }
        reconnectFuture = reconnectExecutor.schedule(() -> {
            final long attemptId;
            synchronized (stateLock) {
                reconnectFuture = null;
                if (isShutdown || !shouldReconnect || isConnected || isConnecting) return;
                isConnecting = true;
                attemptId = ++nextConnectionAttemptId;
                activeConnectionAttemptId = attemptId;
            }
            logger.info("Opening socket to " + request.url());
            WebSocket openedSocket = socketFactory.open(request, new WebSocketListener() {
                @Override
                public void onOpen(@NonNull WebSocket ws, @NonNull Response response) {
                    boolean closeStaleSocket = false;
                    synchronized (stateLock) {
                        if (isShutdown
                                || !shouldReconnect
                                || activeConnectionAttemptId != attemptId) {
                            closeStaleSocket = true;
                        } else {
                            webSocket = ws;
                            isConnected = true;
                            isConnecting = false;
                            reconnectAttempt = 0;
                            cancelScheduledReconnectLocked();
                        }
                    }
                    if (closeStaleSocket) {
                        ws.close(1000, "Stale Connection");
                        return;
                    }
                    logger.info("WebSocket connected.");
                }

                @Override
                public void onMessage(@NonNull WebSocket ws, @NonNull String text) {
                    logger.debug("Server message: " + text);
                }

                @Override
                public void onClosing(@NonNull WebSocket ws, int code, @NonNull String reason) {
                    ws.close(1000, null);
                }

                @Override
                public void onClosed(@NonNull WebSocket ws, int code, @NonNull String reason) {
                    handleDisconnected(attemptId, ws, "Closed: " + reason);
                }

                @Override
                public void onFailure(@NonNull WebSocket ws, @NonNull Throwable t, @Nullable Response resp) {
                    handleDisconnected(attemptId, ws, "Failure: " + t.getMessage());
                }
            });
            boolean closeStaleSocket = false;
            synchronized (stateLock) {
                if (isShutdown
                        || !shouldReconnect
                        || activeConnectionAttemptId != attemptId) {
                    closeStaleSocket = true;
                } else if (webSocket == null) {
                    webSocket = openedSocket;
                }
            }
            if (closeStaleSocket) {
                openedSocket.close(1000, "Stale Connection");
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void handleDisconnected(long attemptId, WebSocket socket, String reason) {
        synchronized (stateLock) {
            if (activeConnectionAttemptId != attemptId) return;
            if (webSocket != null && webSocket != socket) return;
            webSocket = null;
            activeConnectionAttemptId = 0;
            isConnected = false;
            isConnecting = false;
            if (isShutdown || !shouldReconnect) return;
            reconnectAttempt++;
            long delay = computeReconnectDelayMs(reconnectAttempt);
            logger.warn("Link lost (" + reason + "); retry in " + delay + "ms");
            scheduleConnectLocked(delay);
        }
    }

    private long computeReconnectDelayMs(int attempt) {
        long exp = baseReconnectDelayMs * (1L << Math.min(6, attempt - 1));
        long clamped = Math.min(maxReconnectDelayMs, exp);
        return (long) (clamped * (0.8 + 0.4 * random.nextDouble()));
    }

    private boolean hasScheduledReconnectLocked() {
        return reconnectFuture != null && !reconnectFuture.isDone();
    }

    private void cancelScheduledReconnectLocked() {
        if (reconnectFuture != null) {
            reconnectFuture.cancel(false);
            reconnectFuture = null;
        }
    }

    boolean isShutdownForTest() {
        synchronized (stateLock) {
            return isShutdown;
        }
    }

    boolean hasScheduledReconnectForTest() {
        synchronized (stateLock) {
            return hasScheduledReconnectLocked();
        }
    }

    boolean isReconnectExecutorShutdownForTest() {
        return reconnectExecutor.isShutdown();
    }
}
