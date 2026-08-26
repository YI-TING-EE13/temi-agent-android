package com.robotemi.agent.identity;

/** Separates producer wall-clock freshness from local monotonic expiry. */
public interface IdentityClock {
    long wallTimeMillis();
    long monotonicTimeMillis();
}
