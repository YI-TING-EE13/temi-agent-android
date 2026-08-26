package com.robotemi.agent.media.v11;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Strict structural and semantic parser for canonical video.command v1.1. */
public final class MediaV11Parser {
    private static final Set<String> REQUIRED_FIELDS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "schema_version", "message_type", "command_id", "request_id", "event_id",
                    "robot_id", "resident_id", "action", "execution_class",
                    "target_playback_session_id", "video_id", "parameters", "source",
                    "timestamp")));
    private static final Set<String> SOURCES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "hermes_temi_bridge", "temi_app_manual", "remote_operator")));
    private static final Set<String> VIDEO_IDS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "elderly_hand_exercise", "elderly_leg_exercise")));

    private MediaV11Parser() {}

    public static boolean declaresMediaV11(String payload) {
        try {
            JsonElement root = JsonParser.parseString(payload);
            if (!root.isJsonObject()) {
                return false;
            }
            JsonObject object = root.getAsJsonObject();
            return "1.1".equals(string(object, "schema_version"))
                    || "video.command".equals(string(object, "message_type"));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static MediaV11Command parse(String payload, String expectedRobotId)
            throws ValidationException {
        JsonObject object;
        try {
            JsonElement root = JsonParser.parseString(payload);
            if (!root.isJsonObject()) {
                throw new ValidationException("INTERNAL_ERROR", "command_not_object", null,
                        null, null, null, null);
            }
            object = root.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new ValidationException("INTERNAL_ERROR", "malformed_json", null,
                    null, null, null, null);
        }

        String commandId = string(object, "command_id");
        String requestId = string(object, "request_id");
        String eventId = string(object, "event_id");
        String robotId = string(object, "robot_id");
        String actionValue = string(object, "action");
        String videoId = string(object, "video_id");

        if (!object.keySet().equals(REQUIRED_FIELDS)) {
            throw failure("INTERNAL_ERROR", "request_fields_do_not_match_schema", commandId,
                    eventId, robotId, actionValue, videoId);
        }
        requireEquals(string(object, "schema_version"), "1.1", "unsupported_schema_version",
                commandId, eventId, robotId, actionValue, videoId);
        requireEquals(string(object, "message_type"), "video.command", "unsupported_message_type",
                commandId, eventId, robotId, actionValue, videoId);
        requireNonEmpty(commandId, "missing_command_id", commandId, eventId, robotId,
                actionValue, videoId);
        requireNonEmpty(requestId, "missing_request_id", commandId, eventId, robotId,
                actionValue, videoId);
        if (!commandId.equals(requestId)) {
            throw failure("INTERNAL_ERROR", "command_id_request_id_mismatch", commandId,
                    eventId, robotId, actionValue, videoId);
        }
        requireNonEmpty(eventId, "missing_event_id", commandId, eventId, robotId,
                actionValue, videoId);
        requireNonEmpty(robotId, "missing_robot_id", commandId, eventId, robotId,
                actionValue, videoId);
        if (!expectedRobotId.equals(robotId)) {
            throw failure("INTERNAL_ERROR", "robot_id_mismatch", commandId, eventId, robotId,
                    actionValue, videoId);
        }
        String residentId = string(object, "resident_id");
        requireNonEmpty(residentId, "missing_resident_id", commandId, eventId, robotId,
                actionValue, videoId);
        MediaV11Command.Action action = MediaV11Command.Action.fromWireValue(actionValue);
        if (action == null) {
            throw failure("UNSUPPORTED_MEDIA_ACTION", "unsupported_media_action", commandId,
                    eventId, robotId, actionValue, videoId);
        }
        String executionClass = string(object, "execution_class");
        if (!action.executionClass().equals(executionClass)) {
            throw failure("MEDIA_CONTROL_CONFLICT", "execution_class_action_mismatch", commandId,
                    eventId, robotId, actionValue, videoId);
        }
        JsonElement targetElement = object.get("target_playback_session_id");
        String target = nullableString(targetElement);
        if (action.isControl() && isEmpty(target)) {
            throw failure("MEDIA_SESSION_NOT_FOUND", "missing_target_playback_session_id",
                    commandId, eventId, robotId, actionValue, videoId);
        }
        if (!action.isControl() && !targetElement.isJsonNull()) {
            throw failure("MEDIA_CONTROL_CONFLICT", "play_target_session_must_be_null",
                    commandId, eventId, robotId, actionValue, videoId);
        }
        requireNonEmpty(videoId, "missing_video_id", commandId, eventId, robotId,
                actionValue, videoId);
        if (!VIDEO_IDS.contains(videoId)) {
            throw failure("VIDEO_ID_NOT_ALLOWED", "video_id_not_allowed", commandId,
                    eventId, robotId, actionValue, videoId);
        }
        JsonElement parameters = object.get("parameters");
        if (!parameters.isJsonObject()) {
            throw failure("INTERNAL_ERROR", "parameters_not_object", commandId, eventId,
                    robotId, actionValue, videoId);
        }
        if (parameters.getAsJsonObject().size() > 0) {
            throw failure("UNSUPPORTED_MEDIA_ACTION", "unsupported_media_parameters", commandId,
                    eventId, robotId, actionValue, videoId);
        }
        String source = string(object, "source");
        if (!SOURCES.contains(source)) {
            throw failure("INTERNAL_ERROR", "unsupported_source", commandId, eventId, robotId,
                    actionValue, videoId);
        }
        String timestamp = string(object, "timestamp");
        requireNonEmpty(timestamp, "missing_timestamp", commandId, eventId, robotId,
                actionValue, videoId);
        return new MediaV11Command(commandId, eventId, robotId, residentId, action, target,
                videoId, source, timestamp);
    }

    /**
     * Extracts only the fields required to publish a schema-conformant rejection.
     * This never authorizes execution and returns null when safe correlation is impossible.
     */
    public static MediaV11Command correlateForRejection(
            String payload, String expectedRobotId) {
        try {
            JsonObject object = JsonParser.parseString(payload).getAsJsonObject();
            String commandId = string(object, "command_id");
            String requestId = string(object, "request_id");
            String eventId = string(object, "event_id");
            String robotId = string(object, "robot_id");
            String actionValue = string(object, "action");
            String videoId = string(object, "video_id");
            MediaV11Command.Action action = MediaV11Command.Action.fromWireValue(actionValue);
            if (isEmpty(commandId) || isEmpty(eventId)
                    || !expectedRobotId.equals(robotId) || action == null || isEmpty(videoId)) {
                return null;
            }
            String target = nullableString(object.get("target_playback_session_id"));
            if (action.isControl() && isEmpty(target)) {
                return null;
            }
            if (!action.isControl()) {
                target = null;
            }
            String residentId = string(object, "resident_id");
            String source = string(object, "source");
            String timestamp = string(object, "timestamp");
            return new MediaV11Command(commandId, eventId, robotId,
                    isEmpty(residentId) ? "unknown" : residentId, action, target, videoId,
                    isEmpty(source) ? "hermes_temi_bridge" : source,
                    isEmpty(timestamp) ? "unknown" : timestamp);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            return null;
        }
        return value.getAsString().trim();
    }

    private static String nullableString(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return null;
        }
        return value.getAsString().trim();
    }

    private static void requireEquals(
            String actual, String expected, String reason, String commandId, String eventId,
            String robotId, String action, String videoId) throws ValidationException {
        if (!expected.equals(actual)) {
            throw failure("INTERNAL_ERROR", reason, commandId, eventId, robotId, action, videoId);
        }
    }

    private static void requireNonEmpty(
            String value, String reason, String commandId, String eventId, String robotId,
            String action, String videoId) throws ValidationException {
        if (isEmpty(value)) {
            throw failure("INTERNAL_ERROR", reason, commandId, eventId, robotId, action, videoId);
        }
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private static ValidationException failure(
            String errorCode, String reason, String commandId, String eventId, String robotId,
            String action, String videoId) {
        return new ValidationException(errorCode, reason, commandId, eventId, robotId, action,
                videoId);
    }

    public static final class ValidationException extends Exception {
        private final String errorCode;
        private final String reason;
        private final String commandId;
        private final String eventId;
        private final String robotId;
        private final String action;
        private final String videoId;

        private ValidationException(
                String errorCode, String reason, String commandId, String eventId, String robotId,
                String action, String videoId) {
            super(reason);
            this.errorCode = errorCode;
            this.reason = reason;
            this.commandId = commandId;
            this.eventId = eventId;
            this.robotId = robotId;
            this.action = action;
            this.videoId = videoId;
        }

        public String getErrorCode() { return errorCode; }
        public String getReason() { return reason; }
        public String getCommandId() { return commandId; }
        public String getEventId() { return eventId; }
        public String getRobotId() { return robotId; }
        public String getAction() { return action; }
        public String getVideoId() { return videoId; }
        public boolean hasResultCorrelation() {
            return !isEmpty(commandId) && !isEmpty(eventId) && !isEmpty(robotId)
                    && MediaV11Command.Action.fromWireValue(action) != null && !isEmpty(videoId);
        }
    }
}
