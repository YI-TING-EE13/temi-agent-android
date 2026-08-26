package com.robotemi.agent.media.v11;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class MediaV11CoordinatorTest {
    private InMemoryMediaV11Persistence store;
    private MediaV11Fixtures.FakeClock clock;
    private MediaV11Coordinator coordinator;

    @Before
    public void setUp() {
        store = new InMemoryMediaV11Persistence();
        clock = new MediaV11Fixtures.FakeClock();
        coordinator = new MediaV11Coordinator(store, clock, () -> "session-1");
    }

    @Test
    public void playEmitsAcceptedStartedAndCompleted() throws Exception {
        MediaV11Command play = MediaV11Fixtures.parsePlay("play");
        MediaV11Coordinator.Outcome accepted = coordinator.submit(play, true);
        assertEquals(MediaV11Coordinator.Effect.PLAY, accepted.getEffect());
        assertEquals("accepted", accepted.getResults().get(0).getStatus());
        assertEquals("session-1", accepted.getSessionId());

        assertEquals("started", coordinator.markStarted("session-1").get(0).getStatus());
        assertEquals("completed", coordinator.complete("session-1").get(0).getStatus());
        assertNull(coordinator.activeSessionId());
    }

    @Test
    public void controlsBypassActivePlayAndStopLinksCancellation() throws Exception {
        coordinator.submit(MediaV11Fixtures.parsePlay("play"), true);
        coordinator.markStarted("session-1");

        MediaV11Command pause = MediaV11Fixtures.parseControl(
                "pause", "pause_video", "session-1");
        assertEquals(MediaV11Coordinator.Effect.PAUSE,
                coordinator.submit(pause, true).getEffect());
        assertEquals("paused", resultState(coordinator.controlSucceeded(pause).get(0)));

        MediaV11Command resume = MediaV11Fixtures.parseControl(
                "resume", "resume_video", "session-1");
        assertEquals(MediaV11Coordinator.Effect.RESUME,
                coordinator.submit(resume, true).getEffect());
        coordinator.controlSucceeded(resume);

        MediaV11Command stop = MediaV11Fixtures.parseControl(
                "stop", "stop_video", "session-1");
        assertEquals(MediaV11Coordinator.Effect.STOP,
                coordinator.submit(stop, true).getEffect());
        List<MediaV11Result> results = coordinator.controlSucceeded(stop);
        assertEquals(2, results.size());
        assertEquals("succeeded", results.get(0).getStatus());
        JsonObject cancellation = JsonParser.parseString(results.get(1).toJson())
                .getAsJsonObject();
        assertEquals("cancelled", cancellation.get("status").getAsString());
        assertEquals("stop", cancellation.get("cancelled_by_command_id").getAsString());
        assertEquals("remote_stop", cancellation.get("cancel_reason").getAsString());
    }

    @Test
    public void concurrentPlayRejectsWithoutReplacingSession() throws Exception {
        coordinator.submit(MediaV11Fixtures.parsePlay("play-1"), true);
        MediaV11Coordinator.Outcome second =
                coordinator.submit(MediaV11Fixtures.parsePlay("play-2"), true);

        assertEquals(MediaV11Coordinator.Effect.NONE, second.getEffect());
        JsonObject result = json(second.getResults().get(0));
        assertEquals("MEDIA_SESSION_ACTIVE", result.get("error_code").getAsString());
        assertEquals("session-1", result.get("active_playback_session_id").getAsString());
        assertEquals("session-1", coordinator.activeSessionId());
    }

    @Test
    public void duplicateActiveReturnsReferenceAndTerminalReturnsCachedReplay() throws Exception {
        MediaV11Command play = MediaV11Fixtures.parsePlay("play");
        coordinator.submit(play, true);
        int afterAccepted = coordinator.pendingOutbox().size();
        assertEquals("active_reference",
                coordinator.submit(play, true).getResults().get(0).getDelivery());
        assertEquals(afterAccepted + 1, coordinator.pendingOutbox().size());
        coordinator.markStarted("session-1");
        coordinator.complete("session-1");
        int beforeReplay = coordinator.pendingOutbox().size();
        assertEquals("cached_replay",
                coordinator.submit(play, true).getResults().get(0).getDelivery());
        assertEquals(beforeReplay + 1, coordinator.pendingOutbox().size());
    }

    @Test
    public void sameCommandIdWithDifferentPayloadReturnsConflictWithoutReplacingOriginal()
            throws Exception {
        MediaV11Command original = MediaV11Fixtures.parsePlay("play");
        coordinator.submit(original, true);
        String changedPayload = MediaV11Fixtures.request(
                "play", "play_video", "serialized_execution", null)
                .replace("elderly_hand_exercise", "elderly_leg_exercise");
        MediaV11Command changed = MediaV11Parser.parse(changedPayload, "temi-01");

        MediaV11Coordinator.Outcome conflict = coordinator.submit(changed, true);
        assertEquals(MediaV11Coordinator.Effect.NONE, conflict.getEffect());
        assertEquals("MEDIA_CONTROL_CONFLICT", error(conflict));
        assertEquals("session-1", coordinator.activeSessionId());
        assertEquals("active_reference",
                coordinator.submit(original, true).getResults().get(0).getDelivery());
    }

    @Test
    public void wrongSessionAndWrongStateAreRejected() throws Exception {
        coordinator.submit(MediaV11Fixtures.parsePlay("play"), true);
        MediaV11Command wrong = MediaV11Fixtures.parseControl(
                "pause-wrong", "pause_video", "wrong");
        assertEquals("MEDIA_SESSION_NOT_FOUND",
                error(coordinator.submit(wrong, true)));

        MediaV11Command earlyPause = MediaV11Fixtures.parseControl(
                "pause-early", "pause_video", "session-1");
        assertEquals("MEDIA_SESSION_NOT_PLAYING",
                error(coordinator.submit(earlyPause, true)));

        MediaV11Command earlyStop = MediaV11Fixtures.parseControl(
                "stop-early", "stop_video", "session-1");
        assertEquals("MEDIA_CONTROL_CONFLICT",
                error(coordinator.submit(earlyStop, true)));
    }

    @Test
    public void localStopAndRestartReconciliationOnlyCancelOriginatingPlay() throws Exception {
        coordinator.submit(MediaV11Fixtures.parsePlay("play"), true);
        coordinator.markStarted("session-1");
        JsonObject local = json(coordinator.localUserStop().get(0));
        assertEquals("local_user_stop", local.get("cancel_reason").getAsString());
        assertTrue(local.get("cancelled_by_command_id").isJsonNull());

        coordinator = new MediaV11Coordinator(store, clock, () -> "session-2");
        coordinator.submit(MediaV11Fixtures.parsePlay("play-2"), true);
        JsonObject restart = json(coordinator.reconcileAfterProcessRestart().get(0));
        assertEquals("app_process_restart", restart.get("cancel_reason").getAsString());
        assertEquals("restart_reconciliation",
                restart.get("result_delivery").getAsString());
    }

    @Test
    public void restartTerminalDuplicateBypassesExecutionAndRemainsReplayable()
            throws Exception {
        String endpoint = "endpoint-a";
        MediaV11Command play = MediaV11Fixtures.parsePlay("restart-play");
        coordinator.submit(play, true, endpoint);
        coordinator.markStarted("session-1");
        coordinator.reconcileAfterProcessRestart();

        MediaV11Persistence.Snapshot reconciled = store.load();
        MediaV11Persistence.CommandRecord terminal =
                reconciled.commands.get(play.getCommandId());
        assertTrue(terminal.terminal);
        assertEquals("session-1", terminal.sessionId);
        assertEquals(endpoint, terminal.endpointFingerprint);
        assertEquals("restart_reconciliation",
                JsonParser.parseString(terminal.latestResultJson).getAsJsonObject()
                        .get("result_delivery").getAsString());
        assertEquals("app_process_restart",
                JsonParser.parseString(terminal.latestResultJson).getAsJsonObject()
                        .get("cancel_reason").getAsString());
        long terminalUpdatedAt = terminal.updatedAtMs;

        for (MediaV11Persistence.OutboxRecord record :
                coordinator.pendingOutbox()) {
            assertTrue(coordinator.acknowledgeOutbox(record.id));
        }
        assertTrue(coordinator.pendingOutbox().isEmpty());

        AtomicInteger newSessionCalls = new AtomicInteger();
        MediaV11Coordinator recreated = new MediaV11Coordinator(
                store, clock, () -> {
                    newSessionCalls.incrementAndGet();
                    return "must-not-be-created";
                });
        MediaV11Coordinator.Outcome replay =
                recreated.submitDuplicateIfKnown(play, endpoint);

        assertNotNull(replay);
        assertEquals(MediaV11Coordinator.Effect.NONE, replay.getEffect());
        assertEquals("session-1", replay.getSessionId());
        assertEquals(0, newSessionCalls.get());
        assertNull(recreated.activeSessionId());
        JsonObject replayJson = json(replay.getResults().get(0));
        assertEquals("cached_replay", replayJson.get("result_delivery").getAsString());
        assertTrue(replayJson.get("terminal").getAsBoolean());
        assertEquals("cancelled", replayJson.get("status").getAsString());
        assertEquals("session-1", replayJson.get("playback_session_id").getAsString());
        assertEquals("app_process_restart", replayJson.get("cancel_reason").getAsString());
        assertEquals("app_process", replayJson.get("actor").getAsString());
        assertFalse(replayJson.has("side_effect_applied"));

        MediaV11Persistence.Snapshot afterReplay = store.load();
        MediaV11Persistence.CommandRecord cached =
                afterReplay.commands.get(play.getCommandId());
        assertEquals(terminal.latestResultJson, cached.latestResultJson);
        assertEquals(terminalUpdatedAt, cached.updatedAtMs);
        assertEquals(1, afterReplay.outbox.size());

        // A retry while the same replay is pending is coalesced by payload identity.
        assertNotNull(recreated.submitDuplicateIfKnown(play, endpoint));
        assertEquals(1, recreated.pendingOutbox().size());

        String replayDeliveryId = recreated.pendingOutbox().get(0).id;
        assertTrue(recreated.acknowledgeOutbox(replayDeliveryId));
        assertTrue(recreated.pendingOutbox().isEmpty());

        // Publishing a replay removes only its delivery entry, not the terminal cache.
        assertNotNull(recreated.submitDuplicateIfKnown(play, endpoint));
        assertEquals(1, recreated.pendingOutbox().size());
        assertEquals(0, newSessionCalls.get());
    }

    @Test
    public void unknownCommandStillRequiresSerializedFirstDelivery() throws Exception {
        assertNull(coordinator.submitDuplicateIfKnown(
                MediaV11Fixtures.parsePlay("first-delivery")));
        assertTrue(coordinator.pendingOutbox().isEmpty());
    }

    @Test
    public void restartReplayDoesNotReplacePendingReconciliationAndSurvivesRecreation()
            throws Exception {
        String endpoint = "endpoint-a";
        MediaV11Command play = MediaV11Fixtures.parsePlay("restart-pending");
        coordinator.submit(play, true, endpoint);
        coordinator.markStarted("session-1");
        coordinator.reconcileAfterProcessRestart();
        int beforeReplay = coordinator.pendingOutbox().size();

        assertNotNull(coordinator.submitDuplicateIfKnown(play, endpoint));
        assertEquals(beforeReplay + 1, coordinator.pendingOutbox().size());

        boolean hasReconciliation = false;
        boolean hasCachedReplay = false;
        MediaV11Coordinator recreated =
                new MediaV11Coordinator(store, clock, () -> "must-not-be-created");
        for (MediaV11Persistence.OutboxRecord record : recreated.pendingOutbox()) {
            String delivery = JsonParser.parseString(record.payload).getAsJsonObject()
                    .get("result_delivery").getAsString();
            hasReconciliation |= "restart_reconciliation".equals(delivery);
            hasCachedReplay |= "cached_replay".equals(delivery);
        }
        assertTrue(hasReconciliation);
        assertTrue(hasCachedReplay);
        assertNull(recreated.activeSessionId());
    }

    @Test
    public void normalTerminalKindsPreserveTheirContentInCachedReplay() throws Exception {
        MediaV11Command completedPlay = MediaV11Fixtures.parsePlay("completed");
        coordinator.submit(completedPlay, true);
        coordinator.markStarted("session-1");
        coordinator.complete("session-1");
        JsonObject completed = json(coordinator.submitDuplicateIfKnown(completedPlay)
                .getResults().get(0));
        assertEquals("completed", completed.get("status").getAsString());
        assertEquals("completed", completed.get("playback_state").getAsString());

        store = new InMemoryMediaV11Persistence();
        coordinator = new MediaV11Coordinator(store, clock, () -> "session-failed");
        MediaV11Command failedPlay = MediaV11Fixtures.parsePlay("failed");
        coordinator.submit(failedPlay, true);
        coordinator.markStarted("session-failed");
        coordinator.fail("session-failed", "decoder_failed");
        JsonObject failed = json(coordinator.submitDuplicateIfKnown(failedPlay)
                .getResults().get(0));
        assertEquals("failed", failed.get("status").getAsString());
        assertEquals("INTERNAL_ERROR", failed.get("error_code").getAsString());
        assertEquals("decoder_failed", failed.get("error_message").getAsString());

        store = new InMemoryMediaV11Persistence();
        coordinator = new MediaV11Coordinator(store, clock, () -> "session-stopped");
        MediaV11Command stoppedPlay = MediaV11Fixtures.parsePlay("stopped");
        coordinator.submit(stoppedPlay, true);
        coordinator.markStarted("session-stopped");
        MediaV11Command stop = MediaV11Fixtures.parseControl(
                "stop", "stop_video", "session-stopped");
        coordinator.submit(stop, true);
        coordinator.controlSucceeded(stop);
        JsonObject cancelled = json(coordinator.submitDuplicateIfKnown(stoppedPlay)
                .getResults().get(0));
        assertEquals("cancelled", cancelled.get("status").getAsString());
        assertEquals("remote_stop", cancelled.get("cancel_reason").getAsString());
        assertEquals("stop", cancelled.get("cancelled_by_command_id").getAsString());
    }

    @Test
    public void fastDuplicatePathRejectsChangedPayloadWithoutNewSession() throws Exception {
        MediaV11Command original = MediaV11Fixtures.parsePlay("conflict");
        coordinator.submit(original, true, "endpoint-a");
        coordinator.reconcileAfterProcessRestart();
        String changedPayload = MediaV11Fixtures.request(
                "conflict", "play_video", "serialized_execution", null)
                .replace("elderly_hand_exercise", "elderly_leg_exercise");
        MediaV11Command changed = MediaV11Parser.parse(changedPayload, "temi-01");

        MediaV11Coordinator.Outcome conflict =
                coordinator.submitDuplicateIfKnown(changed, "endpoint-a");
        assertNotNull(conflict);
        assertEquals(MediaV11Coordinator.Effect.NONE, conflict.getEffect());
        assertEquals("MEDIA_CONTROL_CONFLICT", error(conflict));
        assertNull(coordinator.activeSessionId());
        assertEquals("restart_reconciliation",
                JsonParser.parseString(store.load().commands.get("conflict").latestResultJson)
                        .getAsJsonObject().get("result_delivery").getAsString());
    }

    @Test
    public void terminalReplayFailsClosedAcrossEndpointOrLegacyMissingFingerprint()
            throws Exception {
        MediaV11Command play = MediaV11Fixtures.parsePlay("endpoint-owned");
        coordinator.submit(play, true, "endpoint-a");
        coordinator.reconcileAfterProcessRestart();
        int pendingBeforeMismatch = coordinator.pendingOutbox().size();

        MediaV11Coordinator.Outcome mismatch =
                coordinator.submitDuplicateIfKnown(play, "endpoint-b");
        assertNotNull(mismatch);
        assertFalse(mismatch.isDurable());
        assertEquals("MEDIA_CONTROL_CONFLICT", error(mismatch));
        assertEquals(pendingBeforeMismatch, coordinator.pendingOutbox().size());
        assertEquals("restart_reconciliation",
                JsonParser.parseString(store.load().commands.get("endpoint-owned")
                        .latestResultJson).getAsJsonObject()
                        .get("result_delivery").getAsString());

        MediaV11Persistence.Snapshot legacy = store.load();
        legacy.commands.get("endpoint-owned").endpointFingerprint = null;
        store.save(legacy);
        MediaV11Coordinator.Outcome unknownOwner =
                coordinator.submitDuplicateIfKnown(play, "endpoint-a");
        assertNotNull(unknownOwner);
        assertFalse(unknownOwner.isDurable());
        assertEquals("MEDIA_CONTROL_CONFLICT", error(unknownOwner));
        assertEquals(pendingBeforeMismatch, coordinator.pendingOutbox().size());
        assertNull(coordinator.activeSessionId());
    }

    @Test
    public void featureDisabledAndStoreFailuresHaveNoEffect() throws Exception {
        MediaV11Command play = MediaV11Fixtures.parsePlay("play");
        MediaV11Coordinator.Outcome disabled = coordinator.submit(play, false);
        assertEquals(MediaV11Coordinator.Effect.NONE, disabled.getEffect());
        assertEquals("UNSUPPORTED_MEDIA_ACTION", error(disabled));

        store.setFailReads(true);
        MediaV11Coordinator.Outcome failed = coordinator.submit(
                MediaV11Fixtures.parsePlay("other"), true);
        assertEquals(MediaV11Coordinator.Effect.NONE, failed.getEffect());
        assertFalse(failed.isDurable());
        assertEquals("INTERNAL_ERROR", error(failed));

        InMemoryMediaV11Persistence writeFailureStore =
                new InMemoryMediaV11Persistence();
        writeFailureStore.setFailWrites(true);
        MediaV11Coordinator writeFailureCoordinator =
                new MediaV11Coordinator(writeFailureStore, clock, () -> "never-created");
        MediaV11Coordinator.Outcome writeFailed = writeFailureCoordinator.submit(
                MediaV11Fixtures.parsePlay("write-failure"), true);
        assertEquals(MediaV11Coordinator.Effect.NONE, writeFailed.getEffect());
        assertFalse(writeFailed.isDurable());
    }

    @Test
    public void outboxSurvivesCoordinatorRecreationAndAcknowledgesByIdentity() throws Exception {
        coordinator.submit(MediaV11Fixtures.parsePlay("play"), true);
        List<MediaV11Persistence.OutboxRecord> pending = coordinator.pendingOutbox();
        assertEquals(1, pending.size());

        MediaV11Coordinator recreated = new MediaV11Coordinator(store, clock, () -> "unused");
        assertEquals(1, recreated.pendingOutbox().size());
        assertTrue(recreated.acknowledgeOutbox(pending.get(0).id));
        assertTrue(recreated.pendingOutbox().isEmpty());
    }

    @Test
    public void explicitOutboxDiscardRetainsCommandHistory() throws Exception {
        MediaV11Command play = MediaV11Fixtures.parsePlay("play");
        coordinator.submit(play, true);
        assertFalse(coordinator.pendingOutbox().isEmpty());

        assertTrue(coordinator.discardPendingOutbox());
        assertTrue(coordinator.pendingOutbox().isEmpty());
        assertEquals("active_reference",
                coordinator.submit(play, true).getResults().get(0).getDelivery());
    }

    @Test
    public void activityRecreationDoesNotReconcileOrReplayActiveSession() throws Exception {
        MediaV11Command play = MediaV11Fixtures.parsePlay("play");
        coordinator.submit(play, true);
        coordinator.markStarted("session-1");

        MediaV11Coordinator recreated = new MediaV11Coordinator(store, clock, () -> "new");
        assertEquals("session-1", recreated.activeSessionId());
        MediaV11Coordinator.Outcome duplicate = recreated.submit(play, true);
        assertEquals(MediaV11Coordinator.Effect.NONE, duplicate.getEffect());
        assertEquals("active_reference", duplicate.getResults().get(0).getDelivery());
    }

    @Test
    public void controlAgainstTerminalSessionDoesNotReopenPlayback() throws Exception {
        coordinator.submit(MediaV11Fixtures.parsePlay("play"), true);
        coordinator.markStarted("session-1");
        coordinator.complete("session-1");
        MediaV11Coordinator.Outcome pause = coordinator.submit(
                MediaV11Fixtures.parseControl("pause", "pause_video", "session-1"), true);
        assertEquals(MediaV11Coordinator.Effect.NONE, pause.getEffect());
        assertEquals("MEDIA_SESSION_NOT_FOUND", error(pause));
    }

    @Test
    public void retentionIsBoundedWithoutEvictingActiveRecord() throws Exception {
        for (int i = 0; i < MediaV11Coordinator.MAX_COMMAND_RECORDS + 5; i++) {
            MediaV11Command command = MediaV11Fixtures.parsePlay("disabled-" + i);
            coordinator.submit(command, false);
        }
        MediaV11Persistence.Snapshot snapshot = store.load();
        assertEquals(MediaV11Coordinator.MAX_COMMAND_RECORDS, snapshot.commands.size());
    }

    @Test
    public void resultContainsEveryCanonicalRequiredField() throws Exception {
        MediaV11Result result = coordinator.submit(
                MediaV11Fixtures.parsePlay("play"), true).getResults().get(0);
        JsonObject object = json(result);
        String[] required = {
                "schema_version", "message_type", "command_id", "request_id", "event_id",
                "robot_id", "command_action", "video_id", "status", "terminal",
                "playback_session_id", "target_playback_session_id",
                "active_playback_session_id", "playback_state",
                "cancelled_by_command_id", "cancel_reason", "actor", "result_delivery",
                "error_code", "error_message", "timestamp"
        };
        for (String field : required) {
            assertTrue(field, object.has(field));
        }
        assertEquals(21, object.size());
    }

    private static String error(MediaV11Coordinator.Outcome outcome) {
        return json(outcome.getResults().get(0)).get("error_code").getAsString();
    }

    private static JsonObject json(MediaV11Result result) {
        return JsonParser.parseString(result.toJson()).getAsJsonObject();
    }

    private static String resultState(MediaV11Result result) {
        return json(result).get("playback_state").getAsString();
    }
}
