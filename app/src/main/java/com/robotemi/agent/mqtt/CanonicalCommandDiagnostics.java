package com.robotemi.agent.mqtt;

import androidx.annotation.Nullable;

/**
 * Structured, credential-free diagnostics for the service-owned canonical route.
 *
 * <p>Implementations must treat identifiers as opaque and redact them before
 * writing to a process log.  Payloads, speech text, endpoints and credentials
 * are intentionally not part of this seam.</p>
 */
interface CanonicalCommandDiagnostics {
    void record(
            String phase,
            String topicClass,
            @Nullable String commandId,
            @Nullable String eventId,
            @Nullable String actionId,
            String state,
            String outcome,
            @Nullable String exceptionClass);

    static CanonicalCommandDiagnostics noOp() {
        return (phase, topicClass, commandId, eventId, actionId,
                state, outcome, exceptionClass) -> {};
    }
}
