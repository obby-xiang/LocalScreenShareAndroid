package com.obby.android.localscreenshare.core.support;

import com.obby.android.localscreenshare.core.util.NanoClock;

/**
 * Controllable monotonic clock for JVM tests.
 */
public final class MutableAudioClock implements NanoClock {

    private long nowNs;

    /**
     * Creates a clock positioned at {@code startNs}.
     */
    public MutableAudioClock(long startNs) {
        this.nowNs = startNs;
    }

    /**
     * Advances the clock by {@code deltaMs} milliseconds.
     */
    public void advanceMs(long deltaMs) {
        nowNs += deltaMs * 1_000_000L;
    }

    /**
     * Sets the clock to an absolute value in nanoseconds.
     */
    public void setNs(long value) {
        nowNs = value;
    }

    @Override
    public long nowNs() {
        return nowNs;
    }
}
