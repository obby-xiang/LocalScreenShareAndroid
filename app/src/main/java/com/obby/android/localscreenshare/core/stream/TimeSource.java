package com.obby.android.localscreenshare.core.stream;

/**
 * Monotonic millisecond time source.
 *
 * <p>Only differences between returned values are meaningful; the origin is arbitrary.
 * Implementations must never go backwards, because the value drives join-point rate limiting and
 * the residency bound of buffered access units. Tests inject a controllable implementation;
 * production code uses {@link #MONOTONIC}.</p>
 */
public interface TimeSource {

    /** Production clock: monotonic milliseconds derived from {@code System.nanoTime()}. */
    TimeSource MONOTONIC = () -> System.nanoTime() / 1_000_000L;

    /** Returns the current time in milliseconds; non-decreasing between calls. */
    long nowMs();
}
