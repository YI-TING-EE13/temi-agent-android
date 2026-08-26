package com.robotemi.agent.identity;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ResidentIdentityLifecyclePolicyTest {
    @Test
    public void careOnlyFeatureStillSchedulesKnownIdentityExpiry() throws Exception {
        ResidentIdentityLifecyclePolicy.Decision decision =
                ResidentIdentityLifecyclePolicy.decide(
                        false, true, knownFather(), false, 12_345L);

        assertFalse(decision.showIdentityLabel);
        assertFalse(decision.clearSensitiveUi);
        assertEquals(12_345L, decision.expiryDelayMillis);
    }

    @Test
    public void careOnlyUnknownClearsSensitiveUiWithoutScheduling() {
        ResidentIdentityLifecyclePolicy.Decision decision =
                ResidentIdentityLifecyclePolicy.decide(
                        false, true, ResidentIdentityUiState.UNKNOWN, false, 12_345L);

        assertFalse(decision.showIdentityLabel);
        assertTrue(decision.clearSensitiveUi);
        assertEquals(0L, decision.expiryDelayMillis);
    }

    private static ResidentIdentityUiState knownFather() throws Exception {
        IdentityClock clock = new IdentityClock() {
            @Override public long wallTimeMillis() { return 1_785_153_601_000L; }
            @Override public long monotonicTimeMillis() { return 10_000L; }
        };
        ResidentIdentityStateHolder holder =
                new ResidentIdentityStateHolder(clock, true);
        return holder.accept(new ResidentIdentityParser().parse(
                "{\"schema_version\":\"1.0\","
                        + "\"event_id\":\"synthetic-event\","
                        + "\"resident_id\":\"resident_father\","
                        + "\"display_name\":\"father\","
                        + "\"identity_status\":\"father\","
                        + "\"confidence\":0.9,"
                        + "\"source\":\"manual_selection\","
                        + "\"reason\":\"Synthetic test identity.\","
                        + "\"timestamp\":\"2026-07-27T12:00:00Z\"}")).state;
    }
}
