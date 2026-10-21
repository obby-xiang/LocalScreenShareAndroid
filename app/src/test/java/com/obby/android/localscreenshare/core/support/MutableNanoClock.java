package com.obby.android.localscreenshare.core.support;

import com.obby.android.localscreenshare.core.util.NanoClock;

/**
 * Deterministic JVM nanosecond clock for pipeline probe tests.
 *
 * <p>Time changes only through {@link #advance(long)} and {@link #set(long)}, so a probe's stage
 * latency can be asserted exactly instead of only being positive. Not thread-safe.</p>
 */
public final class MutableNanoClock implements NanoClock {

    private long nowNs;

    public MutableNanoClock() {
        this(0L);
    }

    public MutableNanoClock(long initialNowNs) {
        this.nowNs = initialNowNs;
    }

    @Override
    public long nowNs() {
        return nowNs;
    }

    public void advance(long deltaNs) {
        nowNs += deltaNs;
    }

    public void set(long nowNs) {
        this.nowNs = nowNs;
    }
}
