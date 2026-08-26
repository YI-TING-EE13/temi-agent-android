package com.robotemi.agent.media.v11;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Canonical Media v1.1 result with schema-valid factory methods. */
public final class MediaV11Result {
    private final JsonObject value;

    private MediaV11Result(JsonObject value) {
        this.value = value;
    }

    public static MediaV11Result accepted(
            MediaV11Command command, String sessionId, String timestamp) {
        return create(command, "accepted", false, sessionId, null, null, null, null,
                "remote_command", "original", null, null, timestamp);
    }

    public static MediaV11Result started(
            MediaV11Command command, String sessionId, String timestamp) {
        return create(command, "started", false, sessionId, null, "playing", null, null,
                "remote_command", "original", null, null, timestamp);
    }

    public static MediaV11Result completed(
            MediaV11Command command, String sessionId, String timestamp) {
        return create(command, "completed", true, sessionId, null, "completed", null, null,
                "remote_command", "original", null, null, timestamp);
    }

    public static MediaV11Result failed(
            MediaV11Command command, String sessionId, String errorCode, String errorMessage,
            String timestamp) {
        return create(command, "failed", true, sessionId, command.getTargetPlaybackSessionId(),
                "failed", null, null, "remote_command", "original", errorCode, errorMessage,
                timestamp);
    }

    public static MediaV11Result controlSucceeded(
            MediaV11Command command, String sessionId, String playbackState, String timestamp) {
        return create(command, "succeeded", true, sessionId,
                command.getTargetPlaybackSessionId(), playbackState, null, null,
                "remote_command", "original", null, null, timestamp);
    }

    public static MediaV11Result cancelled(
            MediaV11Command playCommand,
            String sessionId,
            String cancelledByCommandId,
            String reason,
            String actor,
            String delivery,
            String timestamp
    ) {
        return create(playCommand, "cancelled", true, sessionId, null, "cancelled",
                cancelledByCommandId, reason, actor, delivery, null, null, timestamp);
    }

    public static MediaV11Result rejected(
            MediaV11Command command,
            String errorCode,
            String errorMessage,
            String activeSessionId,
            String timestamp
    ) {
        return create(command, "rejected", true, null, command.getTargetPlaybackSessionId(),
                null, activeSessionId, null, "remote_command", "original", errorCode,
                errorMessage, timestamp);
    }

    public MediaV11Result withDelivery(String delivery, String timestamp) {
        JsonObject copy = JsonParser.parseString(value.toString()).getAsJsonObject();
        copy.addProperty("result_delivery", delivery);
        copy.addProperty("timestamp", timestamp);
        return new MediaV11Result(copy);
    }

    public String toJson() {
        return value.toString();
    }

    public String getCommandId() {
        return value.get("command_id").getAsString();
    }

    public String getEventId() {
        return value.get("event_id").getAsString();
    }

    public String getAction() {
        return value.get("command_action").getAsString();
    }

    public String getStatus() {
        return value.get("status").getAsString();
    }

    public boolean isTerminal() {
        return value.get("terminal").getAsBoolean();
    }

    public String getDelivery() {
        return value.get("result_delivery").getAsString();
    }

    public static MediaV11Result fromJson(String payload) {
        return new MediaV11Result(JsonParser.parseString(payload).getAsJsonObject());
    }

    private static MediaV11Result create(
            MediaV11Command command,
            String status,
            boolean terminal,
            String sessionId,
            String targetSessionId,
            String playbackState,
            String linkageOrActiveSession,
            String cancelReason,
            String actor,
            String delivery,
            String errorCode,
            String errorMessage,
            String timestamp
    ) {
        JsonObject result = new JsonObject();
        result.addProperty("schema_version", "1.1");
        result.addProperty("message_type", "video.command_result");
        result.addProperty("command_id", command.getCommandId());
        result.addProperty("request_id", command.getRequestId());
        result.addProperty("event_id", command.getEventId());
        result.addProperty("robot_id", command.getRobotId());
        result.addProperty("command_action", command.getAction().wireValue());
        result.addProperty("video_id", command.getVideoId());
        result.addProperty("status", status);
        result.addProperty("terminal", terminal);
        addNullable(result, "playback_session_id", sessionId);
        addNullable(result, "target_playback_session_id", targetSessionId);
        if ("rejected".equals(status) && "MEDIA_SESSION_ACTIVE".equals(errorCode)) {
            addNullable(result, "active_playback_session_id", linkageOrActiveSession);
            result.add("cancelled_by_command_id", JsonNull.INSTANCE);
        } else {
            result.add("active_playback_session_id", JsonNull.INSTANCE);
            addNullable(result, "cancelled_by_command_id", linkageOrActiveSession);
        }
        addNullable(result, "playback_state", playbackState);
        addNullable(result, "cancel_reason", cancelReason);
        result.addProperty("actor", actor);
        result.addProperty("result_delivery", delivery);
        addNullable(result, "error_code", errorCode);
        addNullable(result, "error_message", errorMessage);
        result.addProperty("timestamp", timestamp);
        return new MediaV11Result(result);
    }

    private static void addNullable(JsonObject object, String name, String value) {
        if (value == null) {
            object.add(name, JsonNull.INSTANCE);
        } else {
            object.addProperty(name, value);
        }
    }
}
