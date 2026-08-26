package com.robotemi.agent.mqtt;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Thread-safe MQTT client with bounded reconnect and subscription restoration. */
public class MqttManager implements MqttConnection {
    private static final String TAG = "MqttManager";
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int KEEP_ALIVE_INTERVAL_SECONDS = 30;

    interface LogSink {
        void debug(String message);
        void info(String message);
        void warn(String message);
        void error(String message);
    }

    interface ClientFactory {
        MqttClient create(String brokerUrl, String clientId) throws MqttException;
    }

    interface RuntimeMetadata {
        long elapsedRealtimeMs();
        int processId();
        String threadName();
    }

    static final LogSink NO_OP_LOG_SINK = new LogSink() {
        @Override public void debug(String message) {}
        @Override public void info(String message) {}
        @Override public void warn(String message) {}
        @Override public void error(String message) {}
    };

    private static final LogSink ANDROID_LOG_SINK = new LogSink() {
        @Override public void debug(String message) { Log.d(TAG, message); }
        @Override public void info(String message) { Log.i(TAG, message); }
        @Override public void warn(String message) { Log.w(TAG, message); }
        @Override public void error(String message) { Log.e(TAG, message); }
    };

    private static final RuntimeMetadata ANDROID_RUNTIME_METADATA = new RuntimeMetadata() {
        @Override public long elapsedRealtimeMs() { return SystemClock.elapsedRealtime(); }
        @Override public int processId() { return Process.myPid(); }
        @Override public String threadName() { return Thread.currentThread().getName(); }
    };

    static final RuntimeMetadata JVM_RUNTIME_METADATA = new RuntimeMetadata() {
        @Override public long elapsedRealtimeMs() {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        }
        @Override public int processId() { return -1; }
        @Override public String threadName() { return Thread.currentThread().getName(); }
    };

    private static final ClientFactory PAHO_CLIENT_FACTORY =
            (brokerUrl, clientId) -> new MqttClient(
                    brokerUrl, clientId, new MemoryPersistence());
    private static final AtomicLong NEXT_MANAGER_INSTANCE_ID = new AtomicLong();

    private final String brokerUrl;
    private final String clientId;
    private final String clientIdFingerprint;
    private final String[] subscribedTopics;
    @Nullable private final Context appContext;
    private final LogSink logSink;
    private final ClientFactory clientFactory;
    private final RuntimeMetadata runtimeMetadata;
    private final String managerInstanceId;
    private final ExecutorService executor;
    private final ScheduledExecutorService reconnectScheduler;
    private final Object lifecycleLock = new Object();

    @Nullable private volatile MqttClient client;
    @Nullable private volatile MessageListener messageListener;
    @Nullable private volatile InboundMessageListener inboundMessageListener;
    @Nullable private volatile ConnectionListener connectionListener;
    @Nullable private ScheduledFuture<?> reconnectFuture;
    private boolean connectAttemptScheduled;
    private boolean connectInProgress;
    private boolean shutdown;
    private boolean shouldReconnect;
    private int reconnectAttempt;
    private long nextConnectAttemptId;
    private ConnectionState currentConnectionState = ConnectionState.DISCONNECTED;
    @Nullable private volatile String lastSuccessfulSubscription;

    private static final class ConnectAttempt {
        final long id;
        final long startedElapsedMs;

        ConnectAttempt(long id, long startedElapsedMs) {
            this.id = id;
            this.startedElapsedMs = startedElapsedMs;
        }
    }

    public MqttManager(
            @NonNull String brokerUrl,
            @NonNull String clientId,
            @NonNull String[] subscribedTopics) {
        this(brokerUrl, clientId, subscribedTopics, null, ANDROID_LOG_SINK,
                PAHO_CLIENT_FACTORY, ANDROID_RUNTIME_METADATA);
    }

    public MqttManager(
            @NonNull String brokerUrl,
            @NonNull String clientId,
            @NonNull String[] subscribedTopics,
            @Nullable Context context) {
        this(brokerUrl, clientId, subscribedTopics, context, ANDROID_LOG_SINK,
                PAHO_CLIENT_FACTORY, ANDROID_RUNTIME_METADATA);
    }

    MqttManager(
            @NonNull String brokerUrl,
            @NonNull String clientId,
            @NonNull String[] subscribedTopics,
            @Nullable Context context,
            @NonNull LogSink logSink) {
        this(brokerUrl, clientId, subscribedTopics, context, logSink, PAHO_CLIENT_FACTORY,
                ANDROID_RUNTIME_METADATA);
    }

    MqttManager(
            @NonNull String brokerUrl,
            @NonNull String clientId,
            @NonNull String[] subscribedTopics,
            @Nullable Context context,
            @NonNull LogSink logSink,
            @NonNull ClientFactory clientFactory) {
        this(brokerUrl, clientId, subscribedTopics, context, logSink, clientFactory,
                ANDROID_RUNTIME_METADATA);
    }

    MqttManager(
            @NonNull String brokerUrl,
            @NonNull String clientId,
            @NonNull String[] subscribedTopics,
            @Nullable Context context,
            @NonNull LogSink logSink,
            @NonNull ClientFactory clientFactory,
            @NonNull RuntimeMetadata runtimeMetadata) {
        this.brokerUrl = brokerUrl;
        this.clientId = clientId;
        this.clientIdFingerprint = fingerprintClientId(clientId);
        this.subscribedTopics = subscribedTopics.clone();
        this.appContext = context == null ? null : context.getApplicationContext();
        this.logSink = logSink;
        this.clientFactory = clientFactory;
        this.runtimeMetadata = runtimeMetadata;
        this.managerInstanceId = "mqtt-manager-" + NEXT_MANAGER_INSTANCE_ID.incrementAndGet();
        this.executor = Executors.newSingleThreadExecutor();
        this.reconnectScheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @Override
    public void setMessageListener(@Nullable MessageListener listener) {
        this.messageListener = listener;
    }

    @Override
    public void setInboundMessageListener(@Nullable InboundMessageListener listener) {
        this.inboundMessageListener = listener;
    }

    @Override
    public void setConnectionListener(@Nullable ConnectionListener listener) {
        this.connectionListener = listener;
    }

    @Override
    public void connect() {
        synchronized (lifecycleLock) {
            if (shutdown) {
                logDiagnostic(false, null, "connect_ignored_after_shutdown",
                        currentStateTransition(), "outcome=suppressed");
                return;
            }
            shouldReconnect = true;
            boolean enqueued = scheduleConnectLocked(0L, "connect_requested", null);
            logDiagnostic(false, null, enqueued ? "connect_enqueued" : "connect_suppressed",
                    currentStateTransition(), "trigger=connect_requested delay_ms=0");
        }
    }

    @Override
    public void disconnect() {
        synchronized (lifecycleLock) {
            shouldReconnect = false;
            reconnectAttempt = 0;
            cancelReconnectLocked();
        }
        submit(() -> disconnectInternal("local_disconnect"));
    }

    @Override
    public void shutdown() {
        synchronized (lifecycleLock) {
            if (shutdown) return;
            shutdown = true;
            shouldReconnect = false;
            cancelReconnectLocked();
        }
        submit(() -> disconnectInternal("shutdown"));
        reconnectScheduler.shutdownNow();
        executor.shutdown();
    }

    @Override
    public void publish(
            @NonNull String topic,
            @NonNull String jsonPayload,
            @Nullable MqttConnection.PublishCallback callback) {
        submit(() -> {
            boolean success = false;
            try {
                MqttClient current = client;
                if (current != null && current.isConnected()) {
                    MqttMessage message = new MqttMessage(
                            jsonPayload.getBytes(StandardCharsets.UTF_8));
                    message.setQos(MqttTopics.QOS);
                    message.setRetained(false);
                    current.publish(topic, message);
                    success = true;
                } else {
                    logSink.warn("Cannot publish while disconnected topic="
                            + sanitizeDiagnosticText(topic));
                }
            } catch (MqttException e) {
                logSink.error("Publish failed topic=" + sanitizeDiagnosticText(topic) + " "
                        + sanitizedThrowableDetails(e));
            } finally {
                if (callback != null) callback.onComplete(success);
            }
        }, callback);
    }

    @Override
    public boolean isConnected() {
        MqttClient current = client;
        return current != null && current.isConnected();
    }

    @Nullable
    public String lastSuccessfulSubscription() {
        return lastSuccessfulSubscription;
    }

    private void connectInternal() {
        final ConnectAttempt attempt;
        synchronized (lifecycleLock) {
            connectAttemptScheduled = false;
            if (shutdown || !shouldReconnect || connectInProgress) return;
            if (client != null && client.isConnected()) return;
            connectInProgress = true;
            attempt = new ConnectAttempt(
                    ++nextConnectAttemptId, runtimeMetadata.elapsedRealtimeMs());
        }

        logDiagnostic(false, attempt, "executor_start", currentStateTransition(),
                "executor=connect_serial");

        ConnectionState attemptState;
        synchronized (lifecycleLock) {
            attemptState = reconnectAttempt == 0
                    ? ConnectionState.CONNECTING : ConnectionState.RECONNECTING;
        }
        notifyState(attemptState, "connect_attempt", attempt);

        MqttClient newClient = null;
        boolean retryAfterConnectFailure = false;
        try {
            if (!networkAvailable()) {
                throw new MqttException(MqttException.REASON_CODE_CLIENT_EXCEPTION);
            }
            closeClient();
            logDiagnostic(false, attempt, "client_construction_start", currentStateTransition(),
                    redactedUriFields() + " persistence=memory socket_factory=default");
            newClient = clientFactory.create(brokerUrl, clientId);
            logDiagnostic(false, attempt, "client_construction_end", currentStateTransition(),
                    "outcome=created persistence=memory socket_factory=default");
            final MqttClient callbackClient = newClient;
            newClient.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    if (client != callbackClient) return;
                    String reason = cause == null || cause.getMessage() == null
                            ? "unknown" : cause.getMessage();
                    logDiagnostic(true, attempt, "callback_connection_lost", currentStateTransition(),
                            "callback_outcome=failure " + sanitizedThrowableDetails(
                                    cause == null ? new RuntimeException("connection_lost") : cause));
                    notifyDisconnected("connection_lost:" + reason, ConnectionState.RECONNECTING,
                            attempt);
                    scheduleReconnect("connection_lost", attempt);
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    byte[] payloadBytes = message.getPayload();
                    String payload = new String(payloadBytes, StandardCharsets.UTF_8);
                    logSink.debug(InboundMqttLogSummary.describe(
                            InboundMqttLogSummary.Category.TRANSPORT,
                            message.isRetained(), payloadBytes));
                    InboundMessageListener inbound = inboundMessageListener;
                    if (inbound != null) {
                        inbound.onMessage(topic, payload, message.isRetained());
                    } else {
                        MessageListener legacy = messageListener;
                        if (legacy != null) legacy.onMessage(topic, payload);
                    }
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {}
            });
            logDiagnostic(false, attempt, "callback_registered", currentStateTransition(),
                    "callback_outcome=registered");
            synchronized (lifecycleLock) {
                if (shutdown || !shouldReconnect) {
                    closeClientIfCurrent(newClient);
                    return;
                }
                client = newClient;
            }

            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(false);
            options.setConnectionTimeout(CONNECT_TIMEOUT_SECONDS);
            options.setKeepAliveInterval(KEEP_ALIVE_INTERVAL_SECONDS);
            options.setAutomaticReconnect(false);

            logDiagnostic(false, attempt, "paho_connect_start", currentStateTransition(),
                    redactedUriFields() + " connect_mode=synchronous token_status=no_token"
                            + " timeout_seconds=" + CONNECT_TIMEOUT_SECONDS
                            + " keepalive_seconds=" + KEEP_ALIVE_INTERVAL_SECONDS
                            + " clean_session=false automatic_reconnect=false"
                            + " protocol_mode=paho_default");
            newClient.connect(options);
            logDiagnostic(false, attempt, "connack_accepted", currentStateTransition(),
                    "callback_outcome=success");
            for (String topic : subscribedTopics) {
                newClient.subscribe(topic, MqttTopics.QOS);
                lastSuccessfulSubscription = topic;
                logSink.info("Subscription restored topic=" + sanitizeDiagnosticText(topic));
            }
            synchronized (lifecycleLock) {
                reconnectAttempt = 0;
            }
            notifyState(ConnectionState.CONNECTED, "subscriptions_restored", attempt);
            ConnectionListener listener = connectionListener;
            if (listener != null) {
                listener.onConnected();
                logDiagnostic(false, attempt, "connection_listener_connected",
                        currentStateTransition(), "callback_outcome=success");
            }
        } catch (MqttException | RuntimeException e) {
            if (newClient != null) {
                closeClientIfCurrent(newClient);
            }
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            int pahoReasonCode = e instanceof MqttException
                    ? ((MqttException) e).getReasonCode() : -1;
            ConnectionState failureState = networkAvailable()
                    ? ConnectionState.RECONNECTING : ConnectionState.DEGRADED;
            logDiagnostic(true, attempt, "paho_connect_failed", currentStateTransition(),
                    redactedUriFields() + " paho_reason_code=" + pahoReasonCode + " "
                            + sanitizedThrowableDetails(e));
            notifyDisconnected("connect_failed:" + reason, failureState, attempt);
            retryAfterConnectFailure = true;
        } finally {
            synchronized (lifecycleLock) {
                connectInProgress = false;
            }
        }
        if (retryAfterConnectFailure) {
            scheduleReconnect("connect_failed", attempt);
        }
    }

    private void scheduleReconnect(String trigger, ConnectAttempt attempt) {
        synchronized (lifecycleLock) {
            if (!shouldReconnect || shutdown) {
                logDiagnostic(false, attempt, "retry_suppressed", currentStateTransition(),
                        "trigger=" + trigger + " should_reconnect=" + shouldReconnect
                                + " shutdown=" + shutdown);
                return;
            }
            long delay = reconnectDelayMs(reconnectAttempt++);
            boolean enqueued = scheduleConnectLocked(delay, trigger, attempt);
            logDiagnostic(false, attempt, enqueued ? "retry_enqueued" : "retry_suppressed",
                    currentStateTransition(), "trigger=" + trigger + " delay_ms=" + delay);
        }
    }

    private boolean scheduleConnectLocked(
            long delayMs, String reason, @Nullable ConnectAttempt schedulingAttempt) {
        if (connectAttemptScheduled || connectInProgress
                || (client != null && client.isConnected()) || shutdown) {
            return false;
        }
        connectAttemptScheduled = true;
        try {
            reconnectFuture = reconnectScheduler.schedule(() -> {
                logDiagnostic(false, schedulingAttempt, "executor_submit", currentStateTransition(),
                        "trigger=" + reason + " delay_ms=" + delayMs);
                try {
                    executor.submit(this::connectInternal);
                } catch (RejectedExecutionException e) {
                    synchronized (lifecycleLock) {
                        connectAttemptScheduled = false;
                    }
                    logDiagnostic(true, schedulingAttempt, "executor_submit_rejected",
                            currentStateTransition(), "trigger=" + reason);
                }
            }, delayMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (RejectedExecutionException e) {
            connectAttemptScheduled = false;
            logDiagnostic(true, schedulingAttempt, "scheduler_rejected", currentStateTransition(),
                    "trigger=" + reason);
            return false;
        }
    }

    private void cancelReconnectLocked() {
        connectAttemptScheduled = false;
        if (reconnectFuture != null) {
            reconnectFuture.cancel(false);
            reconnectFuture = null;
        }
    }

    private long reconnectDelayMs(int attempt) {
        return MqttReconnectPolicy.delayMs(attempt);
    }

    private void disconnectInternal(String reason) {
        MqttClient current = client;
        if (current != null) {
            try {
                if (current.isConnected()) current.disconnect();
            } catch (MqttException e) {
                logSink.warn("Disconnect failed " + sanitizedThrowableDetails(e));
            } finally {
                closeClientIfCurrent(current);
            }
        }
        notifyDisconnected(reason, ConnectionState.DISCONNECTED);
    }

    private void closeClient() {
        MqttClient current = client;
        if (current != null) closeClientIfCurrent(current);
    }

    private void closeClientIfCurrent(MqttClient target) {
        synchronized (lifecycleLock) {
            if (client == target) client = null;
        }
        try {
            target.close();
        } catch (Exception ignored) {}
    }

    private void submit(Runnable runnable) {
        submit(runnable, null);
    }

    private void submit(Runnable runnable, @Nullable MqttConnection.PublishCallback callback) {
        try {
            executor.submit(runnable);
        } catch (RejectedExecutionException e) {
            if (callback != null) callback.onComplete(false);
            logSink.warn("MQTT executor rejected operation");
        }
    }

    private boolean networkAvailable() {
        if (appContext == null) return true;
        ConnectivityManager manager =
                (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return true;
        NetworkInfo info = manager.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    private String redactedUriFields() {
        int schemeEnd = brokerUrl.indexOf("://");
        String scheme = schemeEnd > 0 ? brokerUrl.substring(0, schemeEnd) : "mqtt";
        String authority = schemeEnd > 0 ? brokerUrl.substring(schemeEnd + 3) : brokerUrl;
        int pathStart = authority.indexOf('/');
        if (pathStart >= 0) authority = authority.substring(0, pathStart);
        int portSeparator = authority.lastIndexOf(':');
        String port = portSeparator >= 0 ? authority.substring(portSeparator + 1) : "default";
        if (!port.matches("[0-9]{1,5}")) port = "redacted";
        return "uri_scheme=" + sanitizeDiagnosticText(scheme)
                + " uri_host=<redacted> uri_port=" + port;
    }

    private void notifyDisconnected(String reason, ConnectionState state) {
        notifyDisconnected(reason, state, null);
    }

    private void notifyDisconnected(
            String reason, ConnectionState state, @Nullable ConnectAttempt attempt) {
        notifyState(state, reason, attempt);
        ConnectionListener listener = connectionListener;
        if (listener != null) listener.onDisconnected(reason);
    }

    private void notifyState(ConnectionState state, String reason) {
        notifyState(state, reason, null);
    }

    private void notifyState(
            ConnectionState state, String reason, @Nullable ConnectAttempt attempt) {
        ConnectionState previous;
        synchronized (lifecycleLock) {
            previous = currentConnectionState;
            currentConnectionState = state;
        }
        logDiagnostic(false, attempt, "state_transition", previous + "->" + state,
                "reason_kind=" + reasonKind(reason));
        ConnectionListener listener = connectionListener;
        if (listener != null) listener.onStateChanged(state, reason);
    }

    private void logDiagnostic(
            boolean error,
            @Nullable ConnectAttempt attempt,
            String phase,
            String stateTransition,
            String details) {
        long elapsedMs = attempt == null ? 0L
                : Math.max(0L, runtimeMetadata.elapsedRealtimeMs() - attempt.startedElapsedMs);
        String message = "MQTT_CONNECT_DIAG manager_instance_id=" + managerInstanceId
                + " process_id=" + runtimeMetadata.processId()
                + " thread_name=" + sanitizeDiagnosticText(runtimeMetadata.threadName())
                + " attempt_id=" + (attempt == null ? "none" : attempt.id)
                + " elapsed_ms=" + elapsedMs
                + " state_transition=" + sanitizeDiagnosticText(stateTransition)
                + " event_phase=" + sanitizeDiagnosticText(phase)
                + " client_id_policy=stable_fingerprinted client_id_fingerprint="
                + clientIdFingerprint + " "
                + sanitizeDiagnosticText(details);
        if (error) {
            logSink.error(message);
        } else {
            logSink.info(message);
        }
    }

    private String currentStateTransition() {
        synchronized (lifecycleLock) {
            return currentConnectionState + "->" + currentConnectionState;
        }
    }

    private static String reasonKind(String reason) {
        if (reason == null || reason.isEmpty()) return "none";
        int separator = reason.indexOf(':');
        return sanitizeDiagnosticText(separator >= 0 ? reason.substring(0, separator) : reason);
    }

    private static String fingerprintClientId(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder fingerprint = new StringBuilder("sha256:");
            for (byte valueByte : digest) {
                fingerprint.append(String.format("%02x", valueByte & 0xff));
            }
            return fingerprint.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String sanitizedThrowableDetails(Throwable error) {
        StringBuilder causes = new StringBuilder();
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getCause()) {
            if (causes.length() > 0) causes.append("->");
            causes.append(current.getClass().getSimpleName()).append(':')
                    .append(sanitizeDiagnosticText(current.getMessage()));
        }
        if (current != null) causes.append("->truncated");

        StringBuilder frames = new StringBuilder();
        StackTraceElement[] stack = error.getStackTrace();
        for (int index = 0; index < stack.length && index < 12; index++) {
            if (frames.length() > 0) frames.append('|');
            frames.append(sanitizeDiagnosticText(stack[index].toString()));
        }
        return "exception_class=" + error.getClass().getSimpleName()
                + " cause_chain=" + causes
                + " stack_trace=" + frames;
    }

    private static String sanitizeDiagnosticText(@Nullable String value) {
        if (value == null || value.isEmpty()) return "none";
        String sanitized = value
                .replaceAll("(?i)([a-z][a-z0-9+.-]*://)([^\\s/@]+@)?[^\\s/:]+(?::[0-9]+)?", "$1<redacted>")
                .replaceAll("\\b(?:25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})(?:\\.(?:25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})){3}\\b", "<redacted-ip>")
                .replaceAll("(?i)(password|passwd|secret|token|username|user|clientid)\\s*[:=]\\s*[^\\s,;]+", "$1=<redacted>")
                .replaceAll("[\\r\\n\\t]+", " ");
        return sanitized.length() <= 512 ? sanitized : sanitized.substring(0, 512) + "<truncated>";
    }
}
