package com.robotemi.agent.identity;

/** Framework-neutral feature and TTL policy shared by identity labels and care gating. */
public final class ResidentIdentityLifecyclePolicy {
    public static final class Decision {
        public final boolean showIdentityLabel;
        public final boolean clearSensitiveUi;
        public final long expiryDelayMillis;

        Decision(
                boolean showIdentityLabel,
                boolean clearSensitiveUi,
                long expiryDelayMillis) {
            this.showIdentityLabel = showIdentityLabel;
            this.clearSensitiveUi = clearSensitiveUi;
            this.expiryDelayMillis = expiryDelayMillis;
        }
    }

    private ResidentIdentityLifecyclePolicy() {}

    public static Decision decide(
            boolean identityLabelEnabled,
            boolean careReportEnabled,
            ResidentIdentityUiState state,
            boolean explicitClear,
            long remainingTtlMillis) {
        boolean authorizationLifecycleEnabled =
                identityLabelEnabled || careReportEnabled;
        boolean authorized = authorizationLifecycleEnabled
                && state != null
                && state.allowsResidentSpecificContent();
        long expiryDelay = authorized ? Math.max(0L, remainingTtlMillis) : 0L;
        return new Decision(
                identityLabelEnabled,
                authorizationLifecycleEnabled && (explicitClear || !authorized),
                expiryDelay);
    }
}
