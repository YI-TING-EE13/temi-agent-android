package com.robotemi.agent.identity;

import androidx.annotation.Nullable;

/** Exact stable-ID gate for all future resident-specific UI entry points. */
public final class ResidentIdentityGate {
    private ResidentIdentityGate() {}

    public static boolean isAuthorized(
            ResidentIdentityUiState.Kind kind, @Nullable String residentId) {
        return (kind == ResidentIdentityUiState.Kind.FATHER
                && "resident_father".equals(residentId))
                || (kind == ResidentIdentityUiState.Kind.MOTHER
                && "resident_mother".equals(residentId));
    }
}
