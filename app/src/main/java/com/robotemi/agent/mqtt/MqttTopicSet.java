package com.robotemi.agent.mqtt;

/** MQTT topics derived from the locally configured robot identity. */
public final class MqttTopicSet {
    public static final String EVENT_ASR_LEGACY = "temi/event/asr";
    public static final String ACTION_SPEAK = "temi/action/speak";
    public static final String ACTION_NAVIGATE = "temi/action/navigate";
    public static final String ACTION_WAKEUP = "temi/action/wakeup";

    private final String robotId;
    private final String commandRequest;
    private final String commandResult;
    private final String residentIdentityResult;
    private final boolean residentIdentityEnabled;
    private final String careReport;
    private final String careReportInteractionResult;
    private final boolean careReportEnabled;

    public MqttTopicSet(String robotId) {
        this(robotId, false, false);
    }

    public MqttTopicSet(String robotId, boolean residentIdentityEnabled) {
        this(robotId, residentIdentityEnabled, false);
    }

    public MqttTopicSet(
            String robotId, boolean residentIdentityEnabled, boolean careReportEnabled) {
        this.robotId = robotId;
        this.commandRequest = "temi/" + robotId + "/cmd/request";
        this.commandResult = "temi/" + robotId + "/cmd/result";
        this.residentIdentityResult =
                "temi/" + robotId + "/resident/identity/result";
        this.residentIdentityEnabled = residentIdentityEnabled || careReportEnabled;
        this.careReport = "temi/" + robotId + "/care/report";
        this.careReportInteractionResult =
                "temi/" + robotId + "/care/report/interaction/result";
        this.careReportEnabled = careReportEnabled;
    }

    public String robotId() {
        return robotId;
    }

    public String commandRequest() {
        return commandRequest;
    }

    public String commandResult() {
        return commandResult;
    }

    public String residentIdentityResult() {
        return residentIdentityResult;
    }

    public String careReport() {
        return careReport;
    }

    public String careReportInteractionResult() {
        return careReportInteractionResult;
    }

    public String[] subscribedTopics() {
        if (residentIdentityEnabled && careReportEnabled) {
            return new String[] {
                    commandRequest,
                    residentIdentityResult,
                    careReport,
                    ACTION_SPEAK,
                    ACTION_NAVIGATE,
                    ACTION_WAKEUP
            };
        }
        if (residentIdentityEnabled) {
            return new String[] {
                    commandRequest,
                    residentIdentityResult,
                    ACTION_SPEAK,
                    ACTION_NAVIGATE,
                    ACTION_WAKEUP
            };
        }
        if (careReportEnabled) {
            return new String[] {
                    commandRequest,
                    careReport,
                    ACTION_SPEAK,
                    ACTION_NAVIGATE,
                    ACTION_WAKEUP
            };
        }
        return new String[] {
            commandRequest,
            ACTION_SPEAK,
            ACTION_NAVIGATE,
            ACTION_WAKEUP
        };
    }
}
