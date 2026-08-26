package com.robotemi.agent.media.v11;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/** Guards the canonical result shape and conditional semantics without a new dependency. */
public class MediaV11ResultConformanceTest {
    private static final Set<String> REQUIRED = new HashSet<>(Arrays.asList(
            "schema_version", "message_type", "command_id", "request_id", "event_id",
            "robot_id", "command_action", "video_id", "status", "terminal",
            "playback_session_id", "target_playback_session_id",
            "active_playback_session_id", "playback_state",
            "cancelled_by_command_id", "cancel_reason", "actor", "result_delivery",
            "error_code", "error_message", "timestamp"));

    @Test
    public void playLifecycleConforms() throws Exception {
        MediaV11Command play = MediaV11Fixtures.parsePlay("play");
        assertConforms(MediaV11Result.accepted(play, "session", "time"));
        assertConforms(MediaV11Result.started(play, "session", "time"));
        assertConforms(MediaV11Result.completed(play, "session", "time"));
        assertConforms(MediaV11Result.failed(
                play, "session", "INTERNAL_ERROR", "failure", "time"));
    }

    @Test
    public void controlAndLinkedCancellationConform() throws Exception {
        MediaV11Command play = MediaV11Fixtures.parsePlay("play");
        MediaV11Command pause =
                MediaV11Fixtures.parseControl("pause", "pause_video", "session");
        MediaV11Command resume =
                MediaV11Fixtures.parseControl("resume", "resume_video", "session");
        MediaV11Command stop =
                MediaV11Fixtures.parseControl("stop", "stop_video", "session");

        assertConforms(MediaV11Result.controlSucceeded(pause, "session", "paused", "time"));
        assertConforms(MediaV11Result.controlSucceeded(resume, "session", "playing", "time"));
        assertConforms(MediaV11Result.controlSucceeded(stop, "session", "cancelled", "time"));
        assertConforms(MediaV11Result.cancelled(
                play, "session", "stop", "remote_stop", "remote_command",
                "original", "time"));
    }

    @Test
    public void localAndRestartCancellationsConform() throws Exception {
        MediaV11Command play = MediaV11Fixtures.parsePlay("play");
        assertConforms(MediaV11Result.cancelled(
                play, "session", null, "local_user_stop", "local_user",
                "original", "time"));
        assertConforms(MediaV11Result.cancelled(
                play, "session", null, "app_process_restart", "app_process",
                "restart_reconciliation", "time"));
        assertConforms(MediaV11Result.cancelled(
                play, "session", null, "app_process_restart", "app_process",
                "restart_reconciliation", "time")
                .withDelivery("cached_replay", "later"));
    }

    @Test
    public void rejectionAndReplayConform() throws Exception {
        MediaV11Command play = MediaV11Fixtures.parsePlay("play");
        MediaV11Result rejection = MediaV11Result.rejected(
                play, "MEDIA_SESSION_ACTIVE", "active", "session", "time");
        assertConforms(rejection);
        assertConforms(MediaV11Result.completed(play, "session", "time")
                .withDelivery("cached_replay", "later"));
    }

    public static void assertConforms(MediaV11Result result) {
        JsonObject object = JsonParser.parseString(result.toJson()).getAsJsonObject();
        assertEquals(REQUIRED, object.keySet());
        assertEquals("1.1", object.get("schema_version").getAsString());
        assertEquals("video.command_result", object.get("message_type").getAsString());
        assertEquals(object.get("command_id"), object.get("request_id"));

        String status = object.get("status").getAsString();
        boolean terminal = object.get("terminal").getAsBoolean();
        assertEquals(!("accepted".equals(status) || "started".equals(status)), terminal);
        if ("rejected".equals(status) || "failed".equals(status)) {
            assertFalse(object.get("error_code").isJsonNull());
            assertFalse(object.get("error_message").isJsonNull());
        } else {
            assertTrue(object.get("error_code").isJsonNull());
            assertTrue(object.get("error_message").isJsonNull());
        }

        boolean play = "play_video".equals(object.get("command_action").getAsString());
        if (play) {
            assertTrue(object.get("target_playback_session_id").isJsonNull());
        } else {
            assertFalse(object.get("target_playback_session_id").isJsonNull());
            assertTrue(terminal);
        }

        if ("cancelled".equals(status)) {
            assertTrue(play);
            assertEquals("cancelled", object.get("playback_state").getAsString());
            assertFalse(object.get("cancel_reason").isJsonNull());
        } else {
            assertTrue(object.get("cancelled_by_command_id").isJsonNull());
            assertTrue(object.get("cancel_reason").isJsonNull());
        }

        if ("MEDIA_SESSION_ACTIVE".equals(nullableString(object, "error_code"))) {
            assertFalse(object.get("active_playback_session_id").isJsonNull());
            assertTrue(object.get("playback_session_id").isJsonNull());
        } else {
            assertTrue(object.get("active_playback_session_id").isJsonNull());
        }

        String delivery = object.get("result_delivery").getAsString();
        if ("cached_replay".equals(delivery)) {
            assertTrue(terminal);
        } else if ("restart_reconciliation".equals(delivery)) {
            assertTrue(terminal);
            assertEquals("app_process", object.get("actor").getAsString());
        }
    }

    private static String nullableString(JsonObject object, String name) {
        return object.get(name).isJsonNull() ? null : object.get(name).getAsString();
    }
}
