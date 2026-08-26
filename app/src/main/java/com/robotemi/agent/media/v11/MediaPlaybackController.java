package com.robotemi.agent.media.v11;

import android.net.Uri;
import android.widget.MediaController;
import android.widget.VideoView;

import java.util.UUID;

/**
 * Sole owner of VideoView playback operations for local and canonical media.
 * Command parsing, durable results, and MQTT remain outside this UI adapter.
 */
public final class MediaPlaybackController {
    public enum Origin { LOCAL, LEGACY_REMOTE, REMOTE_V11 }

    public interface Listener {
        void onPlaybackStarted(String sessionId, Origin origin);
        void onPlaybackCompleted(String sessionId, Origin origin);
        void onPlaybackFailed(String sessionId, Origin origin, String message);
        void onLocalUserStopped(String sessionId, Origin origin);
    }

    interface Player {
        void setCallbacks(Callbacks callbacks);
        void load(Uri uri);
        void start();
        void pause();
        void stop();
        boolean isPlaying();
        void show();
        void hide();
    }

    interface Callbacks {
        void onPrepared();
        void onCompleted();
        boolean onError(int what, int extra);
    }

    private final Player player;
    private final Listener listener;
    private final PlaybackStateMachine stateMachine = new PlaybackStateMachine();
    private Origin origin;

    public MediaPlaybackController(VideoView videoView, Listener listener) {
        this(new VideoViewPlayer(videoView), listener);
    }

    MediaPlaybackController(Player player, Listener listener) {
        this.player = player;
        this.listener = listener;
        player.setCallbacks(new Callbacks() {
            @Override
            public void onPrepared() {
                handlePrepared();
            }

            @Override
            public void onCompleted() {
                handleCompleted();
            }

            @Override
            public boolean onError(int what, int extra) {
                return handleError(what, extra);
            }
        });
    }

    private static void assertMainThread() {
        try {
            android.os.Looper current = android.os.Looper.myLooper();
            android.os.Looper main = android.os.Looper.getMainLooper();
            if (current != null && main != null && current != main) {
                throw new IllegalStateException("Only the original thread that created a view hierarchy can touch its views.");
            }
        } catch (Throwable ignored) {
            // JVM Unit testing environment without Android Looper
        }
    }

    public synchronized void startRemote(String sessionId, Uri uri) {
        assertMainThread();
        start(sessionId, uri, Origin.REMOTE_V11);
    }

    public synchronized void startLegacyRemote(String sessionId, Uri uri) {
        assertMainThread();
        start(sessionId, uri, Origin.LEGACY_REMOTE);
    }

    public synchronized void startLocal(Uri uri) {
        assertMainThread();
        start("local-" + UUID.randomUUID(), uri, Origin.LOCAL);
    }

    private void start(String sessionId, Uri uri, Origin playbackOrigin) {
        stateMachine.start(sessionId);
        origin = playbackOrigin;
        player.show();
        try {
            player.load(uri);
        } catch (RuntimeException e) {
            throw e;
        }
    }

    public synchronized void pauseRemote(String sessionId) {
        assertMainThread();
        stateMachine.pause(sessionId);
        player.pause();
    }

    public synchronized void resumeRemote(String sessionId) {
        assertMainThread();
        stateMachine.resume(sessionId);
        player.start();
    }

    public synchronized void pauseLocal() {
        assertMainThread();
        if (origin != Origin.LOCAL) {
            throw new IllegalStateException("active_playback_is_not_local");
        }
        pauseRemote(stateMachine.getSessionId());
    }

    public synchronized void resumeLocal() {
        assertMainThread();
        if (origin != Origin.LOCAL) {
            throw new IllegalStateException("active_playback_is_not_local");
        }
        resumeRemote(stateMachine.getSessionId());
    }

    public synchronized void stopRemote(String sessionId) {
        assertMainThread();
        stateMachine.beginStop(sessionId);
        player.stop();
        stateMachine.cancel(sessionId);
        player.hide();
        stateMachine.clearTerminal();
    }

    public synchronized void stopLegacyRemote(String sessionId) {
        assertMainThread();
        if (origin != Origin.LEGACY_REMOTE) {
            throw new IllegalStateException("active_playback_is_not_legacy_remote");
        }
        player.stop();
        stateMachine.cancel(sessionId);
        player.hide();
        stateMachine.clearTerminal();
    }

    public synchronized void localUserStop() {
        assertMainThread();
        if (!stateMachine.isActive()) {
            return;
        }
        String sessionId = stateMachine.getSessionId();
        Origin stoppedOrigin = origin;
        player.stop();
        stateMachine.cancel(sessionId);
        player.hide();
        listener.onLocalUserStopped(sessionId, stoppedOrigin);
        stateMachine.clearTerminal();
    }

    public synchronized boolean hasActivePlayback() {
        return stateMachine.isActive();
    }

    public synchronized String activeSessionId() {
        return stateMachine.isActive() ? stateMachine.getSessionId() : null;
    }

    public synchronized PlaybackStateMachine.State state() {
        return stateMachine.getState();
    }

    public synchronized Origin origin() {
        return origin;
    }

    private synchronized void handlePrepared() {
        if (stateMachine.getState() != PlaybackStateMachine.State.STARTING) {
            return;
        }
        String sessionId = stateMachine.getSessionId();
        player.start();
        stateMachine.started(sessionId);
        listener.onPlaybackStarted(sessionId, origin);
    }

    private synchronized void handleCompleted() {
        if (stateMachine.getState() != PlaybackStateMachine.State.PLAYING) {
            return;
        }
        String sessionId = stateMachine.getSessionId();
        Origin completedOrigin = origin;
        if (stateMachine.complete(sessionId)) {
            player.hide();
            listener.onPlaybackCompleted(sessionId, completedOrigin);
            stateMachine.clearTerminal();
        }
    }

    private synchronized boolean handleError(int what, int extra) {
        if (!stateMachine.isActive()) {
            return true;
        }
        failCurrent("media_error_" + what + "_" + extra);
        return true;
    }

    private void failCurrent(String message) {
        String sessionId = stateMachine.getSessionId();
        Origin failedOrigin = origin;
        if (stateMachine.fail(sessionId)) {
            player.hide();
            listener.onPlaybackFailed(sessionId, failedOrigin, message);
            stateMachine.clearTerminal();
        }
    }

    private static final class VideoViewPlayer implements Player {
        private final VideoView videoView;

        private VideoViewPlayer(VideoView videoView) {
            this.videoView = videoView;
            MediaController mediaController = new MediaController(videoView.getContext());
            mediaController.setAnchorView(videoView);
            videoView.setMediaController(mediaController);
        }

        @Override
        public void setCallbacks(Callbacks callbacks) {
            videoView.setOnPreparedListener(player -> callbacks.onPrepared());
            videoView.setOnCompletionListener(player -> callbacks.onCompleted());
            videoView.setOnErrorListener(
                    (player, what, extra) -> callbacks.onError(what, extra));
        }

        @Override
        public void load(Uri uri) {
            videoView.setVideoURI(uri);
        }

        @Override
        public void start() {
            videoView.start();
        }

        @Override
        public void pause() {
            videoView.pause();
        }

        @Override
        public void stop() {
            videoView.stopPlayback();
        }

        @Override
        public boolean isPlaying() {
            return videoView.isPlaying();
        }

        @Override
        public void show() {
            videoView.setVisibility(VideoView.VISIBLE);
        }

        @Override
        public void hide() {
            videoView.setVisibility(VideoView.GONE);
        }
    }
}
