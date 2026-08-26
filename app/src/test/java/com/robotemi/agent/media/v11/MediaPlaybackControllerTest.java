package com.robotemi.agent.media.v11;

import android.net.Uri;

import org.junit.Test;

import static org.junit.Assert.*;

public class MediaPlaybackControllerTest {
    @Test
    public void remoteAndLocalUseSameControllerWithoutForgingLocalResults() {
        FakePlayer player = new FakePlayer();
        FakeListener listener = new FakeListener();
        MediaPlaybackController controller = new MediaPlaybackController(player, listener);

        controller.startRemote("remote-session", (Uri) null);
        player.callbacks.onPrepared();
        assertEquals("remote-session", listener.started);
        controller.pauseRemote("remote-session");
        controller.resumeRemote("remote-session");
        controller.localUserStop();
        assertEquals("remote-session", listener.localStopped);

        MediaPlaybackController localController =
                new MediaPlaybackController(new FakePlayer(), listener);
        localController.startLocal(null);
        localController.localUserStop();
        assertEquals(1, listener.remoteStopNotifications);
        assertEquals(1, listener.localStopNotifications);
    }

    @Test
    public void completionAndFailureAreDeliveredOnlyOnce() {
        FakePlayer player = new FakePlayer();
        FakeListener listener = new FakeListener();
        MediaPlaybackController controller = new MediaPlaybackController(player, listener);
        controller.startRemote("s", null);
        player.callbacks.onPrepared();
        player.callbacks.onCompleted();
        player.callbacks.onCompleted();
        assertEquals(1, listener.completions);

        FakePlayer failingPlayer = new FakePlayer();
        MediaPlaybackController failing =
                new MediaPlaybackController(failingPlayer, listener);
        failing.startRemote("f", null);
        assertTrue(failingPlayer.callbacks.onError(1, 2));
        assertTrue(failingPlayer.callbacks.onError(1, 2));
        assertEquals(1, listener.failures);
    }

    private static final class FakePlayer implements MediaPlaybackController.Player {
        MediaPlaybackController.Callbacks callbacks;

        @Override public void setCallbacks(MediaPlaybackController.Callbacks value) {
            callbacks = value;
        }
        @Override public void load(Uri uri) {}
        @Override public void start() {}
        @Override public void pause() {}
        @Override public void stop() {}
        @Override public boolean isPlaying() { return false; }
        @Override public void show() {}
        @Override public void hide() {}
    }

    private static final class FakeListener implements MediaPlaybackController.Listener {
        String started;
        String localStopped;
        int completions;
        int failures;
        int remoteStopNotifications;
        int localStopNotifications;

        @Override
        public void onPlaybackStarted(
                String sessionId, MediaPlaybackController.Origin origin) {
            started = sessionId;
        }

        @Override
        public void onPlaybackCompleted(
                String sessionId, MediaPlaybackController.Origin origin) {
            completions++;
        }

        @Override
        public void onPlaybackFailed(
                String sessionId, MediaPlaybackController.Origin origin, String message) {
            failures++;
        }

        @Override
        public void onLocalUserStopped(
                String sessionId, MediaPlaybackController.Origin origin) {
            localStopped = sessionId;
            if (origin == MediaPlaybackController.Origin.LOCAL) {
                localStopNotifications++;
            } else {
                remoteStopNotifications++;
            }
        }
    }
}
