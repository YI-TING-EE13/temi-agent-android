package com.robotemi.agent.mqtt;

import com.robotemi.agent.command.CanonicalCommandValidator.CanonicalCommand;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CanonicalCommandIngressTest {
    @Test
    public void validCanonicalSpeakIsValidatedAndDelegatedOnce() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<CanonicalCommand> received = new AtomicReference<>();
        CanonicalCommandIngress ingress = new CanonicalCommandIngress((command, payload) -> {
            calls.incrementAndGet();
            received.set(command);
            return true;
        });

        assertTrue(ingress.ingest(speakPayload("event-ingress", "cmd-ingress"), "temi-01"));

        assertEquals(1, calls.get());
        assertEquals("cmd-ingress", received.get().getCommandId());
        assertEquals("speak", received.get().getActions().get(0).getType());
    }

    @Test
    public void invalidCanonicalPayloadDoesNotDelegate() {
        AtomicInteger calls = new AtomicInteger();
        CanonicalCommandIngress ingress = new CanonicalCommandIngress((command, payload) ->
                incrementAndReturn(calls));

        assertFalse(ingress.ingest("{\"schema_version\":\"1.0\"}", "temi-01"));
        assertEquals(0, calls.get());
    }

    @Test(expected = IllegalStateException.class)
    public void delegateExceptionIsNotClassifiedAsInvalid() {
        CanonicalCommandIngress ingress = new CanonicalCommandIngress((command, payload) -> {
            throw new IllegalStateException("delegate_failure");
        });

        ingress.ingest(speakPayload("event-delegate", "cmd-delegate"), "temi-01");
    }

    private static boolean incrementAndReturn(AtomicInteger calls) {
        calls.incrementAndGet();
        return true;
    }

    static String speakPayload(String eventId, String commandId) {
        return "{\"schema_version\":\"1.0\","
                + "\"command_id\":\"" + commandId + "\","
                + "\"event_id\":\"" + eventId + "\","
                + "\"robot_id\":\"temi-01\","
                + "\"actions\":[{\"action_id\":\"act-1\","
                + "\"type\":\"speak\",\"text\":\"hello\"}]}";
    }
}
