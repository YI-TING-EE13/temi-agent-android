package com.robotemi.agent.media.v11;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import static org.junit.Assert.*;

public class MediaV11ParserTest {
    @Test
    public void parsesStrictPlayAndControlRequests() throws Exception {
        MediaV11Command play = MediaV11Fixtures.parsePlay("play-1");
        MediaV11Command pause = MediaV11Fixtures.parseControl("pause-1", "pause_video", "s-1");

        assertEquals(MediaV11Command.Action.PLAY_VIDEO, play.getAction());
        assertNull(play.getTargetPlaybackSessionId());
        assertEquals(MediaV11Command.Action.PAUSE_VIDEO, pause.getAction());
        assertEquals("s-1", pause.getTargetPlaybackSessionId());
    }

    @Test
    public void declaresV11WithoutDowngradingToLegacy() {
        assertTrue(MediaV11Parser.declaresMediaV11(
                MediaV11Fixtures.request("p", "play_video", "serialized_execution", null)));
        assertFalse(MediaV11Parser.declaresMediaV11(
                "{\"schema_version\":\"1.0\",\"actions\":[]}"));
    }

    @Test
    public void rejectsUnknownVersionActionAndMissingField() {
        assertReason(mutate("schema_version", "1.2"), "unsupported_schema_version");
        assertReason(mutate("action", "rewind_video"), "unsupported_media_action");

        JsonObject missing = base();
        missing.remove("resident_id");
        assertReason(missing.toString(), "request_fields_do_not_match_schema");
    }

    @Test
    public void rejectsCommandRequestMismatchAndExecutionMismatch() {
        JsonObject mismatch = base();
        mismatch.addProperty("request_id", "different");
        assertReason(mismatch.toString(), "command_id_request_id_mismatch");
        assertNotNull(MediaV11Parser.correlateForRejection(
                mismatch.toString(), "temi-01"));
        assertReason(mutate("execution_class", "active_playback_control"),
                "execution_class_action_mismatch");
    }

    @Test
    public void rejectsUnknownPropertiesAndNonEmptyParameters() {
        JsonObject extra = base();
        extra.addProperty("path", "/not/allowed");
        assertReason(extra.toString(), "request_fields_do_not_match_schema");

        JsonObject parameters = base();
        parameters.getAsJsonObject("parameters").addProperty("start_position_ms", 0);
        assertReason(parameters.toString(), "unsupported_media_parameters");

        assertReason(mutate("video_id", "arbitrary_file"), "video_id_not_allowed");
    }

    @Test
    public void rejectsWrongRobotBeforeSideEffects() {
        assertReasonForRobot(base().toString(), "other-robot", "robot_id_mismatch");
    }

    private static JsonObject base() {
        return JsonParser.parseString(MediaV11Fixtures.request(
                "play-1", "play_video", "serialized_execution", null)).getAsJsonObject();
    }

    private static String mutate(String name, String value) {
        JsonObject object = base();
        object.addProperty(name, value);
        return object.toString();
    }

    private static void assertReason(String payload, String reason) {
        assertReasonForRobot(payload, "temi-01", reason);
    }

    private static void assertReasonForRobot(String payload, String robot, String reason) {
        try {
            MediaV11Parser.parse(payload, robot);
            fail("expected validation failure");
        } catch (MediaV11Parser.ValidationException expected) {
            assertEquals(reason, expected.getReason());
        }
    }
}
