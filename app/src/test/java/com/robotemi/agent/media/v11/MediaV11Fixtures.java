package com.robotemi.agent.media.v11;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

final class MediaV11Fixtures {
    private MediaV11Fixtures() {}

    static String request(String commandId, String action, String executionClass, String target) {
        JsonObject object = new JsonObject();
        object.addProperty("schema_version", "1.1");
        object.addProperty("message_type", "video.command");
        object.addProperty("command_id", commandId);
        object.addProperty("request_id", commandId);
        object.addProperty("event_id", "event-" + commandId);
        object.addProperty("robot_id", "temi-01");
        object.addProperty("resident_id", "resident-1");
        object.addProperty("action", action);
        object.addProperty("execution_class", executionClass);
        if (target == null) {
            object.add("target_playback_session_id", JsonNull.INSTANCE);
        } else {
            object.addProperty("target_playback_session_id", target);
        }
        object.addProperty("video_id", "elderly_hand_exercise");
        object.add("parameters", new JsonObject());
        object.addProperty("source", "hermes_temi_bridge");
        object.addProperty("timestamp", "2026-07-26T10:00:00Z");
        return object.toString();
    }

    static MediaV11Command parsePlay(String id) throws Exception {
        return MediaV11Parser.parse(
                request(id, "play_video", "serialized_execution", null), "temi-01");
    }

    static MediaV11Command parseControl(String id, String action, String session)
            throws Exception {
        return MediaV11Parser.parse(
                request(id, action, "active_playback_control", session), "temi-01");
    }

    static final class FakeClock implements MediaV11Coordinator.Clock {
        long now = 1_000L;

        @Override
        public long nowMs() {
            return now;
        }

        @Override
        public String nowTimestamp() {
            return "2026-07-26T10:00:" + now + "Z";
        }
    }
}
