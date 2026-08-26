package com.robotemi.agent.media.v11;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/** Immutable Android adapter model for a validated canonical Media v1.1 request. */
public final class MediaV11Command {
    public enum Action {
        PLAY_VIDEO("play_video", "serialized_execution"),
        PAUSE_VIDEO("pause_video", "active_playback_control"),
        RESUME_VIDEO("resume_video", "active_playback_control"),
        STOP_VIDEO("stop_video", "active_playback_control");

        private final String wireValue;
        private final String executionClass;

        Action(String wireValue, String executionClass) {
            this.wireValue = wireValue;
            this.executionClass = executionClass;
        }

        public String wireValue() {
            return wireValue;
        }

        public String executionClass() {
            return executionClass;
        }

        public boolean isControl() {
            return this != PLAY_VIDEO;
        }

        public static Action fromWireValue(String value) {
            for (Action action : values()) {
                if (action.wireValue.equals(value)) {
                    return action;
                }
            }
            return null;
        }
    }

    private final String commandId;
    private final String eventId;
    private final String robotId;
    private final String residentId;
    private final Action action;
    private final String targetPlaybackSessionId;
    private final String videoId;
    private final String source;
    private final String timestamp;

    MediaV11Command(
            String commandId,
            String eventId,
            String robotId,
            String residentId,
            Action action,
            String targetPlaybackSessionId,
            String videoId,
            String source,
            String timestamp
    ) {
        this.commandId = commandId;
        this.eventId = eventId;
        this.robotId = robotId;
        this.residentId = residentId;
        this.action = action;
        this.targetPlaybackSessionId = targetPlaybackSessionId;
        this.videoId = videoId;
        this.source = source;
        this.timestamp = timestamp;
    }

    public String getCommandId() { return commandId; }
    public String getRequestId() { return commandId; }
    public String getEventId() { return eventId; }
    public String getRobotId() { return robotId; }
    public String getResidentId() { return residentId; }
    public Action getAction() { return action; }
    public String getTargetPlaybackSessionId() { return targetPlaybackSessionId; }
    public String getVideoId() { return videoId; }
    public String getSource() { return source; }
    public String getTimestamp() { return timestamp; }

    public String toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("schema_version", "1.1");
        object.addProperty("message_type", "video.command");
        object.addProperty("command_id", commandId);
        object.addProperty("request_id", commandId);
        object.addProperty("event_id", eventId);
        object.addProperty("robot_id", robotId);
        object.addProperty("resident_id", residentId);
        object.addProperty("action", action.wireValue());
        object.addProperty("execution_class", action.executionClass());
        if (targetPlaybackSessionId == null) {
            object.add("target_playback_session_id", JsonNull.INSTANCE);
        } else {
            object.addProperty("target_playback_session_id", targetPlaybackSessionId);
        }
        object.addProperty("video_id", videoId);
        object.add("parameters", new JsonObject());
        object.addProperty("source", source);
        object.addProperty("timestamp", timestamp);
        return object.toString();
    }
}
