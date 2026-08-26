package com.robotemi.agent.identity;

import androidx.lifecycle.ViewModel;

/** Activity-recreation-safe owner; process restart intentionally begins unknown. */
public final class ResidentIdentityViewModel extends ViewModel {
    private final ResidentIdentityParser parser;
    private final ResidentIdentityStateHolder holder;

    public ResidentIdentityViewModel() {
        this(new ResidentIdentityParser(), new SystemIdentityClock(), true);
    }

    ResidentIdentityViewModel(
            ResidentIdentityParser parser, IdentityClock clock, boolean enabled) {
        this.parser = parser;
        this.holder = new ResidentIdentityStateHolder(clock, enabled);
    }

    public ResidentIdentityStateHolder.Update acceptPayload(String payload) {
        try {
            return holder.accept(parser.parse(payload));
        } catch (ResidentIdentityParser.ParseException e) {
            return holder.rejectInvalid();
        }
    }

    public ResidentIdentityStateHolder.Update expireIfNeeded() {
        return holder.expireIfNeeded();
    }

    public ResidentIdentityUiState state() {
        return holder.currentState();
    }

    public long remainingTtlMillis() {
        return holder.remainingTtlMillis();
    }
}
