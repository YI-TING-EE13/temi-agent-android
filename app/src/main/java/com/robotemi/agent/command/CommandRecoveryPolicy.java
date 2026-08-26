package com.robotemi.agent.command;

import java.util.List;

/** Recovery policy for commands whose execution may have been interrupted by process death. */
public final class CommandRecoveryPolicy {
    public enum Classification {
        SAFE_RETRY,
        UNSAFE_RETRY
    }

    private CommandRecoveryPolicy() {}

    /** Only commands with no irreversible hardware effect may be retried after RECEIVED. */
    public static Classification classify(List<CommandLedger.ActionSummary> actions) {
        if (actions == null || actions.isEmpty()) {
            return Classification.UNSAFE_RETRY;
        }
        for (CommandLedger.ActionSummary action : actions) {
            if (!"noop".equals(action.type) && !"stop".equals(action.type)) {
                return Classification.UNSAFE_RETRY;
            }
        }
        return Classification.SAFE_RETRY;
    }
}
