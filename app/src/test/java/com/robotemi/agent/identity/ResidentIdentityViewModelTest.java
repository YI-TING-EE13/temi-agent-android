package com.robotemi.agent.identity;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ResidentIdentityViewModelTest {
    @Test
    public void recreationKeepsRemainderReconnectDoesNotRenewAndProcessRestartsUnknown() {
        MutableClock clock = new MutableClock(1_775_044_801_000L, 10_000L);
        ResidentIdentityViewModel original = new ResidentIdentityViewModel(
                new ResidentIdentityParser(), clock, true);
        String payload = ResidentIdentityParserTest.known(
                "event", "resident_father", "father", 0.9,
                "vision_gender_fallback", "2026-04-01T12:00:00Z");
        original.acceptPayload(payload);
        clock.advance(5_000L);

        ResidentIdentityViewModel recreatedActivityReference = original;
        long beforeReconnect = recreatedActivityReference.remainingTtlMillis();
        ResidentIdentityStateHolder.Update reconnectDuplicate =
                recreatedActivityReference.acceptPayload(payload);

        assertEquals(ResidentIdentityUiState.Kind.FATHER,
                recreatedActivityReference.state().kind);
        assertEquals(ResidentIdentityStateHolder.Disposition.DUPLICATE,
                reconnectDuplicate.disposition);
        assertEquals(beforeReconnect, recreatedActivityReference.remainingTtlMillis());

        ResidentIdentityViewModel restartedProcess = new ResidentIdentityViewModel(
                new ResidentIdentityParser(), clock, true);
        assertEquals(ResidentIdentityUiState.Kind.UNKNOWN, restartedProcess.state().kind);
        assertEquals(0L, restartedProcess.remainingTtlMillis());
    }

    private static final class MutableClock implements IdentityClock {
        private long wall;
        private long monotonic;

        MutableClock(long wall, long monotonic) {
            this.wall = wall;
            this.monotonic = monotonic;
        }

        void advance(long millis) {
            wall += millis;
            monotonic += millis;
        }

        @Override
        public long wallTimeMillis() {
            return wall;
        }

        @Override
        public long monotonicTimeMillis() {
            return monotonic;
        }
    }
}
