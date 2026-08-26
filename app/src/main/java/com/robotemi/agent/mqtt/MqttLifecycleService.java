package com.robotemi.agent.mqtt;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.app.NotificationCompat;

import com.robotemi.agent.BuildConfig;
import com.robotemi.agent.MainActivity;
import com.robotemi.agent.command.CanonicalCommandRuntime;
import com.robotemi.agent.command.CanonicalCommandValidator.CanonicalAction;
import com.robotemi.agent.command.CanonicalCommandValidator.CanonicalCommand;
import com.robotemi.agent.command.CommandLedger;
import com.robotemi.agent.command.SharedPreferencesCommandLedger;
import com.robotemi.agent.media.v11.MediaV11PlaybackBinding;
import com.robotemi.agent.media.v11.MediaV11Parser;
import com.robotemi.agent.media.v11.MediaV11ServiceRuntime;
import com.robotemi.agent.media.v11.MediaV11LegacyMigration;
import com.robotemi.sdk.Robot;
import com.robotemi.sdk.TtsRequest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground owner for the process-independent MQTT connection lifecycle. */
public final class MqttLifecycleService extends Service {
    private static final String TAG = "MqttLifecycleService";
    private static final String CHANNEL_ID = "temi_mqtt_connection";
    private static final int NOTIFICATION_ID = 4101;
    private static final int MAX_BUFFERED_MESSAGES = MqttIngressLimits.MAX_BUFFERED_MESSAGES;
    private static final long MAX_BUFFERED_BYTES = MqttIngressLimits.MAX_BUFFERED_BYTES;

    private final IBinder binder = new LocalBinder();
    private final ArrayDeque<InboundMessage> bufferedMessages = new ArrayDeque<>();
    private long bufferedMessageBytes;
    @Nullable private SingleActiveMqttBroker broker;
    @Nullable private SingleActiveMqttBroker.Listener attachedListener;
    private MqttConnection.ConnectionState state = MqttConnection.ConnectionState.DISCONNECTED;
    private long lastConnectedAtMs;
    @Nullable private String lastDisconnectReason;
    @Nullable private String clientId;
    @Nullable private SharedPreferencesMqttRuntimeSettings runtimeSettings;
    @Nullable private Handler canonicalHandler;
    @Nullable private CanonicalCommandRuntime canonicalCommandRuntime;
    @Nullable private CommandLedger commandLedger;
    @Nullable private CommandLedger.Persistence commandLedgerPersistence;
    @Nullable private MediaV11ServiceRuntime mediaV11ServiceRuntime;
    @Nullable private final Boolean testMediaV11Enabled;
    private final Set<String> canonicalRuntimeTerminalized = new HashSet<>();
    private final AtomicBoolean canonicalOutboxFlushInProgress = new AtomicBoolean(false);
    private final AtomicBoolean mediaOutboxFlushInProgress = new AtomicBoolean(false);
    @Nullable private Robot robot;
    @Nullable private CanonicalSpeechPort canonicalSpeechPort;
    private CanonicalCommandDiagnostics diagnostics = CanonicalCommandDiagnostics.noOp();
    private final CanonicalCommandIngress canonicalCommandIngress;
    @Nullable private ListenerRegistration attachedListenerRegistration;
    private long listenerGeneration;
    private final Robot.TtsListener canonicalTtsListener =
            ttsRequest -> {
                if (canonicalCommandRuntime == null || ttsRequest == null) return;
                TtsRequest.Status status = ttsRequest.getStatus();
                if (status != TtsRequest.Status.COMPLETED
                        && status != TtsRequest.Status.ERROR) {
                    return;
                }
                diagnostics.record(
                        "tts_callback", "robot_tts", null, null, null,
                        status.name(), "received", null);
                Runnable resolution = () -> canonicalCommandRuntime.onTtsStatusChanged(
                        ttsRequest.getId(), status == TtsRequest.Status.COMPLETED);
                if (canonicalHandler == null) {
                    resolution.run();
                } else {
                    canonicalHandler.post(resolution);
                }
            };

    public MqttLifecycleService() {
        this(null, null, null, null, null, null, null);
    }

    @VisibleForTesting
    MqttLifecycleService(
            @Nullable CanonicalSpeechPort speechPort,
            @Nullable CommandLedger.Persistence ledgerPersistence,
            @Nullable CanonicalCommandRuntime.Scheduler scheduler,
            @Nullable SingleActiveMqttBroker testBroker,
            @Nullable SharedPreferencesMqttRuntimeSettings testRuntimeSettings,
            @Nullable CanonicalCommandDiagnostics testDiagnostics) {
        this(speechPort, ledgerPersistence, scheduler, testBroker, testRuntimeSettings,
                testDiagnostics, null);
    }

    @VisibleForTesting
    MqttLifecycleService(
            @Nullable CanonicalSpeechPort speechPort,
            @Nullable CommandLedger.Persistence ledgerPersistence,
            @Nullable CanonicalCommandRuntime.Scheduler scheduler,
            @Nullable SingleActiveMqttBroker testBroker,
            @Nullable SharedPreferencesMqttRuntimeSettings testRuntimeSettings,
            @Nullable CanonicalCommandDiagnostics testDiagnostics,
            @Nullable Boolean testMediaV11Enabled) {
        this.testMediaV11Enabled = testMediaV11Enabled;
        canonicalSpeechPort = speechPort;
        diagnostics = testDiagnostics == null
                ? createDefaultDiagnostics() : testDiagnostics;
        canonicalCommandIngress = new CanonicalCommandIngress(
                this::onCanonicalCommandValidated, diagnostics);
        broker = testBroker;
        runtimeSettings = testRuntimeSettings;
        if (ledgerPersistence != null) {
            commandLedgerPersistence = ledgerPersistence;
            commandLedger = new CommandLedger(ledgerPersistence);
        }
        if (scheduler != null) {
            canonicalCommandRuntime = new CanonicalCommandRuntime(
                    scheduler,
                    CanonicalCommandRuntime.DEFAULT_TTS_TIMEOUT_MS,
                    this::persistAndPublishCanonicalTtsResult);
            if (commandLedger != null && commandLedgerPersistence != null) {
                mediaV11ServiceRuntime = createMediaV11ServiceRuntime(scheduler);
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        canonicalHandler = new Handler(Looper.getMainLooper());
        if (commandLedger == null) {
            commandLedgerPersistence = new SharedPreferencesCommandLedger(
                    getSharedPreferences("canonical_commands", MODE_PRIVATE));
            commandLedger = new CommandLedger(commandLedgerPersistence);
        }
        try {
            MediaV11LegacyMigration.migrate(
                    commandLedgerPersistence,
                    getSharedPreferences("canonical_media_v11", MODE_PRIVATE),
                    new MediaV11LegacyMigration.Clock() {
                        @Override public long nowMs() { return System.currentTimeMillis(); }
                        @Override public String nowTimestamp() {
                            java.text.SimpleDateFormat format =
                                    new java.text.SimpleDateFormat(
                                            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
                            format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                            return format.format(new java.util.Date());
                        }
                    });
        } catch (CommandLedger.StoreException migrationFailure) {
            throw new IllegalStateException("media_legacy_migration_failed", migrationFailure);
        }
        if (canonicalCommandRuntime == null) {
            canonicalCommandRuntime = new CanonicalCommandRuntime(
                    (task, delayMs) -> {
                        canonicalHandler.postDelayed(task, delayMs);
                        return () -> canonicalHandler.removeCallbacks(task);
                    },
                    CanonicalCommandRuntime.DEFAULT_TTS_TIMEOUT_MS,
                    this::persistAndPublishCanonicalTtsResult);
        }
        robot = Robot.getInstance();
        robot.addTtsListener(canonicalTtsListener);
        if (canonicalSpeechPort == null) {
            canonicalSpeechPort = request -> {
                if (robot == null) throw new IllegalStateException("robot_unavailable");
                robot.speak(request);
            };
        }
        if (mediaV11ServiceRuntime == null && commandLedger != null
                && commandLedgerPersistence != null) {
            mediaV11ServiceRuntime = createMediaV11ServiceRuntime(
                    (task, delayMs) -> {
                        canonicalHandler.postDelayed(task, delayMs);
                        return () -> canonicalHandler.removeCallbacks(task);
                    });
        }
        if (mediaV11ServiceRuntime != null) {
            mediaV11ServiceRuntime.reconcileAfterProcessRestart();
        }
        boolean mediaEnabled = mediaV11EnabledForRuntime();
        diagnostics.record(
                "media_config", "service_status", null, null, null,
                mediaEnabled ? "ENABLED" : "DISABLED",
                "media_v1_1_enabled=" + mediaEnabled, null);
        if (broker == null) {
            broker = new SingleActiveMqttBroker(
                    (endpoint, unusedClientId, subscribedTopics) -> new MqttManager(
                            endpoint.brokerUrl(),
                            clientIdFor(endpoint),
                            subscribedTopics,
                            getApplicationContext()),
                    endpoint -> MqttClientIdentity.forRobot(getPackageName(), endpoint.robotId()),
                    createBrokerListener(),
                    BuildConfig.RESIDENT_IDENTITY_ENABLED || BuildConfig.CARE_REPORT_ENABLED,
                    BuildConfig.CARE_REPORT_ENABLED);
        }
        clientId = MqttClientIdentity.forRobot(getPackageName(), "unconfigured");
        if (runtimeSettings == null) {
            runtimeSettings = new SharedPreferencesMqttRuntimeSettings(
                    getSharedPreferences("mqtt_runtime", MODE_PRIVATE));
        }
    }

    @VisibleForTesting
    SingleActiveMqttBroker.Listener brokerListenerForTest() {
        return createBrokerListener();
    }

    @VisibleForTesting
    Robot.TtsListener ttsListenerForTest() {
        return canonicalTtsListener;
    }

    @VisibleForTesting
    void bindBrokerForTest(SingleActiveMqttBroker testBroker) {
        if (broker != null) throw new IllegalStateException("mqtt_test_broker_already_bound");
        broker = testBroker;
    }

    private SingleActiveMqttBroker.Listener createBrokerListener() {
        return new SingleActiveMqttBroker.Listener() {
            @Override
            public void onMessage(@NonNull String topic, @NonNull String payload) {
                onBrokerMessage(topic, payload, false);
            }

            @Override
            public void onMessage(
                    @NonNull String topic,
                    @NonNull String payload,
                    boolean retained) {
                onBrokerMessage(topic, payload, retained);
            }

            @Override
            public void onConnected() {
                lastConnectedAtMs = System.currentTimeMillis();
                notifyAttachedConnected();
                updateNotification();
                flushCanonicalRuntimeOutbox();
                flushMediaV11RuntimeOutbox();
            }

            @Override
            public void onDisconnected(String reason) {
                lastDisconnectReason = reason;
                notifyAttachedDisconnected(reason);
                updateNotification();
            }

            @Override
            public void onStateChanged(
                    MqttConnection.ConnectionState nextState, String reason) {
                state = nextState;
                if (nextState == MqttConnection.ConnectionState.DISCONNECTED
                        || nextState == MqttConnection.ConnectionState.DEGRADED) {
                    lastDisconnectReason = reason;
                }
                SingleActiveMqttBroker.Listener listener = attachedListener;
                if (listener != null) listener.onStateChanged(nextState, reason);
                updateNotification();
            }
        };
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());
        if (broker != null) {
            MqttEndpointSelection selection = runtimeSettings.loadEndpoint();
            broker.apply(selection, 0, runtimeSettings.outboxOwnerFingerprint());
            if (selection.status() == MqttEndpointSelection.Status.VALID) {
                broker.connect();
            }
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        if (robot != null) robot.removeTtsListener(canonicalTtsListener);
        if (canonicalHandler != null) canonicalHandler.removeCallbacksAndMessages(null);
        if (mediaV11ServiceRuntime != null) {
            mediaV11ServiceRuntime.detachBinding(
                    mediaV11ServiceRuntime.bindingGenerationForTest());
        }
        if (broker != null) broker.shutdown();
        synchronized (this) {
            listenerGeneration++;
            attachedListener = null;
            attachedListenerRegistration = null;
        }
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
        super.onDestroy();
    }

    /** Activity-only observer registration; it never owns canonical execution. */
    public static final class ListenerRegistration {
        private final long generation;

        private ListenerRegistration(long generation) {
            this.generation = generation;
        }
    }

    public final class LocalBinder extends Binder {
        public MqttLifecycleService service() {
            return MqttLifecycleService.this;
        }

        public SingleActiveMqttBroker broker() {
            if (broker == null) throw new IllegalStateException("mqtt_service_not_created");
            return broker;
        }

        public ListenerRegistration attachListener(SingleActiveMqttBroker.Listener listener) {
            return MqttLifecycleService.this.attachListener(listener);
        }

        public void detachListener(ListenerRegistration registration) {
            MqttLifecycleService.this.detachListener(registration);
        }

        public void detachListener() {
            MqttLifecycleService.this.detachListener();
        }

        public void attachCanonicalTtsListener(CanonicalTtsListener listener) {
            MqttLifecycleService.this.attachCanonicalTtsListener(listener);
        }

        public void detachCanonicalTtsListener() {
            MqttLifecycleService.this.detachCanonicalTtsListener();
        }

        public long attachMediaV11PlaybackBinding(MediaV11PlaybackBinding binding) {
            return MqttLifecycleService.this.attachMediaV11PlaybackBinding(binding);
        }

        public void detachMediaV11PlaybackBinding(long generation) {
            MqttLifecycleService.this.detachMediaV11PlaybackBinding(generation);
        }

        public boolean beginCanonicalTts(
                UUID requestId,
                String commandId,
                String eventId,
                String robotId,
                String actionId,
                String actionType,
                boolean terminalAction) {
            return MqttLifecycleService.this.beginCanonicalTts(
                    requestId, commandId, eventId, robotId, actionId, actionType, terminalAction);
        }

        public void cancelCanonicalTts(UUID requestId) {
            MqttLifecycleService.this.cancelCanonicalTts(requestId);
        }

        public boolean isCanonicalCommandTerminalized(String commandId) {
            return MqttLifecycleService.this.isCanonicalCommandTerminalized(commandId);
        }
    }

    public interface CanonicalTtsListener {
        void onCanonicalTtsResolved(CanonicalCommandRuntime.Resolution resolution);
    }

    @Nullable
    public String clientId() { return clientId; }

    public MqttConnection.ConnectionState state() { return state; }

    public long lastConnectedAtMs() { return lastConnectedAtMs; }

    @Nullable
    public String lastDisconnectReason() { return lastDisconnectReason; }

    @VisibleForTesting
    MediaV11ServiceRuntime mediaV11RuntimeForTest() {
        return mediaV11ServiceRuntime;
    }

    @VisibleForTesting
    void flushMediaV11RuntimeOutboxForTest() {
        flushMediaV11RuntimeOutbox();
    }

    private synchronized long attachMediaV11PlaybackBinding(MediaV11PlaybackBinding binding) {
        if (mediaV11ServiceRuntime == null) {
            throw new IllegalStateException("media_service_runtime_unavailable");
        }
        return mediaV11ServiceRuntime.attachBinding(binding);
    }

    private synchronized void detachMediaV11PlaybackBinding(long generation) {
        if (mediaV11ServiceRuntime != null) {
            mediaV11ServiceRuntime.detachBinding(generation);
        }
    }

    private synchronized void attachCanonicalTtsListener(CanonicalTtsListener listener) {
        if (canonicalCommandRuntime != null) {
            canonicalCommandRuntime.attachListener(
                    listener == null ? null : listener::onCanonicalTtsResolved);
        }
    }

    private synchronized void detachCanonicalTtsListener() {
        if (canonicalCommandRuntime != null) canonicalCommandRuntime.detachListener();
    }

    private synchronized boolean beginCanonicalTts(
            UUID requestId,
            String commandId,
            String eventId,
            String robotId,
            String actionId,
            String actionType,
            boolean terminalAction) {
        return canonicalCommandRuntime != null
                && canonicalCommandRuntime.beginTts(
                        requestId, commandId, eventId, robotId, actionId, actionType, terminalAction);
    }

    private synchronized void cancelCanonicalTts(UUID requestId) {
        if (canonicalCommandRuntime != null) canonicalCommandRuntime.cancelTts(requestId);
    }

    private synchronized boolean isCanonicalCommandTerminalized(String commandId) {
        return commandId != null && canonicalRuntimeTerminalized.contains(commandId);
    }

    private void persistAndPublishCanonicalTtsResult(
            CanonicalCommandRuntime.Resolution resolution) {
        if (resolution == null || !resolution.isTerminalAction()
                || resolution.getEventId() == null || resolution.getRobotId() == null
                || commandLedger == null) {
            return;
        }
        String status = "completed".equals(resolution.getStatus()) ? "success" : "failed";
        JsonArray results = new JsonArray();
        results.add(createCanonicalActionResult(
                resolution.getActionId(), resolution.getActionType(),
                resolution.getStatus(), resolution.getError()));
        String payload = buildCanonicalResultPayload(
                resolution.getCommandId(), resolution.getEventId(), resolution.getRobotId(),
                status, results, resolution.getError());
        CommandLedger.State terminalState = "failed".equals(status)
                ? CommandLedger.State.FAILED : CommandLedger.State.COMPLETED;
        if (payload != null && commandLedger.markResultPending(
                resolution.getCommandId(), payload, terminalState, System.currentTimeMillis())) {
            diagnostics.record(
                    "terminal", "command_result", resolution.getCommandId(),
                    resolution.getEventId(), resolution.getActionId(),
                    "RESULT_PENDING", "persisted", null);
            synchronized (this) {
                canonicalRuntimeTerminalized.add(resolution.getCommandId());
            }
            MqttEndpoint endpoint = broker == null ? null : broker.endpoint();
            if (endpoint != null && runtimeSettings != null
                    && !runtimeSettings.bindOutboxOwner(endpoint)) {
                Log.e(TAG, "Service-owned command result outbox endpoint mismatch");
            }
            flushCanonicalRuntimeOutbox();
        }
    }

    private JsonObject createCanonicalActionResult(
            String actionId, String actionType, String status, String error) {
        JsonObject result = new JsonObject();
        result.addProperty("action_id", actionId == null ? "unknown_action" : actionId);
        result.addProperty("type", actionType == null ? "speak" : actionType);
        result.addProperty("status", status);
        if (error != null) result.addProperty("error", error);
        return result;
    }

    private String buildCanonicalResultPayload(
            String commandId, String eventId, String robotId, String status,
            JsonArray results, String error) {
        JsonObject payload = new JsonObject();
        payload.addProperty("schema_version", "1.0");
        payload.addProperty("command_id", commandId);
        payload.addProperty("event_id", eventId);
        payload.addProperty("robot_id", robotId);
        payload.addProperty("status", status);
        payload.addProperty("finished_at_ms", System.currentTimeMillis());
        payload.add("results", results);
        if (error != null) payload.addProperty("error", error);
        return payload.toString();
    }

    private void flushCanonicalRuntimeOutbox() {
        if (commandLedger == null || broker == null
                || !canonicalOutboxFlushInProgress.compareAndSet(false, true)) {
            return;
        }
        List<CommandLedger.Record> pending = commandLedger.pendingResults();
        if (pending.isEmpty() || !broker.isConnected()) {
            canonicalOutboxFlushInProgress.set(false);
            return;
        }
        MqttEndpoint endpoint = broker.endpoint();
        if (endpoint == null || !MqttEndpointSwitchPolicy.canFlush(
                endpoint, commandLedger.pendingResultCount(),
                runtimeSettings == null ? null : runtimeSettings.outboxOwnerFingerprint())) {
            canonicalOutboxFlushInProgress.set(false);
            return;
        }
        MqttTopicSet topics = broker.topics();
        if (topics == null) {
            canonicalOutboxFlushInProgress.set(false);
            return;
        }
        CommandLedger.Record record = pending.get(0);
        diagnostics.record(
                "publish", "command_result", record.commandId, record.requestId,
                firstActionId(record), "RESULT_PENDING", "attempt", null);
        broker.publish(topics.commandResult(), record.resultPayload, success -> {
            if (success) commandLedger.markResultDelivered(
                    record.commandId, System.currentTimeMillis());
            diagnostics.record(
                    "publish", "command_result", record.commandId, record.requestId,
                    firstActionId(record),
                    success ? "DELIVERED" : "RESULT_PENDING",
                    success ? "delivered" : "failed", null);
            canonicalOutboxFlushInProgress.set(false);
            if (success) flushCanonicalRuntimeOutbox();
        });
    }

    private void flushMediaV11RuntimeOutbox() {
        if (mediaV11ServiceRuntime == null || broker == null
                || !mediaOutboxFlushInProgress.compareAndSet(false, true)) {
            return;
        }
        List<com.robotemi.agent.media.v11.MediaV11Persistence.OutboxRecord> pending =
                mediaV11ServiceRuntime.pendingOutbox();
        if (pending.isEmpty() || !broker.isConnected()) {
            mediaOutboxFlushInProgress.set(false);
            return;
        }
        MqttEndpoint endpoint = broker.endpoint();
        if (endpoint == null || (runtimeSettings != null
                && !runtimeSettings.bindOutboxOwner(endpoint))
                || !MqttEndpointSwitchPolicy.canFlush(
                endpoint, pending.size(),
                runtimeSettings == null ? null : runtimeSettings.outboxOwnerFingerprint())) {
            mediaOutboxFlushInProgress.set(false);
            return;
        }
        MqttTopicSet topics = broker.topics();
        if (topics == null) {
            mediaOutboxFlushInProgress.set(false);
            return;
        }
        com.robotemi.agent.media.v11.MediaV11Persistence.OutboxRecord record = pending.get(0);
        JsonObject result = JsonParser.parseString(record.payload).getAsJsonObject();
        diagnostics.record(
                "media_publish", "command_result",
                stringField(result, "command_id"), stringField(result, "event_id"),
                stringField(result, "command_action"),
                "PENDING", "attempt", null);
        broker.publish(topics.commandResult(), record.payload, success -> {
            if (success) mediaV11ServiceRuntime.acknowledgeOutbox(record.id, record.payload);
            diagnostics.record(
                    "media_publish", "command_result",
                    stringField(result, "command_id"), stringField(result, "event_id"),
                    stringField(result, "command_action"),
                    success ? "DELIVERED" : "PENDING",
                    success ? "delivered" : "failed", null);
            mediaOutboxFlushInProgress.set(false);
            if (success) flushMediaV11RuntimeOutbox();
        });
    }

    @Nullable
    private static String stringField(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonPrimitive()
                ? object.get(name).getAsString() : null;
    }

    private static String firstActionId(CommandLedger.Record record) {
        return record.actions == null || record.actions.size() != 1
                ? null : record.actions.get(0).actionId;
    }

    private String clientIdFor(MqttEndpoint endpoint) {
        clientId = MqttClientIdentity.forRobot(getPackageName(), endpoint.robotId());
        return clientId;
    }

    private synchronized ListenerRegistration attachListener(
            SingleActiveMqttBroker.Listener listener) {
        ListenerRegistration registration = new ListenerRegistration(++listenerGeneration);
        attachedListener = listener;
        attachedListenerRegistration = registration;
        List<InboundMessage> replay = new ArrayList<>(bufferedMessages);
        bufferedMessages.clear();
        bufferedMessageBytes = 0L;
        for (InboundMessage message : replay) {
            listener.onMessage(message.topic, message.payload, message.retained);
        }
        listener.onStateChanged(state, "listener_attached");
        if (state == MqttConnection.ConnectionState.CONNECTED) {
            listener.onConnected();
        }
        return registration;
    }

    private synchronized void detachListener(ListenerRegistration registration) {
        if (registration == null
                || registration.generation != listenerGeneration
                || registration != attachedListenerRegistration) {
            return;
        }
        attachedListener = null;
        attachedListenerRegistration = null;
    }

    public synchronized void detachListener() {
        attachedListener = null;
        attachedListenerRegistration = null;
    }

    private synchronized void onBrokerMessage(
            String topic, String payload, boolean retained) {
        int payloadBytes = MqttIngressLimits.utf8ByteLength(payload);
        if (payloadBytes > MqttIngressLimits.MAX_INBOUND_PAYLOAD_BYTES) {
            diagnostics.record(
                    "ingress", "oversized_payload", null, null, null,
                    "REJECTED", "oversized_payload", null);
            return;
        }
        MqttEndpoint endpoint = broker == null ? null : broker.endpoint();
        MqttTopicSet topics = broker == null ? null : broker.topics();
        String retainedRejectionCategory = MqttIngressPolicy.retainedRejectionCategory(
                topics, topic, retained);
        if (retainedRejectionCategory != null) {
            diagnostics.record(
                    "ingress", retainedRejectionCategory, null, null, null,
                    "REJECTED", "retained_rejected", null);
            return;
        }
        if (endpoint != null && topics != null && topics.commandRequest().equals(topic)) {
            if (mediaV11ServiceRuntime != null
                    && MediaV11Parser.declaresMediaV11(payload)
                    && mediaV11ServiceRuntime.ingest(
                            payload, endpoint.robotId(), endpoint.fingerprint())) {
                return;
            }
            if (canonicalCommandIngress.ingest(payload, endpoint.robotId())) return;
        }
        forwardMessage(topic, payload, retained, payloadBytes);
    }

    private synchronized boolean onCanonicalCommandValidated(
            CanonicalCommand command, String payload) {
        if (command.getActions().size() != 1) {
            diagnostics.record(
                    "route", "command_request", command.getCommandId(),
                    command.getEventId(), null, "UI_LEGACY", "mixed_actions", null);
            return false;
        }
        CanonicalAction action = command.getActions().get(0);
        if (!"speak".equals(action.getType())) {
            diagnostics.record(
                    "route", "command_request", command.getCommandId(),
                    command.getEventId(), action.getActionId(),
                    "UI_LEGACY", "non_speak", null);
            return false;
        }
        if (commandLedger == null || canonicalCommandRuntime == null
                || canonicalSpeechPort == null) {
            throw new IllegalStateException("canonical_service_runtime_unavailable");
        }

        CommandLedger.AcceptResult accepted = commandLedger.accept(
                command, payload, System.currentTimeMillis());
        switch (accepted.state()) {
            case FIRST_DELIVERY:
                diagnostics.record(
                        "ledger", "command_request", command.getCommandId(),
                        command.getEventId(), action.getActionId(),
                        "RECEIVED", "accepted", null);
                dispatchCanonicalSpeak(command, action);
                return true;
            case DUPLICATE_CACHED_RESULT:
                diagnostics.record(
                        "ledger", "command_request", command.getCommandId(),
                        command.getEventId(), action.getActionId(),
                        "CACHED_RESULT", "duplicate_replay", null);
                flushCanonicalRuntimeOutbox();
                return true;
            case DUPLICATE_PENDING:
                diagnostics.record(
                        "ledger", "command_request", command.getCommandId(),
                        command.getEventId(), action.getActionId(),
                        "PENDING", "duplicate_suppressed", null);
                return true;
            default:
                diagnostics.record(
                        "ledger", "command_request", command.getCommandId(),
                        command.getEventId(), action.getActionId(),
                        "UI_LEGACY", accepted.state().name(), null);
                return false;
        }
    }

    private void dispatchCanonicalSpeak(CanonicalCommand command, CanonicalAction action) {
        if (commandLedger == null || canonicalCommandRuntime == null
                || canonicalSpeechPort == null) {
            throw new IllegalStateException("canonical_service_runtime_unavailable");
        }
        TtsRequest request = TtsRequest.create(
                action.getText(), false, mapTtsLanguage(action.getLanguage()));
        if (!commandLedger.markExecuting(command.getCommandId(), System.currentTimeMillis())) {
            diagnostics.record(
                    "ledger", "command_request", command.getCommandId(),
                    command.getEventId(), action.getActionId(),
                    "EXECUTING", "transition_rejected", null);
            throw new IllegalStateException("canonical_command_execution_transition_rejected");
        }
        UUID requestId = request.getId();
        if (!canonicalCommandRuntime.beginTts(
                requestId,
                command.getCommandId(),
                command.getEventId(),
                command.getRobotId(),
                action.getActionId(),
                action.getType(),
                true)) {
            diagnostics.record(
                    "runtime", "command_request", command.getCommandId(),
                    command.getEventId(), action.getActionId(),
                    "EXECUTING", "single_flight_rejected", null);
            throw new IllegalStateException("canonical_tts_single_flight_rejected");
        }
        diagnostics.record(
                "speech", "command_request", command.getCommandId(),
                command.getEventId(), action.getActionId(),
                "EXECUTING", "dispatch", null);
        try {
            canonicalSpeechPort.speak(request);
        } catch (RuntimeException | Error error) {
            diagnostics.record(
                    "speech", "command_request", command.getCommandId(),
                    command.getEventId(), action.getActionId(),
                    "EXECUTING", "dispatch_failed", error.getClass().getSimpleName());
            canonicalCommandRuntime.onTtsStatusChanged(requestId, false);
            throw error;
        }
    }

    @VisibleForTesting
    int bufferedMessageCountForTest() {
        synchronized (this) {
            return bufferedMessages.size();
        }
    }

    @VisibleForTesting
    long bufferedBytesForTest() {
        synchronized (this) {
            return bufferedMessageBytes;
        }
    }

    private synchronized void forwardMessage(
            String topic, String payload, boolean retained, int payloadBytes) {
        if (attachedListener != null) {
            attachedListener.onMessage(topic, payload, retained);
            return;
        }
        while (!bufferedMessages.isEmpty()
                && (bufferedMessages.size() >= MAX_BUFFERED_MESSAGES
                || bufferedMessageBytes + payloadBytes > MAX_BUFFERED_BYTES)) {
            evictOldestBufferedMessage();
        }
        if (payloadBytes > MAX_BUFFERED_BYTES) return;
        bufferedMessages.addLast(new InboundMessage(topic, payload, retained, payloadBytes));
        bufferedMessageBytes += payloadBytes;
        diagnostics.record(
                "ui_buffer", topicClass(topic), null, null, null,
                "DETACHED", "buffered", null);
    }

    private void evictOldestBufferedMessage() {
        InboundMessage evicted = bufferedMessages.removeFirst();
        if (bufferedMessageBytes < evicted.payloadBytes) {
            throw new IllegalStateException("mqtt_buffer_byte_accounting_underflow");
        }
        bufferedMessageBytes -= evicted.payloadBytes;
    }

    private synchronized void notifyAttachedConnected() {
        if (attachedListener != null) attachedListener.onConnected();
    }

    private synchronized void notifyAttachedDisconnected(String reason) {
        if (attachedListener != null) attachedListener.onDisconnected(reason);
    }

    private static TtsRequest.Language mapTtsLanguage(String language) {
        String normalized = language == null
                ? "ZH_TW" : language.trim().replace('-', '_').toUpperCase(Locale.US);
        switch (normalized) {
            case "ZH_TW": return TtsRequest.Language.ZH_TW;
            case "ZH_CN": return TtsRequest.Language.ZH_CN;
            case "EN_US": return TtsRequest.Language.EN_US;
            case "JA_JP": return TtsRequest.Language.JA_JP;
            default: return TtsRequest.Language.ZH_TW;
        }
    }

    private static String topicClass(String topic) {
        if (topic == null) return "unknown";
        if (topic.endsWith("/cmd/request")) return "command_request";
        if (topic.endsWith("/cmd/result")) return "command_result";
        if (topic.endsWith("/resident/identity/result")) return "identity_result";
        if (topic.endsWith("/care/report")) return "care_report";
        return "other";
    }

    private MediaV11ServiceRuntime createMediaV11ServiceRuntime(
            CanonicalCommandRuntime.Scheduler scheduler) {
        return new MediaV11ServiceRuntime(
                commandLedger,
                commandLedgerPersistence,
                new MediaV11ServiceRuntime.Clock() {
                    @Override public long nowMs() { return System.currentTimeMillis(); }
                    @Override public String nowTimestamp() {
                        java.text.SimpleDateFormat format =
                                new java.text.SimpleDateFormat(
                                        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
                        format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                        return format.format(new java.util.Date());
                    }
                },
                new MediaV11ServiceRuntime.Scheduler() {
                    @Override
                    public MediaV11ServiceRuntime.Cancellable schedule(
                            Runnable task, long delayMs) {
                        CanonicalCommandRuntime.Cancellable cancellable =
                                scheduler.schedule(task, delayMs);
                        return cancellable::cancel;
                    }
                },
                this::flushMediaV11RuntimeOutbox,
                (phase, topicClass, commandId, eventId, actionId, state, outcome) ->
                        diagnostics.record(
                                phase, topicClass, commandId, eventId, actionId,
                                state, outcome, null),
                mediaV11EnabledForRuntime(),
                mediaV11AttachDeadlineForRuntime());
    }

    private boolean mediaV11EnabledForRuntime() {
        return testMediaV11Enabled == null
                ? BuildConfig.MEDIA_V11_ENABLED : testMediaV11Enabled;
    }

    private long mediaV11AttachDeadlineForRuntime() {
        return testMediaV11Enabled == null
                ? BuildConfig.MEDIA_V11_ATTACH_DEADLINE_MS
                : MediaV11ServiceRuntime.DEFAULT_ATTACH_DEADLINE_MS;
    }

    private static CanonicalCommandDiagnostics createDefaultDiagnostics() {
        return (phase, topicClass, commandId, eventId, actionId,
                state, outcome, exceptionClass) -> Log.i(
                TAG,
                "CANONICAL_DIAG phase=" + phase
                        + " topic=" + topicClass
                        + " command_fp=" + fingerprint(commandId)
                        + " event_fp=" + fingerprint(eventId)
                        + " action_fp=" + fingerprint(actionId)
                        + " state=" + state
                        + " outcome=" + outcome
                        + " exception="
                        + (exceptionClass == null ? "none" : exceptionClass));
    }

    private static String fingerprint(@Nullable String value) {
        if (value == null) return "none";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                result.append(String.format(Locale.US, "%02x", digest[i] & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            return "unavailable";
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID,
                    "TemiAgent MQTT connection",
                    NotificationManager.IMPORTANCE_LOW));
        }
    }

    private Notification buildNotification() {
        Intent launch = new Intent(this, MainActivity.class);
        int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, launch, pendingIntentFlags);
        String label = "TemiAgent MQTT " + state.name().toLowerCase(Locale.US);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("TemiAgent")
                .setContentText(label)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(pendingIntent)
                .build();
    }

    private void updateNotification() {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private static final class InboundMessage {
        private final String topic;
        private final String payload;
        private final boolean retained;
        private final int payloadBytes;

        private InboundMessage(
                String topic, String payload, boolean retained, int payloadBytes) {
            this.topic = topic;
            this.payload = payload;
            this.retained = retained;
            this.payloadBytes = payloadBytes;
        }
    }
}
