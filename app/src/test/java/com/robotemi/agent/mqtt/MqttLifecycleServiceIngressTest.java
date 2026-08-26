package com.robotemi.agent.mqtt;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.robotemi.agent.command.CanonicalCommandRuntime;
import com.robotemi.agent.command.CommandLedger;
import com.robotemi.agent.media.v11.MediaV11Command;
import com.robotemi.agent.media.v11.MediaV11PlaybackBinding;
import com.robotemi.agent.media.v11.MediaV11Result;
import com.robotemi.agent.media.v11.MediaV11ResultConformanceTest;
import com.robotemi.sdk.Robot;
import com.robotemi.sdk.TtsRequest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Service-owned canonical ingress and Activity-observer lifecycle coverage. */
public class MqttLifecycleServiceIngressTest {
    private static final String ROBOT_ID = "temi-01";

    @Test
    public void retainedCanonicalSpeakIsRejectedBeforeServiceIngress() {
        Harness harness = new Harness();

        harness.emitTopic(
                "temi/temi-01/cmd/request",
                speakPayload("event-retained-canonical", "cmd-retained-canonical",
                        "action-retained-canonical", "must not speak"),
                true);

        assertEquals(0, harness.speech.requests.size());
        assertNull(harness.persistence.record("cmd-retained-canonical"));
        assertEquals(0, harness.connection.publishAttempts);
        assertEquals(0, harness.service.bufferedMessageCountForTest());
        assertRetainedRejected(harness, MqttIngressPolicy.RETAINED_COMMAND_REQUEST);
    }

    @Test
    public void retainedMediaPlayIsRejectedBeforeServiceIngress() {
        Harness harness = new Harness(true);
        RecordingMediaBinding binding = new RecordingMediaBinding();
        MqttLifecycleService.LocalBinder binder = harness.service.new LocalBinder();
        binder.attachMediaV11PlaybackBinding(binding);

        harness.emitTopic(
                "temi/temi-01/cmd/request",
                mediaPlayPayload("event-retained-media", "cmd-retained-media",
                        "elderly_hand_exercise"),
                true);

        assertNull(harness.persistence.record("cmd-retained-media"));
        assertEquals(0, binding.startCount);
        assertEquals(0, harness.connection.publishAttempts);
        assertEquals(0, harness.service.bufferedMessageCountForTest());
        assertRetainedRejected(harness, MqttIngressPolicy.RETAINED_COMMAND_REQUEST);
    }

    @Test
    public void retainedLegacySpeakIsRejectedBeforeActivityForwarding() {
        Harness harness = new Harness();
        RecordingUiListener listener = attachListener(harness);

        harness.emitTopic(MqttTopicSet.ACTION_SPEAK, "{\"text\":\"retained\"}", true);

        assertEquals(0, listener.messageCount);
        assertEquals(0, harness.service.bufferedMessageCountForTest());
        assertRetainedRejected(harness, MqttIngressPolicy.RETAINED_LEGACY_SPEAK);
    }

    @Test
    public void retainedLegacyNavigateIsRejectedBeforeActivityForwarding() {
        Harness harness = new Harness();
        RecordingUiListener listener = attachListener(harness);

        harness.emitTopic(MqttTopicSet.ACTION_NAVIGATE, "{\"target\":\"kitchen\"}", true);

        assertEquals(0, listener.messageCount);
        assertEquals(0, harness.service.bufferedMessageCountForTest());
        assertRetainedRejected(harness, MqttIngressPolicy.RETAINED_LEGACY_NAVIGATE);
    }

    @Test
    public void retainedLegacyWakeupIsRejectedBeforeActivityForwarding() {
        Harness harness = new Harness();
        RecordingUiListener listener = attachListener(harness);

        harness.emitTopic(MqttTopicSet.ACTION_WAKEUP, "{\"word\":\"retained\"}", true);

        assertEquals(0, listener.messageCount);
        assertEquals(0, harness.service.bufferedMessageCountForTest());
        assertRetainedRejected(harness, MqttIngressPolicy.RETAINED_LEGACY_WAKEUP);
    }

    @Test
    public void retainedObserverMessageStillReachesActivityWithRetainedFlag() {
        Harness harness = new Harness();
        RecordingUiListener listener = attachListener(harness);

        harness.emitTopic("temi/temi-01/status", "observer", true);

        assertEquals(1, listener.messageCount);
        assertTrue(listener.lastRetained);
        assertEquals(0, harness.service.bufferedMessageCountForTest());
    }

    @Test
    public void retainedIdentityAndCareMessagesAreNotGloballyRejected() {
        Harness harness = new Harness(false, true, true);
        RecordingUiListener listener = attachListener(harness);
        MqttTopicSet topics = harness.broker.topics();

        harness.emitTopic(topics.residentIdentityResult(), "identity", true);
        harness.emitTopic(topics.careReport(), "care", true);

        assertEquals(2, listener.messageCount);
        assertTrue(listener.lastRetained);
        assertEquals(0, harness.service.bufferedMessageCountForTest());
        assertEquals(0, harness.diagnostics.count("ingress", "retained_rejected"));
    }

    @Test
    public void detachedMediaPlayIsConsumedByServiceInsteadOfUiBuffer() {
        Harness harness = new Harness(true);

        harness.emitTopic(
                "temi/temi-01/cmd/request",
                mediaPlayPayload("media-event-red", "media-command-red", "elderly_hand_exercise"));

        CommandLedger.Record record = harness.persistence.record("media-command-red");
        assertNotNull("detached media command must reach the service ledger", record);
        assertEquals(CommandLedger.State.RECEIVED, record.state);
        assertEquals(0, harness.service.bufferedMessageCountForTest());
        assertEquals(0, harness.speech.requests.size());
    }

    @Test
    public void detachedMediaPlayWaitsThenAttachesAndCompletesExactlyOnce() {
        Harness harness = new Harness(true);
        harness.emitTopic(
                "temi/temi-01/cmd/request",
                mediaPlayPayload("media-event-green", "media-command-green", "elderly_hand_exercise"));

        CommandLedger.Record waiting = harness.persistence.record("media-command-green");
        assertEquals(CommandLedger.State.RECEIVED, waiting.state);
        assertEquals("WAITING_FOR_MEDIA_ACTIVITY", waiting.mediaState);

        RecordingMediaBinding binding = new RecordingMediaBinding();
        MqttLifecycleService.LocalBinder binder = harness.service.new LocalBinder();
        long generation = binder.attachMediaV11PlaybackBinding(binding);

        assertEquals(1, binding.startCount);
        CommandLedger.Record executing = harness.persistence.record("media-command-green");
        assertEquals(CommandLedger.State.EXECUTING, executing.state);
        assertEquals("DISPATCHING", executing.mediaState);
        assertNotNull(executing.mediaLeaseId);
        assertEquals(generation, executing.mediaBindingGeneration);

        binding.started();
        assertEquals("PLAYING", harness.persistence.record("media-command-green").mediaState);
        binding.completed();

        CommandLedger.Record terminal = harness.persistence.record("media-command-green");
        assertEquals("terminal=" + terminal.state + ",media=" + terminal.mediaState
                + ",result=" + terminal.resultState,
                CommandLedger.State.COMPLETED, terminal.state);
        assertEquals(CommandLedger.ResultState.DELIVERED, terminal.resultState);
        assertEquals(1, binding.startCount);
        assertEquals(1, harness.connection.terminalPublishCount);
        for (String payload : harness.connection.publishedPayloads) {
            MediaV11ResultConformanceTest.assertConforms(MediaV11Result.fromJson(payload));
        }
    }

    @Test
    public void staleBindingDetachCannotClearReplacementOrDuplicateDispatch() {
        Harness harness = new Harness(true);
        RecordingMediaBinding first = new RecordingMediaBinding();
        RecordingMediaBinding second = new RecordingMediaBinding();
        MqttLifecycleService.LocalBinder binder = harness.service.new LocalBinder();
        long firstGeneration = binder.attachMediaV11PlaybackBinding(first);
        long secondGeneration = binder.attachMediaV11PlaybackBinding(second);

        binder.detachMediaV11PlaybackBinding(firstGeneration);
        harness.emitTopic(
                "temi/temi-01/cmd/request",
                mediaPlayPayload("media-event-stale", "media-command-stale", "elderly_hand_exercise"));

        assertEquals(0, first.startCount);
        assertEquals(1, second.startCount);
        assertEquals(secondGeneration,
                harness.persistence.record("media-command-stale").mediaBindingGeneration);
    }

    @Test
    public void mediaDuplicateCompletionCallbackPersistsAndPublishesOnce() {
        Harness harness = new Harness(true);
        RecordingMediaBinding binding = new RecordingMediaBinding();
        MqttLifecycleService.LocalBinder binder = harness.service.new LocalBinder();
        binder.attachMediaV11PlaybackBinding(binding);
        harness.emitTopic(
                "temi/temi-01/cmd/request",
                mediaPlayPayload("media-event-duplicate", "media-command-duplicate",
                        "elderly_hand_exercise"));

        binding.started();
        binding.completed();
        int publishes = harness.connection.terminalPublishCount;
        binding.completed();

        CommandLedger.Record record = harness.persistence.record("media-command-duplicate");
        assertEquals(CommandLedger.State.COMPLETED, record.state);
        assertEquals(CommandLedger.ResultState.DELIVERED, record.resultState);
        assertEquals(publishes, harness.connection.terminalPublishCount);
        assertEquals(1, publishes);
    }

    @Test
    public void invalidMediaPayloadIsDurablyRejectedWithoutPlayerDispatch() {
        Harness harness = new Harness(true);
        String invalid = mediaPlayPayload(
                "media-event-invalid", "media-command-invalid", "elderly_hand_exercise")
                .replace("\"parameters\":{}", "\"parameters\":{\"unexpected\":true}");

        harness.emitTopic("temi/temi-01/cmd/request", invalid);

        CommandLedger.Record record = harness.persistence.record("media-command-invalid");
        assertNotNull(record);
        assertEquals(CommandLedger.State.FAILED, record.state);
        assertEquals(CommandLedger.ResultState.DELIVERED, record.resultState);
        assertEquals(0, harness.speech.requests.size());
        assertEquals(0, harness.service.bufferedMessageCountForTest());
        JsonObject result = JsonParser.parseString(record.resultPayload).getAsJsonObject();
        assertEquals("rejected", result.get("status").getAsString());
        assertEquals("UNSUPPORTED_MEDIA_ACTION", result.get("error_code").getAsString());
        MediaV11ResultConformanceTest.assertConforms(
                MediaV11Result.fromJson(record.resultPayload));
    }

    @Test
    public void mediaTerminalPublishFailureStaysPendingAndReplaysAfterReconnect() {
        Harness harness = new Harness(true);
        RecordingMediaBinding binding = new RecordingMediaBinding();
        MqttLifecycleService.LocalBinder binder = harness.service.new LocalBinder();
        binder.attachMediaV11PlaybackBinding(binding);
        harness.emitTopic(
                "temi/temi-01/cmd/request",
                mediaPlayPayload("media-event-replay", "media-command-replay",
                        "elderly_hand_exercise"));
        binding.started();
        harness.connection.publishSucceeds = false;
        binding.completed();

        CommandLedger.Record pending = harness.persistence.record("media-command-replay");
        assertEquals(CommandLedger.State.RESULT_PENDING, pending.state);
        assertEquals(CommandLedger.ResultState.PENDING, pending.resultState);
        assertEquals(1, harness.connection.terminalPublishCount);

        harness.connection.publishSucceeds = true;
        harness.service.flushMediaV11RuntimeOutboxForTest();
        CommandLedger.Record delivered = harness.persistence.record("media-command-replay");
        assertEquals(CommandLedger.State.COMPLETED, delivered.state);
        assertEquals(CommandLedger.ResultState.DELIVERED, delivered.resultState);
        assertEquals(2, harness.connection.terminalPublishCount);
    }

    @Test
    public void mediaProcessRestartFailsClosedWithoutReplayingPlayer() {
        Harness harness = new Harness(true);
        RecordingMediaBinding first = new RecordingMediaBinding();
        MqttLifecycleService.LocalBinder binder = harness.service.new LocalBinder();
        binder.attachMediaV11PlaybackBinding(first);
        harness.emitTopic(
                "temi/temi-01/cmd/request",
                mediaPlayPayload("media-event-restart", "media-command-restart",
                        "elderly_hand_exercise"));
        assertEquals(1, first.startCount);

        MqttLifecycleService recreated = new MqttLifecycleService(
                new RecordingSpeechPort(harness.persistence), harness.persistence,
                new ManualScheduler(), null, harness.settings, harness.diagnostics, true);
        RecordingMediaBinding second = new RecordingMediaBinding();
        recreated.mediaV11RuntimeForTest().reconcileAfterProcessRestart();

        assertEquals(0, second.startCount);
        CommandLedger.Record record = harness.persistence.record("media-command-restart");
        assertEquals(CommandLedger.State.RESULT_PENDING, record.state);
        assertEquals(CommandLedger.ResultState.PENDING, record.resultState);
    }

    @Test
    public void detachedSpeakReachesServiceIngressAndSpeechPortExactlyOnce() throws Exception {
        Harness harness = new Harness();

        harness.emit(speakPayload("event-service", "cmd-service", "action-service", "hello"));

        assertEquals(1, harness.speech.requests.size());
        TtsRequest request = harness.speech.requests.get(0);
        assertNotNull(request);
        assertEquals(
                CommandLedger.State.EXECUTING,
                harness.persistence.record("cmd-service").state);
        assertEquals("EXECUTING", harness.speech.ledgerStatesBeforeDispatch.get(0));
        assertEquals(1, harness.diagnostics.count("speech", "dispatch"));

        // A duplicate while EXECUTING is consumed by the service ledger and
        // cannot create a second SDK request.
        harness.emit(speakPayload("event-service", "cmd-service", "action-service", "hello"));
        assertEquals(1, harness.speech.requests.size());

        harness.complete(request, TtsRequest.Status.COMPLETED);

        CommandLedger.Record record = harness.persistence.record("cmd-service");
        assertEquals(CommandLedger.State.COMPLETED, record.state);
        assertEquals(CommandLedger.ResultState.DELIVERED, record.resultState);
        assertEquals(1, harness.connection.publishAttempts);
        assertEquals(1, harness.connection.publishDeliveries);
        assertEquals("RESULT_PENDING", harness.connection.stateBeforePublish);
        JsonObject result = JsonParser.parseString(harness.connection.lastPayload).getAsJsonObject();
        assertEquals("1.0", result.get("schema_version").getAsString());
        assertEquals("cmd-service", result.get("command_id").getAsString());
        assertEquals("event-service", result.get("event_id").getAsString());
        assertEquals(ROBOT_ID, result.get("robot_id").getAsString());
        assertEquals("success", result.get("status").getAsString());
        assertTrue(result.get("finished_at_ms").isJsonPrimitive());
        assertTrue(result.getAsJsonPrimitive("finished_at_ms").isNumber());
        assertEquals(1, result.getAsJsonArray("results").size());
        JsonObject actionResult = result.getAsJsonArray("results")
                .get(0).getAsJsonObject();
        assertEquals(
                "action-service",
                actionResult.get("action_id").getAsString());
        assertEquals("speak", actionResult.get("type").getAsString());
        assertEquals("completed", actionResult.get("status").getAsString());
        assertFalse(result.has("error"));
        assertFalse(actionResult.has("error"));
        assertEquals(1, harness.diagnostics.count("terminal", "persisted"));
        assertEquals(1, harness.diagnostics.count("publish", "attempt"));
        assertEquals(1, harness.diagnostics.count("publish", "delivered"));
    }

    @Test
    public void errorTtsPublishesFailedCanonicalResultWithError() throws Exception {
        Harness harness = new Harness();

        harness.emit(speakPayload("event-error", "cmd-error", "action-error", "hello"));
        harness.complete(harness.speech.requests.get(0), TtsRequest.Status.ERROR);

        CommandLedger.Record record = harness.persistence.record("cmd-error");
        assertEquals(CommandLedger.State.FAILED, record.state);
        assertEquals(CommandLedger.ResultState.DELIVERED, record.resultState);
        assertEquals(1, harness.connection.publishAttempts);
        assertEquals(1, harness.connection.publishDeliveries);

        JsonObject result = JsonParser.parseString(harness.connection.lastPayload).getAsJsonObject();
        assertEquals("1.0", result.get("schema_version").getAsString());
        assertEquals("cmd-error", result.get("command_id").getAsString());
        assertEquals("event-error", result.get("event_id").getAsString());
        assertEquals(ROBOT_ID, result.get("robot_id").getAsString());
        assertEquals("failed", result.get("status").getAsString());
        assertTrue(result.getAsJsonPrimitive("finished_at_ms").isNumber());
        assertEquals("tts_error", result.get("error").getAsString());
        JsonObject actionResult = result.getAsJsonArray("results")
                .get(0).getAsJsonObject();
        assertEquals("action-error", actionResult.get("action_id").getAsString());
        assertEquals("speak", actionResult.get("type").getAsString());
        assertEquals("failed", actionResult.get("status").getAsString());
        assertEquals("tts_error", actionResult.get("error").getAsString());
    }

    @Test
    public void runtimeFailurePropagatesWithoutTerminalResultOrPublish() {
        Harness harness = new Harness(new ThrowingScheduler());

        try {
            harness.emit(speakPayload("event-runtime-failure", "cmd-runtime-failure",
                    "action-runtime-failure", "hello"));
            throw new AssertionError("runtime failure was swallowed");
        } catch (IllegalStateException expected) {
            // The listener must propagate runtime setup failure and must not
            // manufacture a terminal result or claim delivery.
        }

        CommandLedger.Record record = harness.persistence.record("cmd-runtime-failure");
        assertNotNull(record);
        assertEquals(CommandLedger.State.EXECUTING, record.state);
        assertEquals(CommandLedger.ResultState.NONE, record.resultState);
        assertEquals(0, harness.speech.requests.size());
        assertEquals(0, harness.connection.publishAttempts);
        assertEquals(0, harness.connection.publishDeliveries);
    }

    @Test
    public void activityRecreationDoesNotDuplicateSpeakDispatchOrTerminalResult()
            throws Exception {
        Harness harness = new Harness();
        MqttLifecycleService.LocalBinder binder = harness.service.new LocalBinder();
        RecordingUiListener activityA = new RecordingUiListener();
        RecordingUiListener activityB = new RecordingUiListener();
        MqttLifecycleService.ListenerRegistration tokenA = binder.attachListener(activityA);
        binder.detachListener(tokenA);

        harness.emit(speakPayload("event-recreate", "cmd-recreate", "action-recreate", "hello"));
        MqttLifecycleService.ListenerRegistration tokenB = binder.attachListener(activityB);

        assertEquals(1, harness.speech.requests.size());
        assertEquals(0, activityB.messageCount);
        harness.complete(harness.speech.requests.get(0), TtsRequest.Status.COMPLETED);
        assertEquals(1, harness.connection.publishAttempts);
        binder.detachListener(tokenB);
    }

    @Test
    public void staleActivityDetachCannotClearReplacementObserver() {
        Harness harness = new Harness();
        MqttLifecycleService.LocalBinder binder = harness.service.new LocalBinder();
        RecordingUiListener activityA = new RecordingUiListener();
        RecordingUiListener activityB = new RecordingUiListener();
        MqttLifecycleService.ListenerRegistration tokenA = binder.attachListener(activityA);
        binder.attachListener(activityB);

        binder.detachListener(tokenA);
        harness.emitTopic("temi/temi-01/status", "ui-only");

        assertEquals(0, activityA.messageCount);
        assertEquals(1, activityB.messageCount);
        assertEquals(0, harness.service.bufferedMessageCountForTest());
    }

    @Test
    public void duplicateTtsCallbackProducesOneTerminalPersistAndPublish() throws Exception {
        Harness harness = new Harness();
        harness.emit(speakPayload("event-duplicate", "cmd-duplicate", "action-duplicate", "hello"));
        TtsRequest request = harness.speech.requests.get(0);

        harness.complete(request, TtsRequest.Status.COMPLETED);
        harness.complete(request, TtsRequest.Status.ERROR);

        assertEquals(1, harness.connection.publishAttempts);
        assertEquals(1, harness.connection.publishDeliveries);
        assertEquals(1, harness.diagnostics.count("terminal", "persisted"));
        assertEquals(CommandLedger.State.COMPLETED,
                harness.persistence.record("cmd-duplicate").state);
    }

    @Test
    public void nonSpeakUiMessageRemainsBoundedAndObserverOnly() {
        Harness harness = new Harness();
        MqttLifecycleService.LocalBinder binder = harness.service.new LocalBinder();
        RecordingUiListener activityA = new RecordingUiListener();
        RecordingUiListener activityB = new RecordingUiListener();
        MqttLifecycleService.ListenerRegistration tokenA = binder.attachListener(activityA);
        MqttLifecycleService.ListenerRegistration tokenB = binder.attachListener(activityB);
        binder.detachListener(tokenA);

        harness.emitTopic("temi/temi-01/cmd/request",
                navigatePayload("event-ui-direct-1", "cmd-ui-direct-1"));
        harness.emitTopic("temi/temi-01/cmd/request",
                navigatePayload("event-ui-direct-2", "cmd-ui-direct-2"));

        assertEquals(2, activityB.messageCount);
        assertEquals("cmd-ui-direct-1", activityB.commandIds.get(0));
        assertEquals("cmd-ui-direct-2", activityB.commandIds.get(1));

        binder.detachListener(tokenB);
        for (int i = 0; i < 257; i++) {
            harness.emit(navigatePayload("event-ui-" + i, "cmd-ui-" + i));
        }

        assertEquals(256, harness.service.bufferedMessageCountForTest());
        assertEquals(0, harness.speech.requests.size());
        assertNull(harness.persistence.record("cmd-ui-256"));

        RecordingUiListener replayObserver = new RecordingUiListener();
        MqttLifecycleService.ListenerRegistration replayToken = binder.attachListener(replayObserver);
        assertEquals(256, replayObserver.messageCount);
        assertEquals("cmd-ui-1", replayObserver.commandIds.get(0));
        assertEquals("cmd-ui-256", replayObserver.commandIds.get(255));
        assertEquals(0, harness.service.bufferedMessageCountForTest());
        assertEquals(0, harness.connection.publishAttempts);
        assertEquals(0, harness.connection.publishDeliveries);
        binder.detachListener(replayToken);
    }

    @Test
    public void serviceDoesNotCreateASecondReconnectOwner() {
        Harness harness = new Harness();
        MqttLifecycleService.LocalBinder binder = harness.service.new LocalBinder();
        MqttLifecycleService.ListenerRegistration first =
                binder.attachListener(new RecordingUiListener());
        binder.detachListener(first);
        MqttLifecycleService.ListenerRegistration second =
                binder.attachListener(new RecordingUiListener());

        assertEquals(SingleActiveMqttBroker.ApplyResult.UNCHANGED,
                harness.broker.apply(
                        MqttEndpointSelection.valid(MqttEndpoint.create("broker.example", 1883, ROBOT_ID)),
                        0,
                        null));
        harness.broker.connect();

        assertEquals(1, harness.factory.createCount);
        assertEquals(1, harness.connection.connectCount);
        binder.detachListener(second);
    }

    private static RecordingUiListener attachListener(Harness harness) {
        RecordingUiListener listener = new RecordingUiListener();
        MqttLifecycleService.LocalBinder binder = harness.service.new LocalBinder();
        binder.attachListener(listener);
        return listener;
    }

    private static void assertRetainedRejected(Harness harness, String topicClass) {
        assertEquals(1, harness.diagnostics.count(
                "ingress", topicClass, "retained_rejected"));
    }

    private static String speakPayload(
            String eventId, String commandId, String actionId, String text) {
        return "{\"schema_version\":\"1.0\","
                + "\"command_id\":\"" + commandId + "\","
                + "\"event_id\":\"" + eventId + "\","
                + "\"robot_id\":\"" + ROBOT_ID + "\","
                + "\"actions\":[{\"action_id\":\"" + actionId + "\","
                + "\"type\":\"speak\",\"text\":\"" + text + "\"}]}";
    }

    private static String navigatePayload(String eventId, String commandId) {
        return "{\"schema_version\":\"1.0\","
                + "\"command_id\":\"" + commandId + "\","
                + "\"event_id\":\"" + eventId + "\","
                + "\"robot_id\":\"" + ROBOT_ID + "\","
                + "\"actions\":[{\"action_id\":\"action-" + commandId + "\","
                + "\"type\":\"navigate\",\"target\":\"kitchen\"}]}";
    }

    private static String mediaPlayPayload(
            String eventId, String commandId, String videoId) {
        return "{\"schema_version\":\"1.1\","
                + "\"message_type\":\"video.command\","
                + "\"command_id\":\"" + commandId + "\","
                + "\"request_id\":\"" + commandId + "\","
                + "\"event_id\":\"" + eventId + "\","
                + "\"robot_id\":\"" + ROBOT_ID + "\","
                + "\"resident_id\":\"resident-1\","
                + "\"action\":\"play_video\","
                + "\"execution_class\":\"serialized_execution\","
                + "\"target_playback_session_id\":null,"
                + "\"video_id\":\"" + videoId + "\","
                + "\"parameters\":{},"
                + "\"source\":\"hermes_temi_bridge\","
                + "\"timestamp\":\"2026-08-02T15:00:00Z\"}";
    }

    private static final class Harness {
        final MemoryPersistence persistence;
        final RecordingDiagnostics diagnostics;
        final RecordingSpeechPort speech;
        final RecordingConnection connection;
        final RecordingFactory factory;
        final MemoryPreferences preferences;
        final SharedPreferencesMqttRuntimeSettings settings;
        final ManualScheduler mediaScheduler;
        final MqttLifecycleService service;
        final SingleActiveMqttBroker broker;

        Harness() {
            this(new ManualScheduler());
        }

        Harness(boolean mediaEnabled) {
            this(mediaEnabled, false, false);
        }

        Harness(boolean mediaEnabled, boolean residentIdentityEnabled, boolean careReportEnabled) {
            persistence = new MemoryPersistence();
            diagnostics = new RecordingDiagnostics();
            speech = new RecordingSpeechPort(persistence);
            connection = new RecordingConnection(persistence);
            factory = new RecordingFactory(connection);
            preferences = new MemoryPreferences();
            settings = new SharedPreferencesMqttRuntimeSettings(preferences);
            mediaScheduler = new ManualScheduler();
            service = new MqttLifecycleService(
                    speech,
                    persistence,
                    mediaScheduler,
                    null,
                    settings,
                    diagnostics,
                    mediaEnabled);
            connection.connected = true;
            broker = new SingleActiveMqttBroker(
                    factory, "client", service.brokerListenerForTest(),
                    residentIdentityEnabled, careReportEnabled);
            service.bindBrokerForTest(broker);
            broker.apply(
                    MqttEndpointSelection.valid(
                            MqttEndpoint.create("broker.example", 1883, ROBOT_ID)),
                    0,
                    null);
            broker.connect();
        }

        Harness(CanonicalCommandRuntime.Scheduler scheduler) {
            persistence = new MemoryPersistence();
            diagnostics = new RecordingDiagnostics();
            speech = new RecordingSpeechPort(persistence);
            connection = new RecordingConnection(persistence);
            factory = new RecordingFactory(connection);
            preferences = new MemoryPreferences();
            settings = new SharedPreferencesMqttRuntimeSettings(preferences);
            mediaScheduler = null;
            service = new MqttLifecycleService(
                    speech,
                    persistence,
                    scheduler,
                    null,
                    new SharedPreferencesMqttRuntimeSettings(preferences),
                    diagnostics);
            connection.connected = true;
            broker = new SingleActiveMqttBroker(
                    factory, "client", service.brokerListenerForTest());
            service.bindBrokerForTest(broker);
            broker.apply(
                    MqttEndpointSelection.valid(
                            MqttEndpoint.create("broker.example", 1883, ROBOT_ID)),
                    0,
                    null);
            broker.connect();
        }

        void emit(String payload) {
            emitTopic("temi/temi-01/cmd/request", payload);
        }

        void emitTopic(String topic, String payload) {
            emitTopic(topic, payload, false);
        }

        void emitTopic(String topic, String payload, boolean retained) {
            connection.emitMessage(topic, payload, retained);
        }

        void complete(TtsRequest request, TtsRequest.Status status) {
            request.setStatus(status);
            service.ttsListenerForTest().onTtsStatusChanged(request);
        }
    }

    private static final class RecordingSpeechPort implements CanonicalSpeechPort {
        final MemoryPersistence persistence;
        final List<TtsRequest> requests = new ArrayList<>();
        final List<String> ledgerStatesBeforeDispatch = new ArrayList<>();

        RecordingSpeechPort(MemoryPersistence persistence) {
            this.persistence = persistence;
        }

        @Override
        public void speak(TtsRequest request) {
            assertNotNull(request);
            requests.add(request);
            CommandLedger.Record record = persistence.lastRecord();
            ledgerStatesBeforeDispatch.add(record == null || record.state == null
                    ? "none" : record.state.name());
        }
    }

    private static final class RecordingMediaBinding implements MediaV11PlaybackBinding {
        int startCount;
        long generation;
        String leaseId;
        String sessionId;
        Callback callback;

        @Override
        public void start(MediaV11Command command, String sessionId, String leaseId,
                          long generation, Callback callback) {
            startCount++;
            this.sessionId = sessionId;
            this.leaseId = leaseId;
            this.generation = generation;
            this.callback = callback;
        }

        @Override
        public void pause(String sessionId, String leaseId, long generation, Callback callback) {
            this.sessionId = sessionId;
            this.leaseId = leaseId;
            this.generation = generation;
            this.callback = callback;
        }

        @Override
        public void resume(String sessionId, String leaseId, long generation, Callback callback) {
            this.sessionId = sessionId;
            this.leaseId = leaseId;
            this.generation = generation;
            this.callback = callback;
        }

        @Override
        public void stop(String sessionId, String leaseId, long generation, Callback callback) {
            this.sessionId = sessionId;
            this.leaseId = leaseId;
            this.generation = generation;
            this.callback = callback;
        }

        @Override
        public void detach(long generation) {
            if (this.generation == generation) callback = null;
        }

        void started() {
            assertNotNull(callback);
            callback.onStarted(generation, leaseId, sessionId);
        }

        void completed() {
            assertNotNull(callback);
            callback.onCompleted(generation, leaseId, sessionId);
        }

        void cancelled() {
            assertNotNull(callback);
            callback.onCancelled(generation, leaseId, sessionId);
        }
    }

    private static final class RecordingFactory implements SingleActiveMqttBroker.Factory {
        final RecordingConnection connection;
        int createCount;

        RecordingFactory(RecordingConnection connection) {
            this.connection = connection;
        }

        @Override
        public MqttConnection create(
                MqttEndpoint endpoint, String clientId, String[] subscribedTopics) {
            createCount++;
            return connection;
        }
    }

    private static final class RecordingConnection implements MqttConnection {
        final MemoryPersistence persistence;
        @Nullable InboundMessageListener inboundMessageListener;
        @Nullable ConnectionListener connectionListener;
        boolean connected;
        int connectCount;
        int publishAttempts;
        int publishDeliveries;
        int terminalPublishCount;
        boolean publishSucceeds = true;
        String lastPayload;
        final List<String> publishedPayloads = new ArrayList<>();
        String stateBeforePublish;

        RecordingConnection(MemoryPersistence persistence) {
            this.persistence = persistence;
        }

        @Override
        public void setMessageListener(@Nullable MessageListener listener) {
            inboundMessageListener = listener == null
                    ? null : (topic, payload, retained) -> listener.onMessage(topic, payload);
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
            connectCount++;
            connected = true;
        }

        @Override public void disconnect() { connected = false; }
        @Override public void shutdown() { connected = false; }

        @Override
        public void publish(String topic, String jsonPayload, @Nullable PublishCallback callback) {
            publishAttempts++;
            lastPayload = jsonPayload;
            publishedPayloads.add(jsonPayload);
            try {
                if (JsonParser.parseString(jsonPayload).getAsJsonObject().has("terminal")
                        && JsonParser.parseString(jsonPayload).getAsJsonObject()
                        .get("terminal").getAsBoolean()) {
                    terminalPublishCount++;
                }
            } catch (RuntimeException ignored) {
                // The production path validates the payload before publishing.
            }
            try {
                stateBeforePublish = persistence.lastRecord().state.name();
            } catch (RuntimeException ignored) {
                stateBeforePublish = "missing";
            }
            if (callback != null && publishSucceeds) {
                publishDeliveries++;
                callback.onComplete(true);
            } else if (callback != null) {
                callback.onComplete(false);
            }
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        void emitMessage(String topic, String payload, boolean retained) {
            assertNotNull(inboundMessageListener);
            inboundMessageListener.onMessage(topic, payload, retained);
        }
    }

    private static final class RecordingUiListener implements SingleActiveMqttBroker.Listener {
        int messageCount;
        boolean lastRetained;
        final List<String> commandIds = new ArrayList<>();

        @Override
        public void onMessage(String topic, String payload) {
            recordMessage(topic, payload, false);
        }

        @Override
        public void onMessage(String topic, String payload, boolean retained) {
            recordMessage(topic, payload, retained);
        }

        private void recordMessage(String topic, String payload, boolean retained) {
            messageCount++;
            lastRetained = retained;
            if (payload != null && payload.trim().startsWith("{")) {
                JsonObject json = JsonParser.parseString(payload).getAsJsonObject();
                if (json.has("command_id")) commandIds.add(
                        json.get("command_id").getAsString());
            }
        }

        @Override public void onConnected() {}
        @Override public void onDisconnected(String reason) {}
    }

    private static final class ManualScheduler implements CanonicalCommandRuntime.Scheduler {
        final List<Runnable> tasks = new ArrayList<>();

        @Override
        public CanonicalCommandRuntime.Cancellable schedule(Runnable task, long delayMs) {
            tasks.add(task);
            return () -> tasks.remove(task);
        }

        void runAll() {
            List<Runnable> pending = new ArrayList<>(tasks);
            tasks.clear();
            for (Runnable task : pending) task.run();
        }
    }

    private static final class ThrowingScheduler implements CanonicalCommandRuntime.Scheduler {
        @Override
        public CanonicalCommandRuntime.Cancellable schedule(Runnable task, long delayMs) {
            throw new IllegalStateException("scheduler_failure");
        }
    }

    private static final class RecordingDiagnostics implements CanonicalCommandDiagnostics {
        final List<Event> events = new ArrayList<>();

        @Override
        public void record(
                String phase,
                String topicClass,
                @Nullable String commandId,
                @Nullable String eventId,
                @Nullable String actionId,
                String state,
                String outcome,
                @Nullable String exceptionClass) {
            events.add(new Event(
                    phase, topicClass, commandId, eventId, actionId,
                    state, outcome, exceptionClass));
        }

        int count(String phase, String outcome) {
            int count = 0;
            for (Event event : events) {
                if (phase.equals(event.phase) && outcome.equals(event.outcome)) count++;
            }
            return count;
        }

        int count(String phase, String topicClass, String outcome) {
            int count = 0;
            for (Event event : events) {
                if (phase.equals(event.phase)
                        && topicClass.equals(event.topicClass)
                        && outcome.equals(event.outcome)) count++;
            }
            return count;
        }
    }

    private static final class Event {
        final String phase;
        final String topicClass;
        final String commandId;
        final String eventId;
        final String actionId;
        final String state;
        final String outcome;
        final String exceptionClass;

        Event(String phase, String topicClass, String commandId, String eventId,
              String actionId, String state, String outcome, String exceptionClass) {
            this.phase = phase;
            this.topicClass = topicClass;
            this.commandId = commandId;
            this.eventId = eventId;
            this.actionId = actionId;
            this.state = state;
            this.outcome = outcome;
            this.exceptionClass = exceptionClass;
        }
    }

    private static final class MemoryPersistence implements CommandLedger.Persistence {
        private CommandLedger.Snapshot snapshot = new CommandLedger.Snapshot();

        @Override
        public CommandLedger.Snapshot load() {
            return snapshot.copy();
        }

        @Override
        public boolean save(CommandLedger.Snapshot next) {
            snapshot = next.copy();
            return true;
        }

        CommandLedger.Record record(String commandId) {
            CommandLedger.Record record = snapshot.records.get(commandId);
            return record == null ? null : record.copy();
        }

        CommandLedger.Record lastRecord() {
            if (snapshot.records.isEmpty()) return null;
            String commandId = null;
            for (String value : snapshot.records.keySet()) commandId = value;
            return record(commandId);
        }
    }

    private static final class MemoryPreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();

        @Override public Map<String, ?> getAll() { return Collections.unmodifiableMap(values); }
        @Override public String getString(String key, String defValue) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : defValue;
        }
        @Override public Set<String> getStringSet(String key, Set<String> defValue) {
            Object value = values.get(key);
            return value instanceof Set ? new HashSet<>((Set<String>) value) : defValue;
        }
        @Override public int getInt(String key, int defValue) {
            Object value = values.get(key);
            return value instanceof Integer ? (Integer) value : defValue;
        }
        @Override public long getLong(String key, long defValue) {
            Object value = values.get(key);
            return value instanceof Long ? (Long) value : defValue;
        }
        @Override public float getFloat(String key, float defValue) {
            Object value = values.get(key);
            return value instanceof Float ? (Float) value : defValue;
        }
        @Override public boolean getBoolean(String key, boolean defValue) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : defValue;
        }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public Editor edit() { return new MemoryEditor(); }
        @Override public void registerOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {}
        @Override public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {}

        private final class MemoryEditor implements Editor {
            private final Map<String, Object> updates = new HashMap<>();
            private final Set<String> removals = new HashSet<>();
            private boolean clear;

            @Override public Editor putString(String key, @Nullable String value) {
                updates.put(key, value); return this;
            }
            @Override public Editor putStringSet(String key, @Nullable Set<String> value) {
                updates.put(key, value == null ? null : new HashSet<>(value)); return this;
            }
            @Override public Editor putInt(String key, int value) { updates.put(key, value); return this; }
            @Override public Editor putLong(String key, long value) { updates.put(key, value); return this; }
            @Override public Editor putFloat(String key, float value) { updates.put(key, value); return this; }
            @Override public Editor putBoolean(String key, boolean value) { updates.put(key, value); return this; }
            @Override public Editor remove(String key) { removals.add(key); return this; }
            @Override public Editor clear() { clear = true; return this; }
            @Override public boolean commit() { apply(); return true; }
            @Override public void apply() {
                if (clear) values.clear();
                for (String key : removals) values.remove(key);
                for (Map.Entry<String, Object> entry : updates.entrySet()) {
                    if (entry.getValue() == null) values.remove(entry.getKey());
                    else values.put(entry.getKey(), entry.getValue());
                }
            }
        }
    }
}
