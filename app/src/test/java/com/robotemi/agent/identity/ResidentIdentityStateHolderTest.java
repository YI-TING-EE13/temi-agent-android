package com.robotemi.agent.identity;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ResidentIdentityStateHolderTest {
    private static final long BASE_WALL = 1_775_044_800_000L;
    private FakeClock clock;
    private ResidentIdentityParser parser;
    private ResidentIdentityStateHolder holder;

    @Before
    public void setUp() {
        clock = new FakeClock(BASE_WALL + 1_000L, 10_000L);
        parser = new ResidentIdentityParser();
        holder = new ResidentIdentityStateHolder(clock, true);
    }

    @Test
    public void acceptsKnownIdentityWithRemainingMonotonicTtl() throws Exception {
        ResidentIdentityStateHolder.Update update = holder.accept(father(
                "event-1", "2026-04-01T12:00:00Z"));
        assertEquals(ResidentIdentityStateHolder.Disposition.ACCEPTED, update.disposition);
        assertEquals(ResidentIdentityUiState.Kind.FATHER, update.state.kind);
        assertEquals(29_000L, holder.remainingTtlMillis());
        assertTrue(update.state.allowsResidentSpecificContent());
    }

    @Test
    public void duplicateDoesNotRenewTtl() throws Exception {
        ResidentIdentityResult result = father("event-1", "2026-04-01T12:00:00Z");
        holder.accept(result);
        clock.advance(5_000L);
        long before = holder.remainingTtlMillis();
        ResidentIdentityStateHolder.Update duplicate = holder.accept(result);
        assertEquals(ResidentIdentityStateHolder.Disposition.DUPLICATE,
                duplicate.disposition);
        assertEquals(before, holder.remainingTtlMillis());
    }

    @Test
    public void olderMessageDoesNotReplaceNewerOrRenew() throws Exception {
        holder.accept(mother("new", "2026-04-01T12:00:01Z"));
        clock.advance(1_000L);
        long before = holder.remainingTtlMillis();
        ResidentIdentityStateHolder.Update update = holder.accept(father(
                "old", "2026-04-01T12:00:00Z"));
        assertEquals(ResidentIdentityStateHolder.Disposition.OUT_OF_ORDER,
                update.disposition);
        assertEquals(ResidentIdentityUiState.Kind.MOTHER, update.state.kind);
        assertEquals(before, holder.remainingTtlMillis());
    }

    @Test
    public void staleOlderMessageCannotClearNewerValidState() throws Exception {
        clock.wall = BASE_WALL + 21_000L;
        holder.accept(mother("new", "2026-04-01T12:00:20Z"));
        ResidentIdentityStateHolder.Update update = holder.accept(father(
                "old-stale", "2026-04-01T11:59:30Z"));
        assertEquals(ResidentIdentityStateHolder.Disposition.OUT_OF_ORDER,
                update.disposition);
        assertEquals(ResidentIdentityUiState.Kind.MOTHER, update.state.kind);
    }

    @Test
    public void sameEventChangedContentClearsToUnknown() throws Exception {
        holder.accept(father("same", "2026-04-01T12:00:00Z"));
        ResidentIdentityStateHolder.Update update = holder.accept(mother(
                "same", "2026-04-01T12:00:01Z"));
        assertEquals(ResidentIdentityStateHolder.Disposition.CONFLICT, update.disposition);
        assertEquals(ResidentIdentityUiState.Kind.UNKNOWN, update.state.kind);
        assertTrue(update.clearResidentSpecificUi);
    }

    @Test
    public void newerSameEventConflictWatermarkPreventsOlderRevival() throws Exception {
        holder.accept(father("same", "2026-04-01T12:00:00Z"));
        ResidentIdentityStateHolder.Update conflict = holder.accept(mother(
                "same", "2026-04-01T12:00:02Z"));
        ResidentIdentityStateHolder.Update older = holder.accept(father(
                "different", "2026-04-01T12:00:01Z"));

        assertEquals(ResidentIdentityStateHolder.Disposition.CONFLICT,
                conflict.disposition);
        assertEquals(ResidentIdentityStateHolder.Disposition.OUT_OF_ORDER,
                older.disposition);
        assertEquals(ResidentIdentityUiState.Kind.UNKNOWN, older.state.kind);
    }

    @Test
    public void newerStaleWatermarkPreventsOlderRevival() throws Exception {
        clock.wall = BASE_WALL + 100_000L;
        ResidentIdentityStateHolder.Update stale = holder.accept(father(
                "stale-newer", "2026-04-01T12:00:02Z"));
        ResidentIdentityStateHolder.Update older = holder.accept(mother(
                "stale-older", "2026-04-01T12:00:01Z"));

        assertEquals(ResidentIdentityStateHolder.Disposition.STALE, stale.disposition);
        assertEquals(ResidentIdentityStateHolder.Disposition.OUT_OF_ORDER,
                older.disposition);
        assertEquals(ResidentIdentityUiState.Kind.UNKNOWN, older.state.kind);
    }

    @Test
    public void farFutureTimestampDoesNotPoisonOrderingWatermark() throws Exception {
        holder.accept(father("trusted", "2026-04-01T12:00:00Z"));
        ResidentIdentityStateHolder.Update future = holder.accept(mother(
                "future", "2026-04-01T13:00:00Z"));
        ResidentIdentityStateHolder.Update current = holder.accept(mother(
                "current", "2026-04-01T12:00:01Z"));

        assertEquals(ResidentIdentityStateHolder.Disposition.FUTURE_TIMESTAMP,
                future.disposition);
        assertEquals(ResidentIdentityStateHolder.Disposition.ACCEPTED,
                current.disposition);
        assertEquals(ResidentIdentityUiState.Kind.MOTHER, current.state.kind);
    }

    @Test
    public void subMillisecondOrderingIsNotCollapsed() throws Exception {
        holder.accept(father("first", "2026-04-01T12:00:00.0002Z"));
        ResidentIdentityStateHolder.Update older = holder.accept(mother(
                "older", "2026-04-01T12:00:00.0001Z"));
        ResidentIdentityStateHolder.Update equal = holder.accept(father(
                "equal", "2026-04-01T12:00:00.0002Z"));

        assertEquals(ResidentIdentityStateHolder.Disposition.OUT_OF_ORDER,
                older.disposition);
        assertEquals(ResidentIdentityStateHolder.Disposition.DUPLICATE,
                equal.disposition);
        assertEquals(ResidentIdentityUiState.Kind.FATHER, equal.state.kind);
    }

    @Test
    public void equalTimestampDifferentIdentityClearsToUnknown() throws Exception {
        holder.accept(father("father", "2026-04-01T12:00:00Z"));
        ResidentIdentityStateHolder.Update update = holder.accept(mother(
                "mother", "2026-04-01T12:00:00Z"));
        assertEquals(ResidentIdentityStateHolder.Disposition.CONFLICT, update.disposition);
        assertFalse(update.state.allowsResidentSpecificContent());
    }

    @Test
    public void newerUnknownImmediatelyClearsKnownState() throws Exception {
        holder.accept(father("father", "2026-04-01T12:00:00Z"));
        ResidentIdentityResult unknown = parser.parse(
                ResidentIdentityParserTest.unknown("unknown").replace(
                        "2026-07-27T12:00:00Z", "2026-04-01T12:00:01Z"));
        ResidentIdentityStateHolder.Update update = holder.accept(unknown);
        assertEquals(ResidentIdentityStateHolder.Disposition.ACCEPTED_UNKNOWN,
                update.disposition);
        assertEquals(ResidentIdentityUiState.Kind.UNKNOWN, update.state.kind);
    }

    @Test
    public void invalidPayloadAndResidentSwitchRequireSensitiveUiClear() throws Exception {
        holder.accept(father("father", "2026-04-01T12:00:00Z"));
        ResidentIdentityStateHolder.Update invalid = holder.rejectInvalid();
        assertEquals(ResidentIdentityStateHolder.Disposition.INVALID, invalid.disposition);
        assertTrue(invalid.clearResidentSpecificUi);
        assertEquals(ResidentIdentityUiState.Kind.UNKNOWN, invalid.state.kind);

        ResidentIdentityStateHolder.Update switched = holder.accept(mother(
                "mother", "2026-04-01T12:00:01Z"));
        assertEquals(ResidentIdentityStateHolder.Disposition.ACCEPTED,
                switched.disposition);
        assertTrue(switched.clearResidentSpecificUi);
        assertEquals(ResidentIdentityUiState.Kind.MOTHER, switched.state.kind);
    }

    @Test
    public void staleAndFutureMessagesFailClosed() throws Exception {
        ResidentIdentityStateHolder.Update stale = holder.accept(father(
                "stale", "2026-04-01T11:59:30Z"));
        assertEquals(ResidentIdentityStateHolder.Disposition.STALE, stale.disposition);
        assertEquals(ResidentIdentityUiState.Kind.UNKNOWN, stale.state.kind);

        ResidentIdentityStateHolder freshHolder =
                new ResidentIdentityStateHolder(clock, true);
        ResidentIdentityStateHolder.Update future = freshHolder.accept(father(
                "future", "2026-04-01T12:00:06.001Z"));
        assertEquals(ResidentIdentityStateHolder.Disposition.FUTURE_TIMESTAMP,
                future.disposition);
        assertEquals(ResidentIdentityUiState.Kind.UNKNOWN, future.state.kind);
    }

    @Test
    public void activityRecreationKeepsOnlyMonotonicRemainder() throws Exception {
        holder.accept(father("event", "2026-04-01T12:00:00Z"));
        clock.advance(20_000L);
        ResidentIdentityStateHolder sameViewModelHolder = holder;
        assertEquals(9_000L, sameViewModelHolder.remainingTtlMillis());
        clock.advance(9_000L);
        assertEquals(ResidentIdentityUiState.Kind.UNKNOWN,
                sameViewModelHolder.currentState().kind);
    }

    @Test
    public void processRestartBeginsUnknown() throws Exception {
        holder.accept(father("event", "2026-04-01T12:00:00Z"));
        ResidentIdentityStateHolder restarted =
                new ResidentIdentityStateHolder(clock, true);
        assertEquals(ResidentIdentityUiState.Kind.UNKNOWN, restarted.currentState().kind);
    }

    @Test
    public void disabledConsumerAlwaysUnknown() throws Exception {
        ResidentIdentityStateHolder disabled =
                new ResidentIdentityStateHolder(clock, false);
        ResidentIdentityStateHolder.Update update = disabled.accept(father(
                "event", "2026-04-01T12:00:00Z"));
        assertEquals(ResidentIdentityStateHolder.Disposition.DISABLED, update.disposition);
        assertFalse(update.state.allowsResidentSpecificContent());
    }

    @Test
    public void exactGateNeverAuthorizesMismatchedPartition() {
        assertTrue(ResidentIdentityGate.isAuthorized(
                ResidentIdentityUiState.Kind.FATHER, "resident_father"));
        assertFalse(ResidentIdentityGate.isAuthorized(
                ResidentIdentityUiState.Kind.FATHER, "resident_mother"));
        assertFalse(ResidentIdentityGate.isAuthorized(
                ResidentIdentityUiState.Kind.UNKNOWN, "resident_father"));
    }

    private ResidentIdentityResult father(String event, String timestamp) throws Exception {
        return parser.parse(ResidentIdentityParserTest.known(
                event, "resident_father", "father", 0.9,
                "vision_gender_fallback", timestamp));
    }

    private ResidentIdentityResult mother(String event, String timestamp) throws Exception {
        return parser.parse(ResidentIdentityParserTest.known(
                event, "resident_mother", "mother", 0.9,
                "vision_gender_fallback", timestamp));
    }

    private static final class FakeClock implements IdentityClock {
        long wall;
        long monotonic;

        FakeClock(long wall, long monotonic) {
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
