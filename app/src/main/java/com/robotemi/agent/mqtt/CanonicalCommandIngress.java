package com.robotemi.agent.mqtt;

import androidx.annotation.NonNull;

import com.robotemi.agent.command.CanonicalCommandValidator;
import com.robotemi.agent.command.CanonicalCommandValidator.CanonicalCommand;

/**
 * Narrow domain boundary for canonical command validation and handoff.
 *
 * <p>This boundary deliberately owns no queue, persistence, executor, hardware,
 * MQTT client, or observer lifecycle.  The service supplies the existing command
 * runtime/ledger handoff.</p>
 */
final class CanonicalCommandIngress {
    interface Delegate {
        boolean onValidated(@NonNull CanonicalCommand command, @NonNull String payload);
    }

    private final Delegate delegate;
    private final CanonicalCommandDiagnostics diagnostics;

    CanonicalCommandIngress(@NonNull Delegate delegate) {
        this(delegate, CanonicalCommandDiagnostics.noOp());
    }

    CanonicalCommandIngress(
            @NonNull Delegate delegate,
            @NonNull CanonicalCommandDiagnostics diagnostics) {
        this.delegate = delegate;
        this.diagnostics = diagnostics;
    }

    boolean ingest(@NonNull String payload, @NonNull String expectedRobotId) {
        try {
            CanonicalCommand command = CanonicalCommandValidator.validate(
                    payload, expectedRobotId);
            diagnostics.record(
                    "validate", "command_request", command.getCommandId(),
                    command.getEventId(), actionId(command), "validated", "accepted", null);
            return delegate.onValidated(command, payload);
        } catch (CanonicalCommandValidator.ValidationException e) {
            diagnostics.record(
                    "validate", "command_request", e.getCommandId(), e.getEventId(),
                    e.getActionId(), "invalid", e.getReason(),
                    CanonicalCommandValidator.ValidationException.class.getSimpleName());
            return false;
        }
    }

    private static String actionId(CanonicalCommand command) {
        return command.getActions().size() == 1
                ? command.getActions().get(0).getActionId() : null;
    }
}
