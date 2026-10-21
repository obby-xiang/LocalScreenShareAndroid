package com.obby.android.localscreenshare.core.util;

/**
 * Monotonic nanosecond time source for pipeline probes.
 *
 * <p>Only differences between returned values are meaningful, and the origin is arbitrary.
 * Implementations must never go backwards, because the value is used for stage latencies,
 * queue residency and round-trip measurements. The clock is separate from
 * {@link TimeSource} on purpose: {@code TimeSource} stays a millisecond clock for the queue
 * and session machinery, while probes need sub-millisecond resolution and a value that can be
 * compared with media timestamps.</p>
 *
 * <p>Production code uses {@link #MONOTONIC}; tests inject a controllable implementation so a
 * stage latency can be asserted exactly instead of only being non-negative.</p>
 */
public interface NanoClock {

    /**
     * Production clock: {@link System#nanoTime()}.
     */
    NanoClock MONOTONIC = System::nanoTime;

    /**
     * Returns the current monotonic time in nanoseconds.
     */
    long nowNs();
}
