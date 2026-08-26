package com.robotemi.agent.identity;

/** Thread-safe freshness, ordering, conflict, and fail-closed state owner. */
public final class ResidentIdentityStateHolder {
    public static final long TTL_MS = 30_000L;
    public static final long FUTURE_SKEW_MS = 5_000L;

    public enum Disposition {
        ACCEPTED,
        ACCEPTED_UNKNOWN,
        DUPLICATE,
        OUT_OF_ORDER,
        CONFLICT,
        INVALID,
        STALE,
        FUTURE_TIMESTAMP,
        EXPIRED,
        DISABLED
    }

    public static final class Update {
        public final Disposition disposition;
        public final ResidentIdentityUiState state;
        public final boolean clearResidentSpecificUi;

        Update(
                Disposition disposition,
                ResidentIdentityUiState state,
                boolean clearResidentSpecificUi) {
            this.disposition = disposition;
            this.state = state;
            this.clearResidentSpecificUi = clearResidentSpecificUi;
        }
    }

    private final IdentityClock clock;
    private final boolean enabled;
    private ResidentIdentityUiState state = ResidentIdentityUiState.UNKNOWN;
    private ResidentIdentityResult orderingAnchor;

    public ResidentIdentityStateHolder(IdentityClock clock, boolean enabled) {
        this.clock = clock;
        this.enabled = enabled;
    }

    public synchronized Update accept(ResidentIdentityResult result) {
        if (!enabled) {
            return clear(Disposition.DISABLED);
        }
        expireInternal();
        long wallNow = clock.wallTimeMillis();
        long age = wallNow - result.timestampMillis;
        if (age < -FUTURE_SKEW_MS) {
            // An untrusted far-future timestamp clears UI but cannot poison ordering.
            return clear(Disposition.FUTURE_TIMESTAMP);
        }
        if (orderingAnchor != null) {
            if (result.eventId.equals(orderingAnchor.eventId)) {
                if (result.sameCanonicalContent(orderingAnchor)) {
                    return unchanged(Disposition.DUPLICATE);
                }
                advanceOrderingAnchor(result);
                return clear(Disposition.CONFLICT);
            }
            int ordering = result.compareTimestamp(orderingAnchor);
            if (ordering < 0) {
                return unchanged(Disposition.OUT_OF_ORDER);
            }
            if (ordering == 0) {
                if (result.sameObservationContent(orderingAnchor)) {
                    return unchanged(Disposition.DUPLICATE);
                }
                orderingAnchor = result;
                return clear(Disposition.CONFLICT);
            }
        }
        if (age >= TTL_MS) {
            advanceOrderingAnchor(result);
            return clear(Disposition.STALE);
        }

        orderingAnchor = result;
        if ("unknown".equals(result.identityStatus)) {
            state = ResidentIdentityUiState.UNKNOWN;
            return new Update(Disposition.ACCEPTED_UNKNOWN, state, true);
        }
        long remaining = TTL_MS - Math.max(0L, age);
        state = ResidentIdentityUiState.known(
                result, clock.monotonicTimeMillis() + remaining);
        return new Update(Disposition.ACCEPTED, state, true);
    }

    public synchronized Update rejectInvalid() {
        return clear(enabled ? Disposition.INVALID : Disposition.DISABLED);
    }

    public synchronized Update expireIfNeeded() {
        if (expireInternal()) {
            return new Update(Disposition.EXPIRED, state, true);
        }
        return unchanged(Disposition.DUPLICATE);
    }

    public synchronized ResidentIdentityUiState currentState() {
        expireInternal();
        return state;
    }

    public synchronized long remainingTtlMillis() {
        expireInternal();
        if (!state.allowsResidentSpecificContent()) {
            return 0;
        }
        return Math.max(0L, state.expiresAtMonotonicMs - clock.monotonicTimeMillis());
    }

    private boolean expireInternal() {
        if (state.allowsResidentSpecificContent()
                && clock.monotonicTimeMillis() >= state.expiresAtMonotonicMs) {
            state = ResidentIdentityUiState.UNKNOWN;
            return true;
        }
        return false;
    }

    private Update clear(Disposition disposition) {
        state = ResidentIdentityUiState.UNKNOWN;
        return new Update(disposition, state, true);
    }

    private Update unchanged(Disposition disposition) {
        return new Update(disposition, state, false);
    }

    private void advanceOrderingAnchor(ResidentIdentityResult candidate) {
        if (orderingAnchor == null || candidate.compareTimestamp(orderingAnchor) > 0) {
            orderingAnchor = candidate;
        }
    }
}
