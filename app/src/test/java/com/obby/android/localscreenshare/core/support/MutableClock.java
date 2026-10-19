package com.obby.android.localscreenshare.core.support;

import com.obby.android.localscreenshare.core.stream.TimeSource;

/**
 * Deterministic JVM clock for queue, runtime, and session tests.
 *
 * <p>Time changes only through {@link #advance(long)} and {@link #set(long)}, so a test can freeze
 * a deadline and move past it exactly. Not thread-safe.</p>
 */
public final class MutableClock implements TimeSource {

    private long nowMs;

    public MutableClock() {
        this(0L);
    }

    public MutableClock(long initialNowMs) {
        this.nowMs = initialNowMs;
    }

    @Override
    public long nowMs() {
        return nowMs;
    }

    public void advance(long deltaMs) {
        nowMs += deltaMs;
    }

    public void set(long nowMs) {
        this.nowMs = nowMs;
    }
}
