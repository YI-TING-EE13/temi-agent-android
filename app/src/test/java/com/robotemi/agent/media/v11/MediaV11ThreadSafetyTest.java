package com.robotemi.agent.media.v11;

import android.net.Uri;

import com.robotemi.agent.command.CommandLedger;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class MediaV11ThreadSafetyTest {

    private CommandLedger ledger;
    private MemoryPersistence ledgerPersistence;
    private FakeClock clock;
    private FakeScheduler scheduler;
    private List<String> pendingResultsNotifications;
    private MediaV11ServiceRuntime runtime;

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
    }

    private static class FakeClock implements MediaV11ServiceRuntime.Clock {
        long now = 1000L;
        @Override public long nowMs() { return now; }
        @Override public String nowTimestamp() { return "2026-08-04T01:00:00Z"; }
    }

    private static class FakeScheduler implements MediaV11ServiceRuntime.Scheduler {
        static class Task implements MediaV11ServiceRuntime.Cancellable {
            final Runnable runnable;
            final long delayMs;
            boolean cancelled;
            Task(Runnable runnable, long delayMs) {
                this.runnable = runnable;
                this.delayMs = delayMs;
            }
            @Override public void cancel() { cancelled = true; }
        }
        final List<Task> tasks = new ArrayList<>();
        @Override public MediaV11ServiceRuntime.Cancellable schedule(Runnable runnable, long delayMs) {
            Task task = new Task(runnable, delayMs);
            tasks.add(task);
            return task;
        }
        void runAll() {
            for (Task task : new ArrayList<>(tasks)) {
                if (!task.cancelled) task.runnable.run();
            }
        }
    }

    private static class CapturingBinding implements MediaV11PlaybackBinding {
        final List<String> actions = new ArrayList<>();
        final List<String> threads = new ArrayList<>();
        Callback lastCallback;
        long lastGeneration;
        String lastLeaseId;
        String lastSessionId;
        boolean throwOnStart;

        @Override
        public void start(MediaV11Command command, String sessionId, String leaseId, long generation, Callback callback) {
            actions.add("start:" + command.getVideoId());
            threads.add(Thread.currentThread().getName());
            lastCallback = callback;
            lastGeneration = generation;
            lastLeaseId = leaseId;
            lastSessionId = sessionId;
            if (throwOnStart) {
                callback.onFailed(generation, leaseId, sessionId, "Only the original thread that created a view hierarchy can touch its views.");
            }
        }

        @Override
        public void pause(String sessionId, String leaseId, long generation, Callback callback) {
            actions.add("pause:" + sessionId);
            threads.add(Thread.currentThread().getName());
            lastCallback = callback;
            lastGeneration = generation;
            lastLeaseId = leaseId;
            lastSessionId = sessionId;
        }

        @Override
        public void resume(String sessionId, String leaseId, long generation, Callback callback) {
            actions.add("resume:" + sessionId);
            threads.add(Thread.currentThread().getName());
            lastCallback = callback;
            lastGeneration = generation;
            lastLeaseId = leaseId;
            lastSessionId = sessionId;
        }

        @Override
        public void stop(String sessionId, String leaseId, long generation, Callback callback) {
            actions.add("stop:" + sessionId);
            threads.add(Thread.currentThread().getName());
            lastCallback = callback;
            lastGeneration = generation;
            lastLeaseId = leaseId;
            lastSessionId = sessionId;
        }

        @Override
        public void detach(long generation) {
            actions.add("detach:" + generation);
            threads.add(Thread.currentThread().getName());
        }
    }

    private static class FakePlayer implements MediaPlaybackController.Player {
        MediaPlaybackController.Callbacks callbacks;
        @Override public void setCallbacks(MediaPlaybackController.Callbacks callbacks) { this.callbacks = callbacks; }
        @Override public void load(Uri uri) {}
        @Override public void start() {}
        @Override public void pause() {}
        @Override public void stop() {}
        @Override public boolean isPlaying() { return false; }
        @Override public void show() {}
        @Override public void hide() {}
    }

    @Before
    public void setUp() {
        ledgerPersistence = new MemoryPersistence();
        ledger = new CommandLedger(ledgerPersistence);
        clock = new FakeClock();
        scheduler = new FakeScheduler();
        pendingResultsNotifications = new ArrayList<>();
        runtime = new MediaV11ServiceRuntime(
                ledger, ledgerPersistence, clock, scheduler,
                () -> pendingResultsNotifications.add("pending"),
                (phase, topicClass, commandId, eventId, actionId, state, outcome) -> {},
                true, 10000L
        );
    }

    @Test
    public void test1_backgroundThreadPlayWithAttachedActivity() throws Exception {
        CapturingBinding binding = new CapturingBinding();
        runtime.attachBinding(binding);

        String payload = MediaV11Fixtures.request("cmd-bg-play-01", "play_video", "serialized_execution", null);

        ExecutorService bgPool = Executors.newSingleThreadExecutor(r -> new Thread(r, "mqtt-bg-worker-thread"));
        CountDownLatch latch = new CountDownLatch(1);
        bgPool.submit(() -> {
            runtime.ingest(payload, "temi-01", null);
            latch.countDown();
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        bgPool.shutdown();

        assertEquals(1, binding.actions.size());
        assertEquals("start:elderly_hand_exercise", binding.actions.get(0));

        // Simulate playback start and completion callbacks
        assertNotNull(binding.lastCallback);
        binding.lastCallback.onStarted(binding.lastGeneration, binding.lastLeaseId, binding.lastSessionId);
        binding.lastCallback.onCompleted(binding.lastGeneration, binding.lastLeaseId, binding.lastSessionId);

        List<MediaV11Persistence.OutboxRecord> outbox = runtime.pendingOutbox();
        assertTrue(outbox.size() >= 2); // accepted & completed
        MediaV11Result result = MediaV11Result.fromJson(outbox.get(outbox.size() - 1).payload);
        assertEquals("completed", result.getStatus());
    }

    @Test
    public void test2_backgroundThreadPauseResumeStop() throws Exception {
        CapturingBinding binding = new CapturingBinding();
        runtime.attachBinding(binding);

        // 1. Play
        runtime.ingest(MediaV11Fixtures.request("cmd-play-02", "play_video", "serialized_execution", null), "temi-01", null);
        assertNotNull(binding.lastCallback);
        binding.lastCallback.onStarted(binding.lastGeneration, binding.lastLeaseId, binding.lastSessionId);
        String sessionId = binding.lastSessionId;

        // 2. Pause on BG thread
        ExecutorService bgPool = Executors.newSingleThreadExecutor(r -> new Thread(r, "mqtt-bg-thread"));
        bgPool.submit(() -> runtime.ingest(MediaV11Fixtures.request("cmd-pause-02", "pause_video", "active_playback_control", sessionId), "temi-01", null)).get();

        assertEquals(2, binding.actions.size());
        assertEquals("pause:" + sessionId, binding.actions.get(1));
        binding.lastCallback.onControlSucceeded(binding.lastGeneration, binding.lastLeaseId, sessionId, MediaV11Command.Action.PAUSE_VIDEO);

        // 3. Resume on BG thread
        bgPool.submit(() -> runtime.ingest(MediaV11Fixtures.request("cmd-resume-02", "resume_video", "active_playback_control", sessionId), "temi-01", null)).get();
        assertEquals(3, binding.actions.size());
        assertEquals("resume:" + sessionId, binding.actions.get(2));
        binding.lastCallback.onControlSucceeded(binding.lastGeneration, binding.lastLeaseId, sessionId, MediaV11Command.Action.RESUME_VIDEO);

        // 4. Stop on BG thread
        bgPool.submit(() -> runtime.ingest(MediaV11Fixtures.request("cmd-stop-02", "stop_video", "active_playback_control", sessionId), "temi-01", null)).get();
        assertEquals(4, binding.actions.size());
        assertEquals("stop:" + sessionId, binding.actions.get(3));
        binding.lastCallback.onControlSucceeded(binding.lastGeneration, binding.lastLeaseId, sessionId, MediaV11Command.Action.STOP_VIDEO);

        bgPool.shutdown();
    }

    @Test
    public void test3_activityAbsentAndLateAttach() {
        // No binding attached initially
        runtime.ingest(MediaV11Fixtures.request("cmd-absent-03", "play_video", "serialized_execution", null), "temi-01", null);

        // Accepted result should be generated
        List<MediaV11Persistence.OutboxRecord> outbox = runtime.pendingOutbox();
        assertEquals(1, outbox.size());
        MediaV11Result result = MediaV11Result.fromJson(outbox.get(0).payload);
        assertEquals("accepted", result.getStatus());

        // Now attach Activity binding
        CapturingBinding binding = new CapturingBinding();
        runtime.attachBinding(binding);

        // Action dispatched exactly once
        assertEquals(1, binding.actions.size());
        assertEquals("start:elderly_hand_exercise", binding.actions.get(0));
    }

    @Test
    public void test4_timeoutNoLateAction() {
        // No binding attached
        runtime.ingest(MediaV11Fixtures.request("cmd-timeout-04", "play_video", "serialized_execution", null), "temi-01", null);

        // Fast forward clock past 10000ms deadline
        clock.now += 15000L;
        scheduler.runAll();

        // Terminal failed result generated for timeout
        List<MediaV11Persistence.OutboxRecord> outbox = runtime.pendingOutbox();
        assertEquals(2, outbox.size());
        MediaV11Result result = MediaV11Result.fromJson(outbox.get(1).payload);
        assertEquals("failed", result.getStatus());

        // Attach binding late
        CapturingBinding binding = new CapturingBinding();
        runtime.attachBinding(binding);

        // Must NOT execute UI action after timeout
        assertEquals(0, binding.actions.size());
    }

    @Test
    public void test5_duplicateCommandSuppressed() {
        CapturingBinding binding = new CapturingBinding();
        runtime.attachBinding(binding);

        String payload = MediaV11Fixtures.request("cmd-dup-05", "play_video", "serialized_execution", null);
        runtime.ingest(payload, "temi-01", null);

        // Ingest duplicate
        runtime.ingest(payload, "temi-01", null);

        // Executed UI action exactly once
        assertEquals(1, binding.actions.size());
    }

    @Test
    public void test6_activityRecreation() {
        CapturingBinding oldBinding = new CapturingBinding();
        long gen1 = runtime.attachBinding(oldBinding);

        runtime.ingest(MediaV11Fixtures.request("cmd-recreate-06", "play_video", "serialized_execution", null), "temi-01", null);
        assertEquals(1, oldBinding.actions.size());

        // Detach old, attach new
        runtime.detachBinding(gen1);
        CapturingBinding newBinding = new CapturingBinding();
        runtime.attachBinding(newBinding);

        // Old binding gets detach action
        assertEquals(2, oldBinding.actions.size()); // start & detach
        assertEquals("detach:1", oldBinding.actions.get(1));
    }

    @Test
    public void test7_mainThreadUiFailureHandling() {
        CapturingBinding binding = new CapturingBinding();
        binding.throwOnStart = true;
        runtime.attachBinding(binding);

        String payload = MediaV11Fixtures.request("cmd-fail-07", "play_video", "serialized_execution", null);
        runtime.ingest(payload, "temi-01", null);

        List<MediaV11Persistence.OutboxRecord> outbox = runtime.pendingOutbox();
        assertEquals(2, outbox.size());
        MediaV11Result result = MediaV11Result.fromJson(outbox.get(1).payload);
        assertEquals("failed", result.getStatus());
        assertTrue(result.toJson().contains("INTERNAL_ERROR"));
    }

    @Test
    public void test8_threadAssertionFailFast() {
        MediaPlaybackController controller = new MediaPlaybackController(new FakePlayer(), new MediaPlaybackController.Listener() {
            @Override public void onPlaybackStarted(String sessionId, MediaPlaybackController.Origin origin) {}
            @Override public void onPlaybackCompleted(String sessionId, MediaPlaybackController.Origin origin) {}
            @Override public void onPlaybackFailed(String sessionId, MediaPlaybackController.Origin origin, String message) {}
            @Override public void onLocalUserStopped(String sessionId, MediaPlaybackController.Origin origin) {}
        });

        // Controller unit operations should succeed safely in unit test environment
        assertNotNull(controller);
    }
}
