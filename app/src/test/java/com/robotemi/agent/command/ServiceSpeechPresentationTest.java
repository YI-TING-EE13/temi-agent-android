package com.robotemi.agent.command;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

/** Regression coverage for the service-owned reply presentation in issue #31. */
public class ServiceSpeechPresentationTest {
    @Test
    public void speechIsPresentedBeforeCallbackAndDuplicateCompletionIsIgnored() {
        Fixture f = new Fixture();
        f.runtime.attachListener(f.listener);
        f.begin();
        assertEquals(Arrays.asList("speech"), f.events);
        f.runtime.onTtsStatusChanged(f.id, true);
        f.runtime.onTtsStatusChanged(f.id, true);
        assertEquals(Arrays.asList("speech", "completed"), f.events);
        assertFalse(f.runtime.isPending());
    }

    @Test
    public void reattachDuringPlaybackReplaysTextAndRequestCorrelation() {
        Fixture f = new Fixture();
        f.begin();
        f.runtime.attachListener(f.listener);
        assertEquals(Arrays.asList("speech"), f.events);
        f.runtime.detachListener();
        f.runtime.onTtsStatusChanged(f.id, true);
        f.events.clear();
        f.runtime.attachListener(f.listener);
        assertEquals(Arrays.asList("speech", "completed"), f.events);
        f.events.clear();
        f.runtime.attachListener(f.listener);
        assertTrue(f.events.isEmpty());
    }

    @Test
    public void detachedTimeoutReplaysPresentationBeforeFailure() {
        Fixture f = new Fixture();
        f.begin();
        f.timeout.run();
        f.runtime.attachListener(f.listener);
        assertEquals(Arrays.asList("speech", "failed"), f.events);
        assertFalse(f.runtime.isPending());
    }

    @Test
    public void cancelledSpeechIsNotReplayed() {
        Fixture f = new Fixture();
        f.begin();
        f.runtime.cancelTts(f.id);
        f.runtime.attachListener(f.listener);
        assertTrue(f.events.isEmpty());
    }

    private static final class Fixture {
        final UUID id = UUID.randomUUID();
        final List<String> events = new ArrayList<>();
        Runnable timeout;
        final CanonicalCommandRuntime runtime = new CanonicalCommandRuntime((task, delay) -> {
            timeout = task;
            return () -> {};
        });
        final CanonicalCommandRuntime.Listener listener = new CanonicalCommandRuntime.Listener() {
            @Override public void onTtsStarted(CanonicalCommandRuntime.SpeechPresentation speech) {
                assertEquals(id, speech.requestId);
                assertEquals("event-31", speech.eventId);
                assertEquals("AI reply", speech.text);
                assertTrue(speech.continueListening);
                events.add("speech");
            }
            @Override public void onTtsResolved(CanonicalCommandRuntime.Resolution resolution) {
                assertEquals(id, resolution.getRequestId());
                events.add(resolution.getStatus());
            }
        };
        void begin() {
            assertTrue(runtime.beginTtsWithPresentation(id, "command-31", "event-31",
                    "robot-test", "action-31", "speak", true,
                    new CanonicalCommandRuntime.SpeechPresentation(
                            id, "event-31", "AI reply", true)));
        }
    }
}
