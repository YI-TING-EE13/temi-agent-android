package com.robotemi.agent;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModel;

import com.robotemi.agent.camera.CameraManager;
import com.robotemi.agent.care.report.CareInteractionPersistence;
import com.robotemi.agent.care.report.CareReportInteractionCoordinator;
import com.robotemi.agent.care.report.CareReportParser;
import com.robotemi.agent.care.report.CareReportRoutePolicy;
import com.robotemi.agent.care.report.CareReportStateHolder;
import com.robotemi.agent.care.report.CareReportUiBinder;
import com.robotemi.agent.care.report.CareReportUiState;
import com.robotemi.agent.care.report.CareReportViewModel;
import com.robotemi.agent.care.report.SharedPreferencesCareInteractionPersistence;
import com.robotemi.agent.command.CanonicalCommandValidator;
import com.robotemi.agent.command.CanonicalCommandValidator.CanonicalAction;
import com.robotemi.agent.command.CanonicalCommandValidator.CanonicalCommand;
import com.robotemi.agent.command.CanonicalCommandRuntime;
import com.robotemi.agent.command.CanonicalMediaTracker;
import com.robotemi.agent.command.CommandLedger;
import com.robotemi.agent.command.SharedPreferencesCommandLedger;
import com.robotemi.agent.identity.ResidentIdentityStateHolder;
import com.robotemi.agent.identity.ResidentIdentityLifecyclePolicy;
import com.robotemi.agent.identity.ResidentIdentityUiMapper;
import com.robotemi.agent.identity.ResidentIdentityUiState;
import com.robotemi.agent.identity.ResidentIdentityViewModel;
import com.robotemi.agent.mqtt.MqttEndpoint;
import com.robotemi.agent.mqtt.InboundMqttLogSummary;
import com.robotemi.agent.mqtt.MqttEndpointSelection;
import com.robotemi.agent.mqtt.MqttEndpointSwitchPolicy;
import com.robotemi.agent.mqtt.MqttConnection;
import com.robotemi.agent.mqtt.MqttLifecycleService;
import com.robotemi.agent.mqtt.MqttTopicSet;
import com.robotemi.agent.mqtt.SharedPreferencesMqttRuntimeSettings;
import com.robotemi.agent.mqtt.SingleActiveMqttBroker;
import com.robotemi.agent.media.v11.MainThreadDispatcher;
import com.robotemi.agent.media.v11.MediaPlaybackController;
import com.robotemi.agent.media.v11.MediaV11Command;
import com.robotemi.agent.media.v11.MediaV11PlaybackBinding;
import com.robotemi.agent.media.v11.ExerciseMediaResourceResolver;
import com.robotemi.agent.network.WebSocketClient;
import com.robotemi.sdk.NlpResult;
import com.robotemi.sdk.Robot;
import com.robotemi.sdk.SttLanguage;
import com.robotemi.sdk.TtsRequest;
import com.robotemi.sdk.listeners.OnRobotReadyListener;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import com.robotemi.agent.agent.AgentStateMachine;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TemiAgent Main Controller & Embodied AI Orchestrator.
 *
 * <p>This activity serves as the primary integration point for the Temi SDK,
 * CameraX hardware streaming, and the MQTT/WebSocket telemetry bridges.</p>
 *
 * <p>It initializes the {@link AgentStateMachine} to manage the dialogue lifecycle
 * safely, ensuring that physical hardware interrupts (touch) and VLM timeouts
 * are handled deterministically without blocking the UI thread.</p>
 *
 * <p>Multicast Edition: Supports broadcasting telemetry (Vision/ASR) to multiple
 * PC backends simultaneously (e.g. Original Backend + Hermes Agent).</p>
 */
public class MainActivity extends AppCompatActivity
        implements OnRobotReadyListener, Robot.AsrListener, Robot.WakeupWordListener,
                   Robot.TtsListener, Robot.NlpListener,
                   AgentStateMachine.StateChangeListener {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final String CUSTOM_WAKE_WORD = "\u5c0f\u5b89";
    private static final String[] CUSTOM_WAKE_WORD_VARIANTS = {
            "\u5c0f\u5b89",
            "\u5c0f\u5b89\u4f60\u597d",
            "\u4f60\u597d\u5c0f\u5b89",
            "\u5c0f\u6069",
            "\u5c0f\u5eb5",
            "\u5c0f\u978d",
            "\u6653\u5b89",
            "\u6653\u6069",
            "\u6653\u5eb5",
            "\u6821\u5b89",
            "\u7b11\u5b89"
    };
    private static final int HOTWORD_RESTART_DELAY_MS = 250;
    // ─── Components ───────────────────────────────────────────────────
    private Robot robot;
    private CameraManager cameraManager;
    private List<WebSocketClient> webSocketClients = new ArrayList<>();
    private SingleActiveMqttBroker activeMqttBroker;
    private MqttLifecycleService mqttLifecycleService;
    private MqttLifecycleService.LocalBinder mqttServiceBinder;
    @Nullable private MqttLifecycleService.ListenerRegistration mqttListenerRegistration;
    private boolean mqttServiceBound;
    private SharedPreferencesMqttRuntimeSettings mqttRuntimeSettings;
    private boolean servicesStarted;
    private AgentStateMachine stateMachine;
    private boolean shouldContinueListening = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer hotwordRecognizer;
    private Intent hotwordIntent;
    private boolean hotwordEnabled = false;
    private boolean hotwordListening = false;
    private boolean acceptingTemiAsr = false;
    private String activeConversationId = "conv-" + UUID.randomUUID();
    private CommandLedger commandLedger;
    private final ArrayDeque<QueuedCommand> canonicalCommandQueue = new ArrayDeque<>();
    private final AtomicBoolean careOutboxFlushInProgress = new AtomicBoolean(false);
    private final AtomicBoolean commandOutboxFlushInProgress = new AtomicBoolean(false);
    private boolean durableRecoveryLoaded;
    private final CanonicalMediaTracker canonicalMediaTracker = new CanonicalMediaTracker();
    private PendingCanonicalCommand activeCanonicalCommand;
    private MediaPlaybackController mediaPlaybackController;
    private MainThreadDispatcher mediaMainThreadDispatcher = new MainThreadDispatcher.Default();
    @Nullable private MediaV11PlaybackBinding mediaV11PlaybackBinding;
    private long mediaV11BindingGeneration;
    @Nullable private MediaV11PlaybackBinding.Callback mediaV11Callback;
    @Nullable private String mediaV11CallbackSession;
    @Nullable private String mediaV11CallbackLease;
    private long mediaV11CallbackGeneration;
    private boolean resumeListeningAfterCanonicalQueue;
    private ResidentIdentityViewModel residentIdentityViewModel;
    private CareReportViewModel careReportViewModel;

    /** The service owns the TTS callback; this Activity only observes resolutions. */
    private final MqttLifecycleService.CanonicalTtsListener canonicalTtsListener =
            this::completeCanonicalTtsAction;

    private final SingleActiveMqttBroker.Listener mqttListener =
            new SingleActiveMqttBroker.Listener() {
                @Override
                public void onMessage(@NonNull String topic, @NonNull String payload) {
                    handleMqttMessage(topic, payload, false);
                }

                @Override
                public void onMessage(
                        @NonNull String topic,
                        @NonNull String payload,
                        boolean retained) {
                    handleMqttMessage(topic, payload, retained);
                }

                @Override
                public void onConnected() {
                    handleMqttConnected();
                }

                @Override
                public void onDisconnected(String reason) {
                    handleMqttDisconnected(reason);
                }

                @Override
                public void onStateChanged(
                        MqttConnection.ConnectionState state, String reason) {
                    Log.i(TAG, "MQTT state=" + state + " reason=" + reason);
                    updateMqttConnectionStatus();
                }
            };

    private final ServiceConnection mqttServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MqttLifecycleService.LocalBinder localBinder =
                    (MqttLifecycleService.LocalBinder) service;
            mqttLifecycleService = localBinder.service();
            mqttServiceBinder = localBinder;
            activeMqttBroker = localBinder.broker();
            mqttServiceBound = true;
            mqttListenerRegistration = localBinder.attachListener(mqttListener);
            localBinder.attachCanonicalTtsListener(canonicalTtsListener);
            if (mediaV11PlaybackBinding == null) {
                mediaV11PlaybackBinding = createMediaV11PlaybackBinding();
            }
            mediaV11BindingGeneration = localBinder.attachMediaV11PlaybackBinding(
                    mediaV11PlaybackBinding);
            applyStoredMqttEndpoint();
            mainHandler.post(() -> {
                recoverDurableCommandsWhenReady();
                if (servicesStarted) activeMqttBroker.connect();
                updateMqttConnectionStatus();
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mqttServiceBound = false;
            mqttServiceBinder = null;
            mqttListenerRegistration = null;
            mqttLifecycleService = null;
            activeMqttBroker = null;
            mediaV11BindingGeneration = 0L;
            updateMqttConnectionStatus();
        }
    };

    // ─── UI ───────────────────────────────────────────────────────────
    private PreviewView viewFinder;
    private TextView statusText;
    private TextView agentStateText;
    private TextView mqttStatusText;
    private TextView subtitleText;
    private TextView residentIdentityText;
    private UUID activeSubtitleTtsId;
    private FrameLayout mediaContainer;
    private VideoView exerciseVideoView;
    private TextView mediaTitleText;
    private Button mediaStopButton;
    private Button mediaPauseButton;
    private Button mediaResumeButton;
    private Button mediaPlayHandButton;
    private Button mediaPlayLegButton;
    private LinearLayout mqttSettingsPanel;
    private EditText mqttHostInput;
    private EditText mqttPortInput;
    private EditText mqttRobotIdInput;
    private Button mqttSettingsButton;
    private Button mqttApplyButton;
    private Button mqttDisableButton;
    private Button mqttDiscardOutboxButton;
    private CareReportUiBinder careReportUi;
    private WindowInsetsControllerCompat systemUiController;
    private final Runnable residentIdentityExpiryRunnable = () -> {
        if (residentIdentityViewModel == null) {
            return;
        }
        ResidentIdentityStateHolder.Update update =
                residentIdentityViewModel.expireIfNeeded();
        syncCareReportIdentity(update.state);
        renderResidentIdentity(update.state, update.clearResidentSpecificUi);
    };

    // ═══════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        configureSystemUi(findViewById(R.id.appContent));

        // Bind UI
        viewFinder = findViewById(R.id.viewFinder);
        statusText = findViewById(R.id.statusText);
        agentStateText = findViewById(R.id.agentStateText);
        mqttStatusText = findViewById(R.id.mqttStatusText);
        subtitleText = findViewById(R.id.subtitleText);
        residentIdentityText = findViewById(R.id.residentIdentityText);
        mediaContainer = findViewById(R.id.mediaContainer);
        exerciseVideoView = findViewById(R.id.exerciseVideoView);
        mediaTitleText = findViewById(R.id.mediaTitleText);
        mediaStopButton = findViewById(R.id.mediaStopButton);
        mediaPauseButton = findViewById(R.id.mediaPauseButton);
        mediaResumeButton = findViewById(R.id.mediaResumeButton);
        mediaPlayHandButton = findViewById(R.id.mediaPlayHandButton);
        mediaPlayLegButton = findViewById(R.id.mediaPlayLegButton);
        mqttSettingsPanel = findViewById(R.id.mqttSettingsPanel);
        mqttHostInput = findViewById(R.id.mqttHostInput);
        mqttPortInput = findViewById(R.id.mqttPortInput);
        mqttRobotIdInput = findViewById(R.id.mqttRobotIdInput);
        mqttSettingsButton = findViewById(R.id.mqttSettingsButton);
        mqttApplyButton = findViewById(R.id.mqttApplyButton);
        mqttDisableButton = findViewById(R.id.mqttDisableButton);
        mqttDiscardOutboxButton = findViewById(R.id.mqttDiscardOutboxButton);
        careReportUi = new CareReportUiBinder(this, new CareReportUiBinder.Callbacks() {
            @Override
            public void onOpenRequested() {
                openCareReport();
            }

            @Override
            public void onAcknowledgeRequested() {
                acknowledgeVisibleCareReport();
            }

            @Override
            public void onPreviousRequested() {
                navigateCareReport(false);
            }

            @Override
            public void onNextRequested() {
                navigateCareReport(true);
            }
        });
        if (BuildConfig.RESIDENT_IDENTITY_ENABLED || BuildConfig.CARE_REPORT_ENABLED) {
            residentIdentityViewModel =
                    new ViewModelProvider(this).get(ResidentIdentityViewModel.class);
            renderResidentIdentity(residentIdentityViewModel.state(), true);
        } else {
            residentIdentityText.setVisibility(View.GONE);
        }

        mqttRuntimeSettings = new SharedPreferencesMqttRuntimeSettings(
                getSharedPreferences("mqtt_runtime", MODE_PRIVATE));
        commandLedger = new CommandLedger(
                new SharedPreferencesCommandLedger(
                        getSharedPreferences("canonical_commands", MODE_PRIVATE)));
        MqttEndpointSelection commandEndpoint = mqttRuntimeSettings.loadEndpoint();
        if (commandLedger.pendingResultCount() > 0
                && commandEndpoint.status() == MqttEndpointSelection.Status.VALID
                && commandEndpoint.endpoint() != null) {
            mqttRuntimeSettings.bindOutboxOwner(commandEndpoint.endpoint());
        }
        if (BuildConfig.CARE_REPORT_ENABLED) {
            final CareReportInteractionCoordinator careInteractions =
                    new CareReportInteractionCoordinator(
                            new SharedPreferencesCareInteractionPersistence(
                                    getSharedPreferences(
                                            "canonical_care_interactions", MODE_PRIVATE)),
                            CareReportInteractionCoordinator.systemClock(),
                            CareReportInteractionCoordinator.uuidGenerator());
            careReportViewModel = new ViewModelProvider(
                    this,
                    new ViewModelProvider.Factory() {
                        @NonNull
                        @Override
                        @SuppressWarnings("unchecked")
                        public <T extends ViewModel> T create(
                                @NonNull Class<T> modelClass) {
                            if (!modelClass.isAssignableFrom(CareReportViewModel.class)) {
                                throw new IllegalArgumentException(
                                        "unsupported_view_model");
                            }
                            return (T) new CareReportViewModel(
                                    new CareReportStateHolder(
                                            new CareReportParser(), true),
                                    careInteractions);
                        }
                    }).get(CareReportViewModel.class);
            careReportViewModel.syncIdentity(residentIdentityViewModel.state());
            careReportUi.render(careReportViewModel.state());
            updateCareReportEntry();
            MqttEndpointSelection saved = mqttRuntimeSettings.loadEndpoint();
            if (!careReportViewModel.pendingInteractions().isEmpty()
                    && saved.status() == MqttEndpointSelection.Status.VALID
                    && saved.endpoint() != null) {
                String pendingEndpoint =
                        careReportViewModel.pendingEndpointFingerprint();
                if (saved.endpoint().fingerprint().equals(pendingEndpoint)) {
                    mqttRuntimeSettings.bindOutboxOwner(saved.endpoint());
                } else {
                    Log.e(TAG, "Care interaction outbox endpoint mismatch");
                }
            }
        } else {
            careReportUi.disable();
        }
        mediaPlaybackController = new MediaPlaybackController(
                exerciseVideoView, createMediaPlaybackListener());
        mediaStopButton.setOnClickListener(v -> mediaPlaybackController.localUserStop());
        mediaPauseButton.setOnClickListener(v -> pauseLocalPlayback());
        mediaResumeButton.setOnClickListener(v -> resumeLocalPlayback());
        mediaPlayHandButton.setOnClickListener(
                v -> startLocalPlayback("elderly_hand_exercise"));
        mediaPlayLegButton.setOnClickListener(
                v -> startLocalPlayback("elderly_leg_exercise"));
        initializeMqttSettingsUi();
        startAndBindMqttService();

        // Initialize State Machine
        stateMachine = new AgentStateMachine(this);

        // Global Interrupt mechanism on screen touch
        View rootView = findViewById(android.R.id.content);
        rootView.setOnClickListener(v -> {
            if (stateMachine.getCurrentState() != AgentStateMachine.State.IDLE) {
                stateMachine.interrupt();
            }
        });

        // Initialize Robot SDK
        robot = Robot.getInstance();

        // Initialize WebSocket clients (Multicast)
        String[] wsUrls = BuildConfig.WS_SERVER_URLS.split(",");
        for (String url : wsUrls) {
            String cleanUrl = url.trim();
            if (!cleanUrl.isEmpty()) {
                webSocketClients.add(new WebSocketClient(cleanUrl));
            }
        }

        // Initialize Camera with multicast callback → WebSockets
        cameraManager = createCameraManager();

        updateStatus("Initialized. Waiting for Robot...");
    }

    private void configureSystemUi(View insetAwareContent) {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        systemUiController = WindowCompat.getInsetsController(
                getWindow(), getWindow().getDecorView());
        systemUiController.setAppearanceLightStatusBars(false);
        systemUiController.setAppearanceLightNavigationBars(false);
        systemUiController.setSystemBarsBehavior(
                WindowInsetsControllerCompat
                        .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        final int initialLeft = insetAwareContent.getPaddingLeft();
        final int initialTop = insetAwareContent.getPaddingTop();
        final int initialRight = insetAwareContent.getPaddingRight();
        final int initialBottom = insetAwareContent.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(
                insetAwareContent, (view, windowInsets) -> {
                    Insets systemBars = windowInsets.getInsetsIgnoringVisibility(
                            WindowInsetsCompat.Type.systemBars());
                    Insets displayCutout = windowInsets.getInsets(
                            WindowInsetsCompat.Type.displayCutout());
                    int windowHeight = view.getResources()
                            .getDisplayMetrics().heightPixels;
                    int bottomSafeInset =
                            SystemUiSafeAreaPolicy.resolveBottomInset(
                                    systemBars.bottom,
                                    displayCutout.bottom,
                                    windowHeight,
                                    view.getResources()
                                            .getDisplayMetrics().density);
                    int trailingSafeInset =
                            SystemUiSafeAreaPolicy.resolveTrailingInset(
                                    systemBars.right,
                                    displayCutout.right,
                                    view.getResources()
                                            .getDisplayMetrics().density);
                    view.setPadding(
                            initialLeft + Math.max(
                                    systemBars.left, displayCutout.left),
                            initialTop + Math.max(
                                    systemBars.top, displayCutout.top),
                            initialRight + trailingSafeInset,
                            initialBottom + bottomSafeInset);
                    return windowInsets;
                });
        ViewCompat.requestApplyInsets(insetAwareContent);
        restoreImmersiveSystemUi();
    }

    private void restoreImmersiveSystemUi() {
        if (systemUiController == null) {
            return;
        }
        getWindow().getDecorView().post(() ->
                systemUiController.hide(WindowInsetsCompat.Type.systemBars()));
    }

    private void restoreFullscreenSurfaces() {
        restoreImmersiveSystemUi();
        if (robot != null) {
            robot.hideTopBar();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (residentIdentityViewModel != null) {
            ResidentIdentityUiState identity = residentIdentityViewModel.state();
            syncCareReportIdentity(identity);
            renderResidentIdentity(identity, false);
        }
        robot.addOnRobotReadyListener(this);
        robot.addAsrListener(this);
        robot.addNlpListener(this);
        robot.addWakeupWordListener(this);
        robot.addTtsListener(this);
        ActivityInfo activityInfo = getActivityInfo();
        if (activityInfo != null) {
            robot.onStart(activityInfo);
        }
        restoreFullscreenSurfaces();
    }

    @Override
    protected void onResume() {
        super.onResume();
        restoreFullscreenSurfaces();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            restoreFullscreenSurfaces();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        hotwordEnabled = false;
        mainHandler.removeCallbacksAndMessages(null);
        stopHotwordListening();
        destroyHotwordRecognizer();
        robot.removeOnRobotReadyListener(this);
        robot.removeAsrListener(this);
        robot.removeNlpListener(this);
        robot.removeWakeupWordListener(this);
        robot.removeTtsListener(this);

        for (WebSocketClient wsc : webSocketClients) {
            if (wsc != null) wsc.disconnect();
        }
        if (cameraManager != null) cameraManager.shutdown();
        cameraManager = null;

        // MQTT is owned by MqttLifecycleService and intentionally survives Activity stop.
        servicesStarted = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        for (WebSocketClient client : webSocketClients) {
            if (client != null) {
                client.shutdown();
            }
        }
        if (mqttServiceBound) {
            if (mqttServiceBinder != null && mediaV11BindingGeneration != 0L) {
                mqttServiceBinder.detachMediaV11PlaybackBinding(mediaV11BindingGeneration);
                mediaV11BindingGeneration = 0L;
            }
            if (mqttServiceBinder != null) mqttServiceBinder.detachCanonicalTtsListener();
            if (mqttServiceBinder != null && mqttListenerRegistration != null) {
                mqttServiceBinder.detachListener(mqttListenerRegistration);
            }
            mqttLifecycleService = null;
            mqttServiceBinder = null;
            mqttListenerRegistration = null;
            activeMqttBroker = null;
            unbindService(mqttServiceConnection);
            mqttServiceBound = false;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Robot SDK Callback
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void onRobotReady(boolean isReady) {
        if (isReady) {
            Log.i(TAG, "Temi Robot is ready.");
            runOnUiThread(() -> {
                restoreFullscreenSurfaces();
                if (checkPermissions()) {
                    startAllServices();
                } else {
                    requestPermissions();
                }
            });
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  MQTT Callbacks
    // ═══════════════════════════════════════════════════════════════════

    private void handleMqttConnected() {
        if (activeMqttBroker == null) return;
        MqttEndpoint endpoint = activeMqttBroker.endpoint();
        Log.i(TAG, "Active MQTT connected: "
                + (endpoint == null ? "not_configured" : endpoint.displayName()));
        updateMqttConnectionStatus();
        flushDurableCommandResults();
        flushCareInteractionOutbox();
    }

    private void handleMqttDisconnected(String reason) {
        Log.w(TAG, "Active MQTT disconnected: " + reason);
        updateMqttConnectionStatus();
    }

    private void recoverDurableCommandsWhenReady() {
        if (durableRecoveryLoaded || commandLedger == null || stateMachine == null
                || robot == null) {
            return;
        }
        durableRecoveryLoaded = true;
        for (CommandLedger.RecoveryItem item :
                commandLedger.recover(System.currentTimeMillis())) {
            CommandLedger.Record record = item.record();
            switch (item.state()) {
                case CACHED_RESULT:
                    publishDurableCommandResult(record.commandId, record.resultPayload);
                    break;
                case SAFE_RETRY:
                    canonicalCommandQueue.add(QueuedCommand.recovery(record));
                    break;
                case EXECUTION_UNKNOWN:
                    persistRecoveryFailure(record, "process_death_execution_unknown");
                    break;
                case UNSAFE_RETRY:
                    persistRecoveryFailure(record, "process_death_unsafe_retry");
                    break;
                case EXPIRED:
                    persistRecoveryFailure(record, "command_expired_before_execution");
                    break;
                default:
                    break;
            }
        }
        flushDurableCommandResults();
        if (servicesStarted) startNextCanonicalCommand();
    }

    private void persistRecoveryFailure(CommandLedger.Record record, String error) {
        JSONArray results = new JSONArray();
        if (record.actions != null) {
            for (CommandLedger.ActionSummary action : record.actions) {
                results.put(createActionResult(action.actionId, action.type, "failed", error));
            }
        }
        String payload = buildCommandResultPayload(
                record.commandId, record.requestId, "failed", results, error);
        bindCommandOutboxOwner();
        if (payload != null && commandLedger.markResultPending(
                record.commandId, payload, CommandLedger.State.FAILED,
                System.currentTimeMillis())) {
            publishDurableCommandResult(record.commandId, payload);
        } else {
            Log.e(TAG, "Could not persist recovery failure: " + record.commandId);
        }
    }

    private void executeRecoveredSafeCommand(CommandLedger.Record record) {
        if (!commandLedger.markExecuting(record.commandId, System.currentTimeMillis())) {
            persistRecoveryFailure(record, "command_store_unavailable");
            startNextCanonicalCommand();
            return;
        }
        JSONArray results = new JSONArray();
        boolean failed = false;
        if (record.actions != null) {
            for (CommandLedger.ActionSummary action : record.actions) {
                try {
                    if ("stop".equals(action.type)) {
                        robot.cancelAllTtsRequests();
                        robot.stopMovement();
                        hideSubtitle();
                    }
                    results.put(createActionResult(action.actionId, action.type,
                            "completed", null));
                } catch (Exception e) {
                    failed = true;
                    results.put(createActionResult(action.actionId, action.type,
                            "failed", safeError(e)));
                }
            }
        }
        String status = failed ? "failed" : "success";
        String payload = buildCommandResultPayload(
                record.commandId, record.requestId, status, results,
                failed ? "recovery_safe_retry_failed" : null);
        CommandLedger.State terminalState = failed
                ? CommandLedger.State.FAILED : CommandLedger.State.COMPLETED;
        bindCommandOutboxOwner();
        if (payload != null && commandLedger.markResultPending(
                record.commandId, payload, terminalState, System.currentTimeMillis())) {
            publishDurableCommandResult(record.commandId, payload);
        } else {
            Log.e(TAG, "Could not persist safe recovery result: " + record.commandId);
        }
        startNextCanonicalCommand();
    }

    private void flushDurableCommandResults() {
        if (activeMqttBroker == null
                || !commandOutboxFlushInProgress.compareAndSet(false, true)) {
            return;
        }
        List<CommandLedger.Record> pending = commandLedger.pendingResults();
        if (pending.isEmpty() || !activeMqttBroker.isConnected()) {
            commandOutboxFlushInProgress.set(false);
            return;
        }
        MqttEndpoint endpoint = activeMqttBroker.endpoint();
        if (endpoint == null || !MqttEndpointSwitchPolicy.canFlush(
                endpoint, totalPendingOutboxCount(),
                mqttRuntimeSettings.outboxOwnerFingerprint())) {
            commandOutboxFlushInProgress.set(false);
            return;
        }
        MqttTopicSet topics = activeMqttBroker.topics();
        if (topics == null) {
            commandOutboxFlushInProgress.set(false);
            return;
        }
        CommandLedger.Record record = pending.get(0);
        activeMqttBroker.publish(topics.commandResult(), record.resultPayload, success -> {
            if (success) {
                commandLedger.markResultDelivered(record.commandId, System.currentTimeMillis());
                clearOutboxOwnerIfAllDelivered();
            }
            commandOutboxFlushInProgress.set(false);
            if (success) mainHandler.post(this::flushDurableCommandResults);
        });
    }

    private void handleMqttMessage(
            @NonNull String topic, @NonNull String payload, boolean retained) {
        if (activeMqttBroker == null) {
            Log.w(TAG, "Ignoring MQTT message before service bind");
            return;
        }
        MqttTopicSet topics = activeMqttBroker.topics();
        if (topics == null) {
            Log.w(TAG, InboundMqttLogSummary.describe(
                    InboundMqttLogSummary.Category.OTHER, retained, payload)
                    + " ignored=no_active_endpoint");
            return;
        }
        InboundMqttLogSummary.Category logCategory =
                inboundMqttLogCategory(topic, topics);
        Log.i(TAG, InboundMqttLogSummary.describe(
                logCategory, retained, payload));
        if (topics.commandRequest().equals(topic)) {
            handleCommandRequest(payload);
            return;
        }
        if (BuildConfig.RESIDENT_IDENTITY_ENABLED
                || BuildConfig.CARE_REPORT_ENABLED) {
            if (topics.residentIdentityResult().equals(topic)) {
                handleResidentIdentityResult(payload);
                return;
            }
        }
        if (BuildConfig.CARE_REPORT_ENABLED && topics.careReport().equals(topic)) {
            handleCareReport(payload, retained);
            return;
        }
        try {
            JSONObject json = new JSONObject(payload);
            switch (topic) {
                case MqttTopicSet.ACTION_SPEAK:
                    handleSpeakAction(json);
                    break;
                case MqttTopicSet.ACTION_NAVIGATE:
                    handleNavigateAction(json);
                    break;
                case MqttTopicSet.ACTION_WAKEUP:
                    handleWakeupAction(json);
                    break;
                default:
                    Log.w(TAG, "Unhandled MQTT topic category="
                            + logCategory.name().toLowerCase(Locale.US));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Invalid JSON for MQTT category="
                    + logCategory.name().toLowerCase(Locale.US), e);
        }
    }

    private static InboundMqttLogSummary.Category inboundMqttLogCategory(
            String topic, MqttTopicSet topics) {
        if (topics.commandRequest().equals(topic)) {
            return InboundMqttLogSummary.Category.COMMAND_REQUEST;
        }
        if (topics.residentIdentityResult().equals(topic)) {
            return InboundMqttLogSummary.Category.RESIDENT_IDENTITY;
        }
        if (topics.careReport().equals(topic)) {
            return InboundMqttLogSummary.Category.CARE_REPORT;
        }
        if (MqttTopicSet.ACTION_SPEAK.equals(topic)
                || MqttTopicSet.ACTION_NAVIGATE.equals(topic)
                || MqttTopicSet.ACTION_WAKEUP.equals(topic)) {
            return InboundMqttLogSummary.Category.LEGACY_ACTION;
        }
        return InboundMqttLogSummary.Category.OTHER;
    }

    private void handleResidentIdentityResult(@NonNull String payload) {
        if (residentIdentityViewModel == null) {
            return;
        }
        ResidentIdentityStateHolder.Update update =
                residentIdentityViewModel.acceptPayload(payload);
        syncCareReportIdentity(update.state);
        Log.i(TAG, "Resident identity disposition: " + update.disposition);
        runOnUiThread(() ->
                renderResidentIdentity(update.state, update.clearResidentSpecificUi));
    }

    private void renderResidentIdentity(
            ResidentIdentityUiState state, boolean clearResidentSpecificUi) {
        long remaining = residentIdentityViewModel == null
                ? 0 : residentIdentityViewModel.remainingTtlMillis();
        ResidentIdentityLifecyclePolicy.Decision lifecycle =
                ResidentIdentityLifecyclePolicy.decide(
                        BuildConfig.RESIDENT_IDENTITY_ENABLED,
                        BuildConfig.CARE_REPORT_ENABLED,
                        state,
                        clearResidentSpecificUi,
                        remaining);
        if (lifecycle.clearSensitiveUi) {
            clearResidentSpecificUi();
        }
        mainHandler.removeCallbacks(residentIdentityExpiryRunnable);
        if (lifecycle.expiryDelayMillis > 0) {
            mainHandler.postDelayed(
                    residentIdentityExpiryRunnable,
                    lifecycle.expiryDelayMillis);
        }
        if (residentIdentityText == null || !lifecycle.showIdentityLabel) {
            return;
        }
        int label;
        switch (ResidentIdentityUiMapper.labelFor(state)) {
            case FATHER:
                label = R.string.resident_identity_father;
                break;
            case MOTHER:
                label = R.string.resident_identity_mother;
                break;
            case IDENTIFYING:
                label = R.string.resident_identity_identifying;
                break;
            default:
                label = R.string.resident_identity_unknown;
                break;
        }
        residentIdentityText.setText(label);
        residentIdentityText.setVisibility(View.VISIBLE);
    }

    private void clearResidentSpecificUi() {
        if (careReportUi != null) {
            careReportUi.hideOverlay();
        }
        updateCareReportEntry();
    }

    private void syncCareReportIdentity(ResidentIdentityUiState identity) {
        if (careReportViewModel == null) {
            return;
        }
        CareReportStateHolder.Update update =
                careReportViewModel.syncIdentity(identity);
        runOnUiThread(() -> {
            if (update.clearSensitiveUi) {
                careReportUi.hideOverlay();
                careReportUi.render(update.state);
            }
            updateCareReportEntry();
        });
    }

    private void handleCareReport(String payload, boolean retained) {
        if (careReportViewModel == null) {
            return;
        }
        CareReportStateHolder.Update update =
                careReportViewModel.acceptReport(payload, retained);
        Log.i(TAG, "Care report disposition: " + update.disposition);
        runOnUiThread(() -> {
            CareReportRoutePolicy.Decision route =
                    CareReportRoutePolicy.decide(
                            update,
                            careReportUi.isOverlayVisible(),
                            careReportViewModel.isEntryAuthorized());
            careReportUi.setEntryAllowed(route.showEntry);
            if (route.hideOverlay) {
                careReportUi.hideOverlay();
            } else if (route.renderOverlay) {
                careReportUi.render(update.state);
                if (route.recordVisibleReport) {
                    careReportUi.postAfterVisible(this::recordVisibleCareReport);
                }
            }
        });
    }

    private void openCareReport() {
        if (careReportViewModel == null
                || residentIdentityViewModel == null
                || !residentIdentityViewModel.state().allowsResidentSpecificContent()) {
            clearResidentSpecificUi();
            return;
        }
        careReportUi.showOverlay(
                careReportViewModel.state(), this::recordVisibleCareReport);
    }

    private void navigateCareReport(boolean forward) {
        if (careReportViewModel == null || !careReportUi.isOverlayVisible()) {
            return;
        }
        CareReportUiState state = forward
                ? careReportViewModel.nextReport()
                : careReportViewModel.previousReport();
        careReportUi.render(state);
        careReportUi.postAfterVisible(this::recordVisibleCareReport);
    }

    private void recordVisibleCareReport() {
        CareReportUiState visibleState = careReportViewModel.state();
        if (!careReportUi.isOverlayVisible() || visibleState.report == null) {
            return;
        }
        CareReportInteractionCoordinator.Outcome outcome =
                careReportViewModel.reportVisible(activeMqttEndpointFingerprint());
        if (outcome.enqueued()) {
            bindNewCareOutboxToActiveEndpoint();
            flushCareInteractionOutbox();
        }
        careReportUi.render(careReportViewModel.state());
    }

    private void acknowledgeVisibleCareReport() {
        if (careReportViewModel == null
                || !careReportUi.isOverlayVisible()) {
            return;
        }
        CareReportInteractionCoordinator.Outcome outcome =
                careReportViewModel.acknowledge(activeMqttEndpointFingerprint());
        if (outcome.enqueued()) {
            bindNewCareOutboxToActiveEndpoint();
            flushCareInteractionOutbox();
        }
        careReportUi.render(careReportViewModel.state());
    }

    private void updateCareReportEntry() {
        if (careReportUi == null || careReportViewModel == null
                || residentIdentityViewModel == null) {
            return;
        }
        boolean allowed = residentIdentityViewModel.state()
                .allowsResidentSpecificContent()
                && careReportViewModel.isEntryAuthorized();
        careReportUi.setEntryAllowed(allowed);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  State Machine Callbacks & TTS
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void onStateChanged(AgentStateMachine.State oldState, AgentStateMachine.State newState) {
        updateAgentState(newState.name());
        if (newState == AgentStateMachine.State.IDLE) {
            startHotwordListening();
        } else {
            stopHotwordListening();
        }

        if (newState == AgentStateMachine.State.THINKING) {
            // Non-blocking transition feedback
            TtsRequest.Language ttsLang = mapTtsLanguage("ZH_TW");
            speakWithoutConversationLayer("讓我看一下", ttsLang);

            // Immediately transition to WAITING to start the Watchdog
            stateMachine.transitionTo(AgentStateMachine.State.WAITING);
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Executing Global Interrupt: Stopping all actions.");
        if (mediaPlaybackController != null && mediaPlaybackController.hasActivePlayback()) {
            mediaPlaybackController.localUserStop();
        } else {
            cancelCanonicalMedia("interrupted");
        }
        robot.cancelAllTtsRequests();
        robot.stopMovement();
        hideSubtitle();
    }

    @Override
    public void onTimeout() {
        Log.w(TAG, "Watchdog timeout! Returning to IDLE.");
        TtsRequest.Language ttsLang = mapTtsLanguage("ZH_TW");
        speakWithoutConversationLayer("連線逾時，請稍後再試", ttsLang);
    }

    @Override
    public void onTtsStatusChanged(@NonNull TtsRequest ttsRequest) {
        if (ttsRequest.getStatus() == TtsRequest.Status.COMPLETED ||
            ttsRequest.getStatus() == TtsRequest.Status.ERROR) {
            hideSubtitleForRequest(ttsRequest);

            if (activeCanonicalCommand != null) {
                Log.i(TAG, "Canonical TTS completion is owned by MqttLifecycleService");
                return;
            }
        }

        if (stateMachine.getCurrentState() == AgentStateMachine.State.EXECUTING) {
            if (ttsRequest.getStatus() == TtsRequest.Status.COMPLETED ||
                ttsRequest.getStatus() == TtsRequest.Status.ERROR) {
        if (shouldContinueListening) {
                    stateMachine.transitionTo(AgentStateMachine.State.WAKEUP_TRIGGERED);
                    stateMachine.transitionTo(AgentStateMachine.State.ASR_LISTENING);
                    wakeupWithoutBuiltInResponse();
                } else {
                    stateMachine.transitionTo(AgentStateMachine.State.IDLE);
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Action Handlers
    // ═══════════════════════════════════════════════════════════════════

    private void handleSpeakAction(JSONObject json) throws JSONException {
        stateMachine.transitionTo(AgentStateMachine.State.EXECUTING);

        String text = json.getString("text");
        String language = json.optString("language", "ZH_TW");
        shouldContinueListening = json.optBoolean("continue_listening", false);

        Log.i(TAG, "ACTION_SPEAK received (lang=" + language
                + ", continue=" + shouldContinueListening + ")");

        TtsRequest.Language ttsLang = mapTtsLanguage(language);
        suppressLauncherConversation();
        speakWithoutConversationLayer(text, ttsLang);
    }

    private void handleNavigateAction(JSONObject json) throws JSONException {
        stateMachine.transitionTo(AgentStateMachine.State.EXECUTING);

        String target = json.optString("target", json.optString("target_location", ""));
        if (target.trim().isEmpty()) {
            throw new JSONException("Missing navigation target");
        }
        Log.i(TAG, "ACTION_NAVIGATE received");
        robot.goTo(target);

        // Navigation doesn't trigger TTS completion usually, so we manually go to IDLE
        stateMachine.transitionTo(AgentStateMachine.State.IDLE);
    }

    private void handleWakeupAction(JSONObject json) {
        Log.i(TAG, "ACTION_WAKEUP");
        stateMachine.transitionTo(AgentStateMachine.State.WAKEUP_TRIGGERED);
        stateMachine.transitionTo(AgentStateMachine.State.ASR_LISTENING);
        wakeupWithoutBuiltInResponse();
    }

    private void handleCommandRequest(String payload) {
        final CanonicalCommand command;
        try {
            command = CanonicalCommandValidator.validate(payload, activeRobotId());
        } catch (CanonicalCommandValidator.ValidationException e) {
            Log.e(TAG, "Rejected canonical command: " + e.getReason());
            if (e.hasCorrelation()) {
                JSONArray results = new JSONArray();
                if (e.getActionId() != null || e.getActionType() != null) {
                    results.put(createActionResult(
                            e.getActionId(), e.getActionType(), "failed", e.getReason()));
                }
                publishRawCommandResult(buildCommandResultPayload(
                        e.getCommandId(), e.getEventId(), "failed", results, e.getReason()));
            } else {
                Log.e(TAG, "Cannot publish failure result without command_id and event_id correlation");
            }
            return;
        }

        CommandLedger.AcceptResult acceptResult = commandLedger.accept(
                command, payload, System.currentTimeMillis());
        switch (acceptResult.state()) {
            case DUPLICATE_CACHED_RESULT:
                Log.i(TAG, "Duplicate completed command; replaying cached result: "
                        + command.getCommandId());
                CommandLedger.Record cached = acceptResult.record();
                if (cached != null && cached.resultPayload != null) {
                    publishDurableCommandResult(command.getCommandId(), cached.resultPayload);
                }
                return;
            case DUPLICATE_PENDING:
                Log.i(TAG, "Duplicate pending command; execution suppressed and final result queued: "
                        + command.getCommandId());
                return;
            case PAYLOAD_CONFLICT:
                Log.e(TAG, "Rejected command ID payload conflict: " + command.getCommandId());
                publishRawCommandResult(buildCommandResultPayload(
                        command.getCommandId(), command.getEventId(), "failed",
                        new JSONArray(), "command_id_payload_conflict"));
                return;
            case CAPACITY_REJECTED:
                Log.e(TAG, "Canonical command registry capacity exhausted; rejecting command: "
                        + command.getCommandId());
                publishRawCommandResult(buildCommandResultPayload(
                        command.getCommandId(), command.getEventId(), "failed",
                        new JSONArray(), "command_registry_capacity_exhausted"));
                return;
            case STORE_ERROR:
                Log.e(TAG, "Canonical command ledger unavailable; rejecting command: "
                        + command.getCommandId());
                publishRawCommandResult(buildCommandResultPayload(
                        command.getCommandId(), command.getEventId(), "failed",
                        new JSONArray(), "command_store_unavailable"));
                return;
            case FIRST_DELIVERY:
            default:
                Log.i(TAG, "COMMAND_REQUEST: " + command.getCommandId()
                        + " actions=" + command.getActions().size());
                runOnUiThread(() -> {
                    canonicalCommandQueue.add(QueuedCommand.legacy(command));
                    startNextCanonicalCommand();
                });
        }
    }

    public void setMediaMainThreadDispatcher(MainThreadDispatcher dispatcher) {
        this.mediaMainThreadDispatcher = dispatcher == null ? new MainThreadDispatcher.Default() : dispatcher;
    }

    private MediaV11PlaybackBinding createMediaV11PlaybackBinding() {
        return new MediaV11PlaybackBinding() {
            @Override
            public void start(MediaV11Command command, String sessionId, String leaseId,
                              long generation, Callback callback) {
                rememberMediaCallback(sessionId, leaseId, generation, callback);
                mediaMainThreadDispatcher.post(() -> {
                    if (generation != mediaV11CallbackGeneration) return;
                    try {
                        mediaTitleText.setText(mediaTitleResourceId(command.getVideoId()));
                        mediaContainer.setVisibility(View.VISIBLE);
                        Uri uri = Uri.parse("android.resource://" + getPackageName() + "/"
                                + mediaResourceId(command.getVideoId()));
                        mediaPlaybackController.startRemote(sessionId, uri);
                    } catch (Exception error) {
                        Log.e(TAG, "MEDIA_UI_THREAD_EXECUTION_FAILED for start: " + command.getCommandId(), error);
                        callback.onFailed(generation, leaseId, sessionId, safeError(error));
                        clearMediaCallback(generation);
                    }
                });
            }

            @Override
            public void pause(String sessionId, String leaseId, long generation,
                              Callback callback) {
                rememberMediaCallback(sessionId, leaseId, generation, callback);
                mediaMainThreadDispatcher.post(() -> {
                    if (generation != mediaV11CallbackGeneration) return;
                    try {
                        mediaPlaybackController.pauseRemote(sessionId);
                        callback.onControlSucceeded(generation, leaseId, sessionId,
                                MediaV11Command.Action.PAUSE_VIDEO);
                    } catch (Exception error) {
                        Log.e(TAG, "MEDIA_UI_THREAD_EXECUTION_FAILED for pause: " + sessionId, error);
                        callback.onControlFailed(generation, leaseId, sessionId,
                                MediaV11Command.Action.PAUSE_VIDEO, safeError(error));
                    }
                });
            }

            @Override
            public void resume(String sessionId, String leaseId, long generation,
                               Callback callback) {
                rememberMediaCallback(sessionId, leaseId, generation, callback);
                mediaMainThreadDispatcher.post(() -> {
                    if (generation != mediaV11CallbackGeneration) return;
                    try {
                        mediaPlaybackController.resumeRemote(sessionId);
                        callback.onControlSucceeded(generation, leaseId, sessionId,
                                MediaV11Command.Action.RESUME_VIDEO);
                    } catch (Exception error) {
                        Log.e(TAG, "MEDIA_UI_THREAD_EXECUTION_FAILED for resume: " + sessionId, error);
                        callback.onControlFailed(generation, leaseId, sessionId,
                                MediaV11Command.Action.RESUME_VIDEO, safeError(error));
                    }
                });
            }

            @Override
            public void stop(String sessionId, String leaseId, long generation,
                             Callback callback) {
                rememberMediaCallback(sessionId, leaseId, generation, callback);
                mediaMainThreadDispatcher.post(() -> {
                    if (generation != mediaV11CallbackGeneration) return;
                    try {
                        mediaPlaybackController.stopRemote(sessionId);
                        clearMediaPlaybackUi();
                        callback.onControlSucceeded(generation, leaseId, sessionId,
                                MediaV11Command.Action.STOP_VIDEO);
                    } catch (Exception error) {
                        Log.e(TAG, "MEDIA_UI_THREAD_EXECUTION_FAILED for stop: " + sessionId, error);
                        callback.onControlFailed(generation, leaseId, sessionId,
                                MediaV11Command.Action.STOP_VIDEO, safeError(error));
                    }
                });
            }

            @Override
            public void detach(long generation) {
                mediaMainThreadDispatcher.post(() -> {
                    if (generation != mediaV11CallbackGeneration) return;
                    String sessionId = mediaV11CallbackSession;
                    clearMediaCallback(generation);
                    if (sessionId != null && mediaPlaybackController != null
                            && mediaPlaybackController.origin()
                            == MediaPlaybackController.Origin.REMOTE_V11) {
                        try {
                            mediaPlaybackController.stopRemote(sessionId);
                        } catch (Exception ignored) {
                            // The Activity is being destroyed; service timeout/reconciliation is
                            // the durable recovery path and no hardware replay is attempted.
                        }
                    }
                    clearMediaPlaybackUi();
                });
            }
        };
    }

    private void rememberMediaCallback(String sessionId, String leaseId, long generation,
                                       MediaV11PlaybackBinding.Callback callback) {
        mediaV11CallbackSession = sessionId;
        mediaV11CallbackLease = leaseId;
        mediaV11CallbackGeneration = generation;
        mediaV11Callback = callback;
    }

    private void clearMediaCallback(long generation) {
        if (generation != mediaV11CallbackGeneration) return;
        mediaV11Callback = null;
        mediaV11CallbackSession = null;
        mediaV11CallbackLease = null;
        mediaV11CallbackGeneration = 0L;
    }

    private MediaPlaybackController.Listener createMediaPlaybackListener() {
        return new MediaPlaybackController.Listener() {
            @Override
            public void onPlaybackStarted(
                    String sessionId, MediaPlaybackController.Origin origin) {
                if (origin == MediaPlaybackController.Origin.REMOTE_V11) {
                    MediaV11PlaybackBinding.Callback callback = mediaV11Callback;
                    if (callback != null && sessionId.equals(mediaV11CallbackSession)) {
                        callback.onStarted(mediaV11CallbackGeneration,
                                mediaV11CallbackLease, sessionId);
                    }
                    return;
                }
                if (origin == MediaPlaybackController.Origin.LEGACY_REMOTE
                        && canonicalMediaTracker.markStarted(sessionId)) {
                    Log.i(TAG, "CANONICAL_MEDIA_STARTED: " + sessionId);
                }
            }

            @Override
            public void onPlaybackCompleted(
                    String sessionId, MediaPlaybackController.Origin origin) {
                if (origin == MediaPlaybackController.Origin.REMOTE_V11) {
                    MediaV11PlaybackBinding.Callback callback = mediaV11Callback;
                    if (callback != null && sessionId.equals(mediaV11CallbackSession)) {
                        callback.onCompleted(mediaV11CallbackGeneration,
                                mediaV11CallbackLease, sessionId);
                    }
                    clearMediaCallback(mediaV11CallbackGeneration);
                    clearMediaPlaybackUi();
                    return;
                }
                if (origin == MediaPlaybackController.Origin.LEGACY_REMOTE) {
                    CanonicalMediaTracker.Resolution resolution =
                            canonicalMediaTracker.complete(sessionId);
                    if (resolution != null) {
                        completeCanonicalMediaAction(resolution);
                    }
                } else {
                    clearMediaPlaybackUi();
                }
            }

            @Override
            public void onPlaybackFailed(
                    String sessionId, MediaPlaybackController.Origin origin, String message) {
                if (origin == MediaPlaybackController.Origin.REMOTE_V11) {
                    MediaV11PlaybackBinding.Callback callback = mediaV11Callback;
                    if (callback != null && sessionId.equals(mediaV11CallbackSession)) {
                        callback.onFailed(mediaV11CallbackGeneration, mediaV11CallbackLease,
                                sessionId, message);
                    }
                    clearMediaCallback(mediaV11CallbackGeneration);
                    clearMediaPlaybackUi();
                    return;
                }
                if (origin == MediaPlaybackController.Origin.LEGACY_REMOTE) {
                    CanonicalMediaTracker.Resolution resolution =
                            canonicalMediaTracker.fail(sessionId, message);
                    if (resolution != null) {
                        completeCanonicalMediaAction(resolution);
                    }
                } else {
                    clearMediaPlaybackUi();
                }
            }

            @Override
            public void onLocalUserStopped(
                    String sessionId, MediaPlaybackController.Origin origin) {
                if (origin == MediaPlaybackController.Origin.REMOTE_V11) {
                    MediaV11PlaybackBinding.Callback callback = mediaV11Callback;
                    if (callback != null && sessionId.equals(mediaV11CallbackSession)) {
                        callback.onCancelled(mediaV11CallbackGeneration,
                                mediaV11CallbackLease, sessionId);
                    }
                    clearMediaCallback(mediaV11CallbackGeneration);
                    clearMediaPlaybackUi();
                    return;
                }
                if (origin == MediaPlaybackController.Origin.LOCAL) {
                    clearMediaPlaybackUi();
                    return;
                }
                CanonicalMediaTracker.Resolution resolution =
                        canonicalMediaTracker.cancel(sessionId, "user_cancelled");
                if (resolution != null) {
                    completeCanonicalMediaAction(resolution);
                }
            }
        };
    }

    private void startLocalPlayback(String videoId) {
        if (activeCanonicalCommand != null
                || mediaPlaybackController.hasActivePlayback()) {
            Toast.makeText(this, "目前已有播放或指令正在執行", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            mediaTitleText.setText(mediaTitleResourceId(videoId));
            mediaContainer.setVisibility(View.VISIBLE);
            Uri uri = Uri.parse(
                    "android.resource://" + getPackageName() + "/" + mediaResourceId(videoId));
            mediaPlaybackController.startLocal(uri);
        } catch (ExerciseMediaResourceResolver.MediaUnavailableException e) {
            Toast.makeText(this, "此部署未提供運動影片", Toast.LENGTH_SHORT).show();
            clearMediaPlaybackUi();
        } catch (Exception e) {
            Log.e(TAG, "Local playback failed", e);
            clearMediaPlaybackUi();
        }
    }

    private void pauseLocalPlayback() {
        try {
            mediaPlaybackController.pauseLocal();
        } catch (IllegalStateException e) {
            Log.i(TAG, "Ignoring local pause: " + e.getMessage());
        }
    }

    private void resumeLocalPlayback() {
        try {
            mediaPlaybackController.resumeLocal();
        } catch (IllegalStateException e) {
            Log.i(TAG, "Ignoring local resume: " + e.getMessage());
        }
    }

    private void bindNewCareOutboxToActiveEndpoint() {
        MqttEndpoint endpoint = activeMqttBroker.endpoint();
        if (endpoint == null || careReportViewModel == null
                || careReportViewModel.pendingInteractions().isEmpty()) {
            return;
        }
        if (!mqttRuntimeSettings.bindOutboxOwner(endpoint)) {
            Log.e(TAG, "Care interaction outbox is bound to a different endpoint");
        }
    }

    private void bindCommandOutboxOwner() {
        if (activeMqttBroker == null) {
            return;
        }
        MqttEndpoint endpoint = activeMqttBroker.endpoint();
        if (endpoint != null && !mqttRuntimeSettings.bindOutboxOwner(endpoint)) {
            Log.e(TAG, "Command result outbox is bound to a different endpoint");
        }
    }

    private String activeMqttEndpointFingerprint() {
        MqttEndpoint endpoint = activeMqttBroker == null ? null : activeMqttBroker.endpoint();
        return endpoint == null ? null : endpoint.fingerprint();
    }

    private void flushCareInteractionOutbox() {
        if (careReportViewModel == null
                || !careOutboxFlushInProgress.compareAndSet(false, true)) {
            return;
        }
        if (!careReportViewModel.isInteractionStoreAvailable()) {
            careOutboxFlushInProgress.set(false);
            updateMqttConnectionStatus();
            return;
        }
        List<CareInteractionPersistence.OutboxRecord> pending =
                careReportViewModel.pendingInteractions();
        if (pending.isEmpty()) {
            clearOutboxOwnerIfAllDelivered();
            careOutboxFlushInProgress.set(false);
            updateMqttConnectionStatus();
            return;
        }
        MqttEndpoint endpoint = activeMqttBroker.endpoint();
        CareInteractionPersistence.OutboxRecord record = pending.get(0);
        if (endpoint == null
                || !endpoint.fingerprint().equals(record.endpointFingerprint)
                || !MqttEndpointSwitchPolicy.canFlush(
                        endpoint, totalPendingOutboxCount(),
                        mqttRuntimeSettings.outboxOwnerFingerprint())
                || !activeMqttBroker.isConnected()) {
            careOutboxFlushInProgress.set(false);
            updateMqttConnectionStatus();
            return;
        }
        MqttTopicSet topics = activeMqttBroker.topics();
        if (topics == null) {
            careOutboxFlushInProgress.set(false);
            return;
        }
        activeMqttBroker.publish(
                topics.careReportInteractionResult(),
                record.payload,
                false,
                success -> {
                    final boolean deliveredAndRemoved;
                    if (success) {
                        deliveredAndRemoved =
                                careReportViewModel.acknowledgePublished(record.requestId);
                        if (deliveredAndRemoved) {
                            careReportViewModel.publishSucceeded();
                        } else {
                            careReportViewModel.publishFailed(record.action);
                        }
                    } else {
                        deliveredAndRemoved = false;
                        careReportViewModel.publishFailed(record.action);
                    }
                    careOutboxFlushInProgress.set(false);
                    runOnUiThread(() -> {
                        if (careReportUi.isOverlayVisible()) {
                            careReportUi.render(careReportViewModel.state());
                        }
                        updateMqttConnectionStatus();
                    });
                    if (deliveredAndRemoved) {
                        clearOutboxOwnerIfAllDelivered();
                        flushCareInteractionOutbox();
                    }
                });
    }

    private int pendingCareOutboxCount() {
        if (careReportViewModel == null) {
            return 0;
        }
        // Unknown durable state blocks endpoint mutation until the store recovers.
        return careReportViewModel.isInteractionStoreAvailable()
                ? careReportViewModel.pendingInteractions().size() : 1;
    }

    private int totalPendingOutboxCount() {
        int commands = commandLedger == null ? 0 : commandLedger.pendingResultCount();
        return commands + pendingCareOutboxCount();
    }

    private void clearOutboxOwnerIfAllDelivered() {
        if (totalPendingOutboxCount() == 0) {
            mqttRuntimeSettings.clearOutboxOwner();
        }
    }

    private void startNextCanonicalCommand() {
        if (robot == null || activeCanonicalCommand != null) {
            return;
        }
        QueuedCommand queued = canonicalCommandQueue.poll();
        if (queued == null) {
            return;
        }
        if (queued.recovery != null) {
            executeRecoveredSafeCommand(queued.recovery);
            return;
        }
        CanonicalCommand command = queued.legacy;
        if (!commandLedger.markExecuting(command.getCommandId(), System.currentTimeMillis())) {
            Log.e(TAG, "Cannot mark canonical command EXECUTING: " + command.getCommandId());
            publishRawCommandResult(buildCommandResultPayload(
                    command.getCommandId(), command.getEventId(), "failed",
                    new JSONArray(), "command_store_unavailable"));
            startNextCanonicalCommand();
            return;
        }
        activeCanonicalCommand = new PendingCanonicalCommand(command);
        stateMachine.transitionTo(AgentStateMachine.State.EXECUTING);
        executeNextCanonicalAction();
    }

    private void executeNextCanonicalAction() {
        while (activeCanonicalCommand != null
                && activeCanonicalCommand.nextActionIndex
                < activeCanonicalCommand.command.getActions().size()) {
            CanonicalAction action = activeCanonicalCommand.command.getActions().get(
                    activeCanonicalCommand.nextActionIndex);
            if ("speak".equals(action.getType())
                    || "ask_clarification".equals(action.getType())) {
                startCanonicalSpeech(action);
                return;
            }
            if ("play_media".equals(action.getType())) {
                startCanonicalMedia(action);
                return;
            }

            JSONObject result = executeImmediateCanonicalAction(action);
            activeCanonicalCommand.recordResult(result);
            activeCanonicalCommand.nextActionIndex++;
        }

        if (activeCanonicalCommand != null) {
            finishCanonicalCommand();
        }
    }

    private void startCanonicalSpeech(CanonicalAction action) {
        UUID requestId = null;
        try {
            suppressLauncherConversation();
            TtsRequest request = TtsRequest.create(
                    action.getText(), false, mapTtsLanguage(action.getLanguage()));
            requestId = request.getId();
            activeCanonicalCommand.continueListeningAfterCompletion |=
                    action.shouldContinueListening();
            activeCanonicalCommand.pendingSpeechAction = action;
            if (mqttServiceBinder == null
                    || !mqttServiceBinder.beginCanonicalTts(
                            request.getId(),
                            activeCanonicalCommand.command.getCommandId(),
                            activeCanonicalCommand.command.getEventId(),
                            activeRobotId(),
                            action.getActionId(),
                            action.getType(),
                            activeCanonicalCommand.nextActionIndex
                                    >= activeCanonicalCommand.command.getActions().size() - 1)) {
                throw new IllegalStateException("canonical_runtime_unavailable");
            }
            showSubtitle(action.getText(), request.getId());
            Log.i(TAG, "CANONICAL_TTS_DISPATCH: " + action.getActionId());
            robot.speak(request);
        } catch (Exception e) {
            Log.e(TAG, "Failed to dispatch canonical TTS", e);
            if (mqttServiceBinder != null && requestId != null) {
                mqttServiceBinder.cancelCanonicalTts(requestId);
            }
            activeCanonicalCommand.recordResult(createActionResult(
                    action.getActionId(), action.getType(), "failed", safeError(e)));
            activeCanonicalCommand.pendingSpeechAction = null;
            activeCanonicalCommand.nextActionIndex++;
            executeNextCanonicalAction();
        }
    }

    private void completeCanonicalTtsAction(CanonicalCommandRuntime.Resolution resolution) {
        if (activeCanonicalCommand == null
                || activeCanonicalCommand.pendingSpeechAction == null) {
            Log.e(TAG, "Canonical TTS resolved without an active speech action");
            return;
        }
        CanonicalAction action = activeCanonicalCommand.pendingSpeechAction;
        activeCanonicalCommand.recordResult(createActionResult(
                action.getActionId(), action.getType(),
                resolution.getStatus(), resolution.getError()));
        activeCanonicalCommand.pendingSpeechAction = null;
        activeCanonicalCommand.nextActionIndex++;
        executeNextCanonicalAction();
    }

    private void startCanonicalMedia(CanonicalAction action) {
        String token = UUID.randomUUID().toString();
        try {
            int resourceId = mediaResourceId(action.getMediaId());
            activeCanonicalCommand.pendingMediaAction = action;
            activeCanonicalCommand.pendingMediaToken = token;
            canonicalMediaTracker.begin(token, action.getMediaId());

            mediaTitleText.setText(mediaTitleResourceId(action.getMediaId()));
            mediaContainer.setVisibility(View.VISIBLE);
            Log.i(TAG, "CANONICAL_MEDIA_RECEIVED: " + action.getActionId()
                    + " media_id=" + action.getMediaId());
            Uri uri = Uri.parse("android.resource://" + getPackageName() + "/" + resourceId);
            mediaPlaybackController.startLegacyRemote(token, uri);
        } catch (Exception e) {
            Log.e(TAG, "Failed to prepare canonical media", e);
            CanonicalMediaTracker.Resolution resolution =
                    canonicalMediaTracker.fail(token, safeError(e));
            if (resolution != null) {
                completeCanonicalMediaAction(resolution);
            } else {
                activeCanonicalCommand.recordResult(createMediaActionResult(
                        action, "failed", safeError(e)));
                activeCanonicalCommand.pendingMediaAction = null;
                activeCanonicalCommand.pendingMediaToken = null;
                activeCanonicalCommand.nextActionIndex++;
                executeNextCanonicalAction();
            }
        }
    }

    private void cancelCanonicalMedia(String reason) {
        if (activeCanonicalCommand == null
                || activeCanonicalCommand.pendingMediaAction == null
                || activeCanonicalCommand.pendingMediaToken == null) {
            return;
        }
        CanonicalAction action = activeCanonicalCommand.pendingMediaAction;
        String token = activeCanonicalCommand.pendingMediaToken;
        try {
            mediaPlaybackController.stopLegacyRemote(token);
        } catch (IllegalStateException e) {
            Log.w(TAG, "Legacy media controller was not active during cancellation", e);
        }
        CanonicalMediaTracker.Resolution resolution =
                canonicalMediaTracker.cancel(token, reason);
        if (resolution != null) {
            Log.i(TAG, "CANONICAL_MEDIA_CANCELLED: " + action.getActionId()
                    + " media_id=" + action.getMediaId() + " reason=" + reason);
            completeCanonicalMediaAction(resolution);
        }
    }

    private void completeCanonicalMediaAction(CanonicalMediaTracker.Resolution resolution) {
        if (activeCanonicalCommand == null
                || activeCanonicalCommand.pendingMediaAction == null) {
            Log.e(TAG, "Canonical media resolved without an active media action");
            clearMediaPlaybackUi();
            return;
        }
        CanonicalAction action = activeCanonicalCommand.pendingMediaAction;
        clearMediaPlaybackUi();
        activeCanonicalCommand.recordResult(createMediaActionResult(
                action, resolution.getStatus(), resolution.getError()));
        activeCanonicalCommand.pendingMediaAction = null;
        activeCanonicalCommand.pendingMediaToken = null;
        activeCanonicalCommand.nextActionIndex++;
        executeNextCanonicalAction();
    }

    private JSONObject createMediaActionResult(
            CanonicalAction action, String status, String error) {
        JSONObject result = createActionResult(
                action.getActionId(), action.getType(), status, error);
        try {
            result.put("media_id", action.getMediaId());
        } catch (JSONException e) {
            Log.e(TAG, "Failed to attach media_id to action result", e);
        }
        return result;
    }

    private int mediaResourceId(String mediaId) {
        return ExerciseMediaResourceResolver.resolve(
                mediaId,
                resourceName -> getResources().getIdentifier(
                        resourceName, "raw", getPackageName()));
    }

    private int mediaTitleResourceId(String mediaId) {
        return "elderly_hand_exercise".equals(mediaId)
                ? R.string.hand_exercise_title : R.string.leg_exercise_title;
    }

    private void clearMediaPlaybackUi() {
        if (mediaContainer != null) mediaContainer.setVisibility(View.GONE);
        if (mediaTitleText != null) mediaTitleText.setText(null);
    }

    private JSONObject executeImmediateCanonicalAction(CanonicalAction action) {
        try {
            switch (action.getType()) {
                case "navigate":
                    Log.i(TAG, "ACTION_NAVIGATE_DISPATCH: " + action.getTarget());
                    robot.goTo(action.getTarget());
                    return createActionResult(
                            action.getActionId(), action.getType(), "dispatched", null);
                case "turn":
                    int signedDegrees = "left".equals(action.getDirection())
                            ? action.getDegrees() : -action.getDegrees();
                    Log.i(TAG, "ACTION_TURN_DISPATCH: " + action.getDirection()
                            + " " + action.getDegrees());
                    robot.turnBy(signedDegrees, 0.6f);
                    return createActionResult(
                            action.getActionId(), action.getType(), "dispatched", null);
                case "stop":
                    Log.i(TAG, "ACTION_STOP");
                    robot.cancelAllTtsRequests();
                    robot.stopMovement();
                    hideSubtitle();
                    return createActionResult(
                            action.getActionId(), action.getType(), "completed", null);
                case "noop":
                    Log.i(TAG, "ACTION_NOOP: " + action.getReason());
                    return createActionResult(
                            action.getActionId(), action.getType(), "completed", null);
                default:
                    throw new IllegalStateException(
                            "Unexpected validated action type: " + action.getType());
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to execute canonical action " + action.getActionId(), e);
            return createActionResult(
                    action.getActionId(), action.getType(), "failed", safeError(e));
        }
    }

    private void handleTurnAction(JSONObject json) throws JSONException {
        stateMachine.transitionTo(AgentStateMachine.State.EXECUTING);
        String direction = json.getString("direction");
        int degrees = json.getInt("degrees");
        int signedDegrees = "left".equals(direction) ? degrees : -degrees;
        Log.i(TAG, "ACTION_TURN: " + direction + " " + degrees);
        robot.turnBy(signedDegrees, 0.6f);
        stateMachine.transitionTo(AgentStateMachine.State.IDLE);
    }

    private void handleStopAction() {
        Log.i(TAG, "ACTION_STOP");
        robot.cancelAllTtsRequests();
        robot.stopMovement();
        hideSubtitle();
        stateMachine.transitionTo(AgentStateMachine.State.IDLE);
    }

    private String buildCommandResultPayload(
            String commandId,
            String eventId,
            String status,
            JSONArray actionResults,
            String error
    ) {
        try {
            JSONObject result = new JSONObject();
            result.put("schema_version", "1.0");
            result.put("command_id", commandId);
            result.put("event_id", eventId);
            result.put("robot_id", activeRobotId());
            result.put("status", status);
            result.put("finished_at_ms", System.currentTimeMillis());
            result.put("results", actionResults);
            if (error != null) {
                result.put("error", error);
            }
            return result.toString();
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build command result", e);
            return null;
        }
    }

    private JSONObject createActionResult(
            String actionId, String type, String status, String error) {
        JSONObject result = new JSONObject();
        try {
            result.put("action_id", actionId == null ? "unknown_action" : actionId);
            result.put("type", type == null ? "unknown" : type);
            result.put("status", status);
            if (error != null) {
                result.put("error", error);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build action result", e);
        }
        return result;
    }

    private void finishCanonicalCommand() {
        PendingCanonicalCommand completed = activeCanonicalCommand;
        int actionCount = completed.command.getActions().size();
        String status;
        if (completed.cancelledCount == actionCount) {
            status = "cancelled";
        } else if (completed.failedCount == actionCount) {
            status = "failed";
        } else if (completed.failedCount > 0 || completed.cancelledCount > 0) {
            status = "partial_success";
        } else {
            status = "success";
        }
        String payload = buildCommandResultPayload(
                completed.command.getCommandId(),
                completed.command.getEventId(),
                status,
                completed.results,
                null);
        boolean serviceTerminalized = mqttServiceBinder != null
                && mqttServiceBinder.isCanonicalCommandTerminalized(
                        completed.command.getCommandId());
        if (payload != null && !serviceTerminalized) {
            CommandLedger.State terminalState = "failed".equals(status)
                    ? CommandLedger.State.FAILED : CommandLedger.State.COMPLETED;
            bindCommandOutboxOwner();
            if (commandLedger.markResultPending(
                    completed.command.getCommandId(), payload, terminalState,
                    System.currentTimeMillis())) {
                publishDurableCommandResult(completed.command.getCommandId(), payload);
            } else {
                Log.e(TAG, "Cannot persist canonical result before publish: "
                        + completed.command.getCommandId());
            }
        }

        activeCanonicalCommand = null;
        resumeListeningAfterCanonicalQueue |= completed.continueListeningAfterCompletion;
        if (!canonicalCommandQueue.isEmpty()) {
            startNextCanonicalCommand();
            return;
        }
        if (resumeListeningAfterCanonicalQueue) {
            resumeListeningAfterCanonicalQueue = false;
            stateMachine.transitionTo(AgentStateMachine.State.WAKEUP_TRIGGERED);
            stateMachine.transitionTo(AgentStateMachine.State.ASR_LISTENING);
            wakeupWithoutBuiltInResponse();
        } else if (stateMachine.getCurrentState() != AgentStateMachine.State.IDLE) {
            stateMachine.transitionTo(AgentStateMachine.State.IDLE);
        }
    }

    private void publishRawCommandResult(String payload) {
        if (payload == null || activeMqttBroker == null) {
            return;
        }
        MqttTopicSet topics = activeMqttBroker.topics();
        if (topics != null && activeMqttBroker.isConnected()) {
            activeMqttBroker.publish(topics.commandResult(), payload, null);
        }
    }

    private void publishDurableCommandResult(String commandId, String payload) {
        if (payload == null || activeMqttBroker == null) return;
        MqttTopicSet topics = activeMqttBroker.topics();
        MqttEndpoint endpoint = activeMqttBroker.endpoint();
        if (topics == null || endpoint == null
                || !MqttEndpointSwitchPolicy.canFlush(
                        endpoint, totalPendingOutboxCount(),
                        mqttRuntimeSettings.outboxOwnerFingerprint())
                || !activeMqttBroker.isConnected()) {
            Log.i(TAG, "Command result remains pending while MQTT is disconnected: "
                    + commandId);
            return;
        }
        activeMqttBroker.publish(topics.commandResult(), payload, success -> {
            if (success) {
                commandLedger.markResultDelivered(commandId, System.currentTimeMillis());
                clearOutboxOwnerIfAllDelivered();
            }
            if (!success) {
                Log.w(TAG, "Command result publish failed and remains pending: " + commandId);
            }
            mainHandler.post(this::flushDurableCommandResults);
        });
    }

    private String safeError(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static final class PendingCanonicalCommand {
        private final CanonicalCommand command;
        private final JSONArray results = new JSONArray();
        private int nextActionIndex;
        private int failedCount;
        private int cancelledCount;
        private CanonicalAction pendingSpeechAction;
        private CanonicalAction pendingMediaAction;
        private String pendingMediaToken;
        private boolean continueListeningAfterCompletion;

        private PendingCanonicalCommand(CanonicalCommand command) {
            this.command = command;
        }

        private void recordResult(JSONObject result) {
            results.put(result);
            if ("failed".equals(result.optString("status"))) {
                failedCount++;
            } else if ("cancelled".equals(result.optString("status"))) {
                cancelledCount++;
            }
        }
    }

    private static final class QueuedCommand {
        private final CanonicalCommand legacy;
        private final CommandLedger.Record recovery;

        private QueuedCommand(
                CanonicalCommand legacy,
                CommandLedger.Record recovery) {
            this.legacy = legacy;
            this.recovery = recovery;
        }

        private static QueuedCommand legacy(CanonicalCommand command) {
            return new QueuedCommand(command, null);
        }

        private static QueuedCommand recovery(CommandLedger.Record record) {
            return new QueuedCommand(null, record);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Voice & ASR Callbacks
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void onWakeupWord(@NonNull String wakeupWord, int direction) {
        long wakeupTimeMs = System.currentTimeMillis();
        if (acceptingTemiAsr) {
            Log.i(TAG, "Temi ASR wakeup accepted: " + wakeupWord
                    + " dir=" + direction + " time=" + wakeupTimeMs);
            return;
        }
        Log.i(TAG, "Ignoring Temi system wake word: " + wakeupWord
                + " dir=" + direction + " time=" + wakeupTimeMs);
        suppressLauncherConversation();
    }

    @Override
    public void onAsrResult(@NonNull String text, @NonNull SttLanguage sttLanguage) {
        long asrCompleteTimeMs = System.currentTimeMillis();
        Log.i(TAG, "onAsrResult: '" + text + "' (lang=" + sttLanguage + ") time=" + asrCompleteTimeMs);

        if (!acceptingTemiAsr) {
            Log.i(TAG, "Ignoring ASR because it was not requested by custom wake word.");
            suppressLauncherConversation();
            stateMachine.transitionTo(AgentStateMachine.State.IDLE);
            return;
        }
        acceptingTemiAsr = false;

        if (text.isEmpty()) {
            stateMachine.transitionTo(AgentStateMachine.State.IDLE);
            return;
        }

        suppressLauncherConversation();

        try {
            JSONObject json = new JSONObject();
            String eventId = "evt_" + asrCompleteTimeMs + "_" + UUID.randomUUID().toString().substring(0, 8);
            json.put("schema_version", "1.0");
            json.put("event_id", eventId);
            json.put("robot_id", activeRobotId());
            json.put("conversation_id", activeConversationId);
            json.put("type", "asr.legacy_text");
            json.put("text", text);
            json.put("language", sttLanguage.name());
            json.put("timestamp_ms", asrCompleteTimeMs);

            if (activeMqttBroker.isConnected()) {
                activeMqttBroker.publish(
                        MqttTopicSet.EVENT_ASR_LEGACY, json.toString(), null);
            }

            stateMachine.transitionTo(AgentStateMachine.State.THINKING);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to create ASR JSON", e);
            stateMachine.transitionTo(AgentStateMachine.State.IDLE);
        }
    }

    @Override
    public void onNlpCompleted(@NonNull NlpResult nlpResult) {
        Log.i(TAG, "Ignoring Temi default NLU result: " + nlpResult);
        robot.finishConversation();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Service Initialization
    // ═══════════════════════════════════════════════════════════════════

    private void startAndBindMqttService() {
        Intent intent = new Intent(this, MqttLifecycleService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent);
        } else {
            startService(intent);
        }
        bindService(intent, mqttServiceConnection, BIND_AUTO_CREATE);
    }

    private void startAllServices() {
        updateStatus("Starting services...");
        servicesStarted = true;
        configureTemiVoiceOwnership();
        initHotwordRecognizer();

        // 1. Start the one locally configured MQTT client.
        if (activeMqttBroker != null) activeMqttBroker.connect();

        // 2. Start all WebSocket clients + Camera
        for (WebSocketClient wsc : webSocketClients) {
            wsc.connect();
        }
        if (cameraManager == null) {
            cameraManager = createCameraManager();
        }
        cameraManager.startCamera(this, this, viewFinder);

        // 3. Delayed status check
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            int wsConnected = 0;
            for (WebSocketClient wsc : webSocketClients) {
                if (wsc != null && wsc.isConnected()) wsConnected++;
            }

            String wsStatus = "WS: " + wsConnected + "/" + webSocketClients.size();
            String mqttStatus = mqttStatusText();

            updateStatus(wsStatus + " | " + mqttStatus);
            updateMqttConnectionStatus();

            stateMachine.transitionTo(AgentStateMachine.State.IDLE);
            startHotwordListening();
        }, 3000);

        speakWithoutConversationLayer("系統就緒", TtsRequest.Language.ZH_TW);
        recoverDurableCommandsWhenReady();
        startNextCanonicalCommand();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Permissions
    // ═══════════════════════════════════════════════════════════════════

    private boolean checkPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (allPermissionsGranted(grantResults)) {
                startAllServices();
            } else {
                Toast.makeText(this, "Camera and microphone permissions are required.", Toast.LENGTH_LONG).show();
                updateStatus("Permission Denied");
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════

    private ActivityInfo getActivityInfo() {
        try {
            return getPackageManager().getActivityInfo(
                    getComponentName(), PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Failed to retrieve ActivityInfo", e);
            return null;
        }
    }

    private CameraManager createCameraManager() {
        return new CameraManager(videoData -> {
            for (WebSocketClient client : webSocketClients) {
                if (client != null && client.isConnected()) {
                    client.sendVideoPacket(videoData);
                }
            }
        });
    }

    private TtsRequest.Language mapTtsLanguage(String lang) {
        String normalized = lang == null ? "ZH_TW" : lang.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        switch (normalized) {
            case "ZH_TW": return TtsRequest.Language.ZH_TW;
            case "ZH_CN": return TtsRequest.Language.ZH_CN;
            case "EN_US": return TtsRequest.Language.EN_US;
            case "JA_JP": return TtsRequest.Language.JA_JP;
            default: return TtsRequest.Language.ZH_TW;
        }
    }

    private void configureTemiVoiceOwnership() {
        try {
            robot.toggleWakeup(true);
            robot.setAsrLanguages(Collections.singletonList(SttLanguage.ZH_TW));
            Log.i(TAG, "Temi built-in wake trigger disabled; custom wake word is " + CUSTOM_WAKE_WORD);
            mainHandler.postDelayed(() ->
                    Log.i(TAG, "Temi wakeup disabled state: " + robot.isWakeupDisabled()), 500);
        } catch (Exception e) {
            Log.w(TAG, "Failed to configure Temi voice ownership", e);
        }
    }

    private void initHotwordRecognizer() {
        if (hotwordRecognizer != null) {
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "Android SpeechRecognizer is not available on this device.");
            updateStatus("Hotword unavailable");
            return;
        }

        hotwordIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        hotwordIntent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        hotwordIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW");
        hotwordIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        hotwordIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        hotwordIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());

        hotwordRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        hotwordRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                hotwordListening = true;
                updateStatus("Waiting for \"" + CUSTOM_WAKE_WORD + "\"");
            }

            @Override
            public void onBeginningOfSpeech() {
            }

            @Override
            public void onRmsChanged(float rmsdB) {
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
            }

            @Override
            public void onEndOfSpeech() {
                hotwordListening = false;
            }

            @Override
            public void onError(int error) {
                hotwordListening = false;
                Log.d(TAG, "Hotword recognizer error: " + error);
                scheduleHotwordRestart();
            }

            @Override
            public void onResults(Bundle results) {
                hotwordListening = false;
                if (containsCustomWakeWord(results)) {
                    triggerCustomWakeWord();
                } else {
                    scheduleHotwordRestart();
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                if (containsCustomWakeWord(partialResults)) {
                    triggerCustomWakeWord();
                }
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
            }
        });
    }

    private void startHotwordListening() {
        hotwordEnabled = true;
        if (hotwordRecognizer == null) {
            initHotwordRecognizer();
        }
        if (hotwordRecognizer == null || hotwordListening) {
            return;
        }
        if (stateMachine.getCurrentState() != AgentStateMachine.State.IDLE) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        try {
            hotwordListening = true;
            hotwordRecognizer.startListening(hotwordIntent);
        } catch (Exception e) {
            hotwordListening = false;
            Log.w(TAG, "Failed to start hotword recognizer", e);
            scheduleHotwordRestart();
        }
    }

    private void stopHotwordListening() {
        if (hotwordRecognizer == null) {
            return;
        }
        try {
            hotwordRecognizer.cancel();
        } catch (Exception e) {
            Log.d(TAG, "Failed to cancel hotword recognizer", e);
        }
        hotwordListening = false;
    }

    private void destroyHotwordRecognizer() {
        if (hotwordRecognizer == null) {
            return;
        }
        hotwordRecognizer.destroy();
        hotwordRecognizer = null;
        hotwordIntent = null;
        hotwordListening = false;
    }

    private void scheduleHotwordRestart() {
        mainHandler.postDelayed(() -> {
            if (hotwordEnabled && stateMachine.getCurrentState() == AgentStateMachine.State.IDLE) {
                startHotwordListening();
            }
        }, HOTWORD_RESTART_DELAY_MS);
    }

    private boolean containsCustomWakeWord(Bundle results) {
        if (results == null) {
            return false;
        }
        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null) {
            return false;
        }

        for (String match : matches) {
            String normalized = normalizeHotwordText(match);
            for (String variant : CUSTOM_WAKE_WORD_VARIANTS) {
                String normalizedVariant = normalizeHotwordText(variant);
                if (normalized.contains(normalizedVariant)) {
                    Log.i(TAG, "Custom wake word matched from phrase: " + match
                            + " variant=" + variant);
                    return true;
                }
            }
            Log.d(TAG, "Hotword phrase did not match: " + match + " normalized=" + normalized);
        }
        return false;
    }

    private String normalizeHotwordText(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder normalized = new StringBuilder();
        String lower = text.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            int type = Character.getType(ch);
            if (!Character.isWhitespace(ch)
                    && !Character.isISOControl(ch)
                    && type != Character.CONNECTOR_PUNCTUATION
                    && type != Character.DASH_PUNCTUATION
                    && type != Character.START_PUNCTUATION
                    && type != Character.END_PUNCTUATION
                    && type != Character.OTHER_PUNCTUATION
                    && type != Character.INITIAL_QUOTE_PUNCTUATION
                    && type != Character.FINAL_QUOTE_PUNCTUATION) {
                normalized.append(ch);
            }
        }
        return normalized.toString();
    }

    private String normalizeSpeech(String text) {
        if (text == null) {
            return "";
        }
        return text
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。！？、；：「」『』（）()]", "");
    }

    private void triggerCustomWakeWord() {
        if (stateMachine.getCurrentState() != AgentStateMachine.State.IDLE) {
            return;
        }
        stopHotwordListening();
        long wakeupTimeMs = System.currentTimeMillis();
        Log.i(TAG, "Custom wake word triggered: " + CUSTOM_WAKE_WORD + " time=" + wakeupTimeMs);
        stateMachine.transitionTo(AgentStateMachine.State.WAKEUP_TRIGGERED);
        stateMachine.transitionTo(AgentStateMachine.State.ASR_LISTENING);
        wakeupWithoutBuiltInResponse();
    }

    private boolean allPermissionsGranted(@NonNull int[] grantResults) {
        if (grantResults.length == 0) {
            return false;
        }
        for (int grantResult : grantResults) {
            if (grantResult != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void suppressLauncherConversation() {
        robot.finishConversation();
        robot.cancelAllTtsRequests();
        hideSubtitle();
    }

    private void wakeupWithoutBuiltInResponse() {
        stopHotwordListening();
        acceptingTemiAsr = true;
        robot.wakeup(Collections.singletonList(SttLanguage.ZH_TW));
    }

    private void speakWithoutConversationLayer(String text, TtsRequest.Language language) {
        TtsRequest request = TtsRequest.create(text, false, language);
        showSubtitle(text, request.getId());
        robot.speak(request);
    }

    private void showSubtitle(String text, UUID requestId) {
        if (text == null || text.trim().isEmpty()) {
            hideSubtitle();
            return;
        }
        activeSubtitleTtsId = requestId;
        runOnUiThread(() -> {
            subtitleText.setText(text.trim());
            subtitleText.setVisibility(View.VISIBLE);
        });
    }

    private void hideSubtitleForRequest(@NonNull TtsRequest request) {
        UUID requestId = request.getId();
        if (activeSubtitleTtsId == null || !activeSubtitleTtsId.equals(requestId)) {
            return;
        }
        hideSubtitle();
    }

    private void hideSubtitle() {
        activeSubtitleTtsId = null;
        runOnUiThread(() -> {
            subtitleText.setText("");
            subtitleText.setVisibility(View.GONE);
        });
    }

    private void initializeMqttSettingsUi() {
        MqttEndpointSelection saved = mqttRuntimeSettings.loadEndpoint();
        if (saved.status() == MqttEndpointSelection.Status.VALID
                && saved.endpoint() != null) {
            MqttEndpoint endpoint = saved.endpoint();
            mqttHostInput.setText(endpoint.host());
            mqttPortInput.setText(String.valueOf(endpoint.port()));
            mqttRobotIdInput.setText(endpoint.robotId());
        }
        mqttSettingsButton.setOnClickListener(v -> mqttSettingsPanel.setVisibility(
                mqttSettingsPanel.getVisibility() == View.VISIBLE
                        ? View.GONE : View.VISIBLE));
        mqttApplyButton.setOnClickListener(v -> applyMqttEndpointFromUi());
        mqttDisableButton.setOnClickListener(v -> disableMqttEndpointFromUi());
        mqttDiscardOutboxButton.setOnClickListener(v -> confirmDiscardMqttOutbox());
        updateMqttConnectionStatus();
    }

    private void applyStoredMqttEndpoint() {
        if (activeMqttBroker == null) {
            Log.w(TAG, "MQTT service is not bound; endpoint apply deferred");
            return;
        }
        MqttEndpointSelection selection = mqttRuntimeSettings.loadEndpoint();
        SingleActiveMqttBroker.ApplyResult result = activeMqttBroker.apply(
                selection,
                totalPendingOutboxCount(),
                mqttRuntimeSettings.outboxOwnerFingerprint());
        if (result == SingleActiveMqttBroker.ApplyResult.INVALID_CONFIGURATION) {
            Log.e(TAG, "MQTT endpoint configuration is invalid; runtime disabled");
        } else if (result == SingleActiveMqttBroker.ApplyResult.REJECTED_PENDING_OUTBOX) {
            Log.e(TAG, "MQTT endpoint activation blocked by pending outbox");
        }
        updateMqttConnectionStatus();
    }

    private void applyMqttEndpointFromUi() {
        if (activeMqttBroker == null) {
            Toast.makeText(this, "MQTT service is still starting", Toast.LENGTH_SHORT).show();
            return;
        }
        if (hasActiveRemoteCommand()) {
            Toast.makeText(
                    this,
                    "Finish the active remote command before changing MQTT",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final MqttEndpoint requested;
        try {
            int port = Integer.parseInt(mqttPortInput.getText().toString().trim());
            requested = MqttEndpoint.create(
                    mqttHostInput.getText().toString(),
                    port,
                    mqttRobotIdInput.getText().toString());
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, "Invalid MQTT endpoint", Toast.LENGTH_SHORT).show();
            return;
        }
        int pending = totalPendingOutboxCount();
        if (!MqttEndpointSwitchPolicy.canActivate(
                activeMqttBroker.endpoint(),
                requested,
                pending,
                mqttRuntimeSettings.outboxOwnerFingerprint())) {
            Toast.makeText(
                    this,
                    "Pending results block endpoint change",
                    Toast.LENGTH_LONG).show();
            updateMqttConnectionStatus();
            return;
        }
        if (!mqttRuntimeSettings.saveEndpoint(requested)) {
            Toast.makeText(this, "MQTT settings save failed", Toast.LENGTH_LONG).show();
            return;
        }
        SingleActiveMqttBroker.ApplyResult result = activeMqttBroker.apply(
                MqttEndpointSelection.valid(requested),
                pending,
                mqttRuntimeSettings.outboxOwnerFingerprint());
        if (result == SingleActiveMqttBroker.ApplyResult.REJECTED_PENDING_OUTBOX) {
            Toast.makeText(
                    this,
                    "Pending results block endpoint change",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (careReportViewModel != null) {
            CareReportStateHolder.Update update = careReportViewModel.endpointChanged();
            careReportUi.render(update.state);
            careReportUi.hideOverlay();
        }
        if (servicesStarted) {
            activeMqttBroker.connect();
        }
        updateMqttConnectionStatus();
    }

    private void disableMqttEndpointFromUi() {
        if (activeMqttBroker == null) {
            Toast.makeText(this, "MQTT service is still starting", Toast.LENGTH_SHORT).show();
            return;
        }
        if (hasActiveRemoteCommand()) {
            Toast.makeText(
                    this,
                    "Finish the active remote command before disabling MQTT",
                    Toast.LENGTH_LONG).show();
            return;
        }
        int pending = totalPendingOutboxCount();
        if (!MqttEndpointSwitchPolicy.canDisable(pending)) {
            Toast.makeText(
                    this,
                    "Discard or deliver pending results first",
                    Toast.LENGTH_LONG).show();
            updateMqttConnectionStatus();
            return;
        }
        if (!mqttRuntimeSettings.disableEndpoint()) {
            Toast.makeText(this, "MQTT settings save failed", Toast.LENGTH_LONG).show();
            return;
        }
        activeMqttBroker.apply(MqttEndpointSelection.disabled(), 0, null);
        if (careReportViewModel != null) {
            careReportViewModel.endpointChanged();
            careReportUi.hideOverlay();
        }
        updateMqttConnectionStatus();
    }

    private void confirmDiscardMqttOutbox() {
        int pending = totalPendingOutboxCount();
        if (pending == 0) {
            Toast.makeText(this, "No pending MQTT results", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Discard pending MQTT results?")
                .setMessage("This removes " + pending
                        + " undelivered result(s). Command history is retained.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Discard", (dialog, which) -> {
                    boolean careDiscarded = careReportViewModel == null
                            || careReportViewModel.discardPendingInteractions();
                    if (careDiscarded) {
                        updateMqttConnectionStatus();
                    } else {
                        Toast.makeText(
                                this, "Outbox discard failed", Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private String activeRobotId() {
        MqttEndpoint endpoint = activeMqttBroker == null ? null : activeMqttBroker.endpoint();
        return endpoint == null ? "unconfigured" : endpoint.robotId();
    }

    private boolean hasActiveRemoteCommand() {
        return activeCanonicalCommand != null;
    }

    private String mqttStatusText() {
        MqttEndpointSelection saved = mqttRuntimeSettings.loadEndpoint();
        int pending = totalPendingOutboxCount();
        if (saved.status() == MqttEndpointSelection.Status.DISABLED) {
            return "MQTT: Disabled | pending=" + pending;
        }
        if (saved.status() != MqttEndpointSelection.Status.VALID
                || saved.endpoint() == null) {
            return "MQTT: Invalid configuration | pending=" + pending;
        }
        if (pending > 0 && !MqttEndpointSwitchPolicy.canFlush(
                saved.endpoint(),
                pending,
                mqttRuntimeSettings.outboxOwnerFingerprint())) {
            return "MQTT: Blocked by pending results | pending=" + pending;
        }
        String state = mqttLifecycleService == null
                ? "DISCONNECTED" : mqttLifecycleService.state().name();
        return "MQTT: " + state + " " + saved.endpoint().displayName()
                + " | pending=" + pending;
    }

    private void updateMqttConnectionStatus() {
        runOnUiThread(() -> {
            if (mqttStatusText == null || mqttRuntimeSettings == null) {
                return;
            }
            String text = mqttStatusText();
            mqttStatusText.setText(text);
            mqttStatusText.setTextColor(
                    activeMqttBroker != null && activeMqttBroker.isConnected()
                            ? 0xFF00FF00 : 0xFFFF6666);
            if (mqttDiscardOutboxButton != null) {
                mqttDiscardOutboxButton.setEnabled(
                        totalPendingOutboxCount() > 0);
            }
        });
    }

    private void updateStatus(String text) {
        runOnUiThread(() -> statusText.setText(text));
    }

    private void updateAgentState(String state) {
        runOnUiThread(() -> agentStateText.setText("Agent: " + state));
    }
}
