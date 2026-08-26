package com.robotemi.agent.media.v11;

import android.os.Handler;
import android.os.Looper;

/**
 * Interface to dispatch UI actions onto the Android main thread, decoupled for testing.
 */
public interface MainThreadDispatcher {
    void post(Runnable action);
    boolean isMainThread();

    class Default implements MainThreadDispatcher {
        private final Handler handler;

        public Default() {
            this(new Handler(Looper.getMainLooper()));
        }

        public Default(Handler handler) {
            this.handler = handler;
        }

        @Override
        public void post(Runnable action) {
            if (action == null) return;
            if (isMainThread()) {
                action.run();
            } else {
                handler.post(action);
            }
        }

        @Override
        public boolean isMainThread() {
            try {
                return Looper.myLooper() == Looper.getMainLooper();
            } catch (Throwable ignored) {
                // In non-Android unit test environment without Looper mock
                return true;
            }
        }
    }

    class Direct implements MainThreadDispatcher {
        @Override
        public void post(Runnable action) {
            if (action != null) {
                action.run();
            }
        }

        @Override
        public boolean isMainThread() {
            return true;
        }
    }
}
