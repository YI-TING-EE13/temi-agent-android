package com.robotemi.agent.identity;

/** Maps state to stable resource-neutral presentation keys. */
public final class ResidentIdentityUiMapper {
    public enum Label {
        IDENTIFYING,
        FATHER,
        MOTHER,
        UNKNOWN
    }

    private ResidentIdentityUiMapper() {}

    public static Label labelFor(ResidentIdentityUiState state) {
        switch (state.kind) {
            case IDENTIFYING:
                return Label.IDENTIFYING;
            case FATHER:
                return Label.FATHER;
            case MOTHER:
                return Label.MOTHER;
            default:
                return Label.UNKNOWN;
        }
    }
}
