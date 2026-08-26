package com.robotemi.agent.mqtt;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class InboundMqttLogSummaryTest {
    @Test
    public void syntheticSensitiveMarkerNeverAppearsInLogSummary() {
        String marker = "SYNTHETIC_PRIVATE_CARE_MARKER";
        String payload = "{\"summary\":\"" + marker + "\"}";

        String summary = InboundMqttLogSummary.describe(
                InboundMqttLogSummary.Category.CARE_REPORT,
                false,
                payload);

        assertFalse(summary.contains(marker));
        assertFalse(summary.contains(payload));
        assertTrue(summary.contains("type=care_report"));
        assertTrue(summary.contains("retained=false"));
        assertTrue(summary.contains("bytes="
                + payload.getBytes(StandardCharsets.UTF_8).length));
        assertTrue(summary.matches(".*sha256=[0-9a-f]{64}$"));
    }
}
