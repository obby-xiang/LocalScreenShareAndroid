package com.obby.android.localscreenshare.core.stream;

/**
 * Monotonic millisecond time source.
 */
public interface TimeSource {

    TimeSource MONOTONIC = () -> System.nanoTime() / 1_000_000L;

    long nowMs();
}
