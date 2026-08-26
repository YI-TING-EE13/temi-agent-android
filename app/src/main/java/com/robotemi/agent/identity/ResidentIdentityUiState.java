package com.robotemi.agent.identity;

import androidx.annotation.Nullable;

/** Minimal UI-safe identity state; no resident data is carried for unknown. */
public final class ResidentIdentityUiState {
    public enum Kind {
        IDENTIFYING,
        FATHER,
        MOTHER,
        UNKNOWN
    }

    public static final ResidentIdentityUiState UNKNOWN =
            new ResidentIdentityUiState(Kind.UNKNOWN, null, 0);
    public static final ResidentIdentityUiState IDENTIFYING =
            new ResidentIdentityUiState(Kind.IDENTIFYING, null, 0);

    public final Kind kind;
    @Nullable public final String residentId;
    public final long expiresAtMonotonicMs;

    private ResidentIdentityUiState(
            Kind kind, @Nullable String residentId, long expiresAtMonotonicMs) {
        this.kind = kind;
        this.residentId = residentId;
        this.expiresAtMonotonicMs = expiresAtMonotonicMs;
    }

    static ResidentIdentityUiState known(
            ResidentIdentityResult result, long expiresAtMonotonicMs) {
        Kind kind = "father".equals(result.identityStatus) ? Kind.FATHER : Kind.MOTHER;
        return new ResidentIdentityUiState(kind, result.residentId, expiresAtMonotonicMs);
    }

    public boolean allowsResidentSpecificContent() {
        return ResidentIdentityGate.isAuthorized(kind, residentId);
    }
}
