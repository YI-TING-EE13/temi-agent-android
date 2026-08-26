package com.robotemi.agent.identity;

import android.os.SystemClock;

/** Android production clock. */
public final class SystemIdentityClock implements IdentityClock {
    @Override
    public long wallTimeMillis() {
        return System.currentTimeMillis();
    }

    @Override
    public long monotonicTimeMillis() {
        return SystemClock.elapsedRealtime();
    }
}
