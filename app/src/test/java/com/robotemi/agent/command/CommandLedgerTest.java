package com.robotemi.agent.command;

import com.google.gson.Gson;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CommandLedgerTest {
    private static final String ROBOT_ID = "temi-01";

    @Test
    public void duplicateCommandIsDedupeAndCachedResultIsReplayable() throws Exception {
        MemoryPersistence persistence = new MemoryPersistence();
        CommandLedger ledger = new CommandLedger(persistence);
        String payload = command("cmd-1", "noop", "not-sensitive");
        CanonicalCommandValidator.CanonicalCommand command = parse(payload);

        assertEquals(CommandLedger.AcceptState.FIRST_DELIVERY,
                ledger.accept(command, payload, 1_000L).state());
        assertEquals(CommandLedger.AcceptState.DUPLICATE_PENDING,
                ledger.accept(command, payload, 1_001L).state());
        assertTrue(ledger.markExecuting("cmd-1", 1_002L));
        assertTrue(ledger.markResultPending(
                "cmd-1", "{\"command_id\":\"cmd-1\"}",
                CommandLedger.State.COMPLETED, 1_003L));
        assertEquals(CommandLedger.AcceptState.DUPLICATE_CACHED_RESULT,
                ledger.accept(command, payload, 1_004L).state());
        assertEquals("{\"command_id\":\"cmd-1\"}",
                ledger.accept(command, payload, 1_005L).record().resultPayload);
        assertTrue(ledger.markResultDelivered("cmd-1", 1_006L));
    }

    @Test
    public void stateTransitionsPersistBeforeResultDelivery() throws Exception {
        MemoryPersistence persistence = new MemoryPersistence();
        CommandLedger ledger = new CommandLedger(persistence);
        String payload = command("cmd-2", "noop", "safe");
        CanonicalCommandValidator.CanonicalCommand command = parse(payload);

        ledger.accept(command, payload, 10L);
        assertEquals(CommandLedger.State.RECEIVED, persistence.record("cmd-2").state);
        ledger.markExecuting("cmd-2", 20L);
        assertEquals(CommandLedger.State.EXECUTING, persistence.record("cmd-2").state);
        ledger.markResultPending("cmd-2", "cached", CommandLedger.State.COMPLETED, 30L);
        assertEquals(CommandLedger.State.RESULT_PENDING, persistence.record("cmd-2").state);
        assertEquals(1, ledger.pendingResults().size());
        ledger.markResultDelivered("cmd-2", 40L);
        assertEquals(CommandLedger.State.COMPLETED, persistence.record("cmd-2").state);
        assertTrue(ledger.pendingResults().isEmpty());
    }

    @Test
    public void recoveryClassifiesSafeAndUnsafeActionsWithoutPersistingSpeechText() {
        MemoryPersistence persistence = new MemoryPersistence();
        CommandLedger ledger = new CommandLedger(persistence, 100L);
        String secret = "resident private speech that must not be stored";
        String safePayload = command("safe", "noop", "safe reason");
        String unsafePayload = command("unsafe", "speak", secret);
        ledger.accept(parse(safePayload), safePayload, 1_000L);
        ledger.accept(parse(unsafePayload), unsafePayload, 1_000L);

        List<CommandLedger.RecoveryItem> recovered = ledger.recover(1_050L);
        assertEquals(CommandLedger.RecoveryState.SAFE_RETRY, recovered.get(0).state());
        assertEquals(CommandLedger.RecoveryState.UNSAFE_RETRY, recovered.get(1).state());
        assertFalse(new Gson().toJson(recovered.get(1).record()).contains(secret));
        assertNotNull(recovered.get(1).record().payloadDigest);

        MemoryPersistence expiredPersistence = new MemoryPersistence();
        CommandLedger expiredLedger = new CommandLedger(expiredPersistence, 100L);
        expiredLedger.accept(parse(safePayload), safePayload, 1_000L);
        assertEquals(CommandLedger.RecoveryState.EXPIRED,
                expiredLedger.recover(1_101L).get(0).state());
    }

    @Test
    public void changedPayloadForExistingCommandIsRejected() {
        MemoryPersistence persistence = new MemoryPersistence();
        CommandLedger ledger = new CommandLedger(persistence);
        String first = command("conflict", "noop", "one");
        String changed = command("conflict", "noop", "two");
        ledger.accept(parse(first), first, 1L);
        assertEquals(CommandLedger.AcceptState.PAYLOAD_CONFLICT,
                ledger.accept(parse(changed), changed, 2L).state());
    }

    private static CanonicalCommandValidator.CanonicalCommand parse(String payload) {
        try {
            return CanonicalCommandValidator.validate(payload, ROBOT_ID);
        } catch (CanonicalCommandValidator.ValidationException e) {
            throw new AssertionError(e);
        }
    }

    private static String command(String id, String type, String value) {
        String action = "noop".equals(type)
                ? "\"reason\":\"" + value + "\""
                : "\"text\":\"" + value + "\",\"language\":\"zh-TW\"";
        return "{\"schema_version\":\"1.0\",\"command_id\":\"" + id
                + "\",\"event_id\":\"event-" + id + "\",\"robot_id\":\""
                + ROBOT_ID + "\",\"actions\":[{\"action_id\":\"action-"
                + id + "\",\"type\":\"" + type + "\"," + action + "}]}";
    }

    private static final class MemoryPersistence implements CommandLedger.Persistence {
        private CommandLedger.Snapshot snapshot = new CommandLedger.Snapshot();

        @Override
        public CommandLedger.Snapshot load() {
            return snapshot.copy();
        }

        @Override
        public boolean save(CommandLedger.Snapshot next) {
            snapshot = next.copy();
            return true;
        }

        private CommandLedger.Record record(String commandId) {
            return snapshot.records.get(commandId);
        }
    }
}
