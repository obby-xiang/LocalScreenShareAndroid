package com.obby.android.localscreenshare.client;

/**
 * Rate limiter for outbound key-frame requests.
 *
 * <p>A decode failure, a queue overflow or a stalled decoder can each ask for a fresh random-access
 * point, and a congested link produces those events in bursts. The server coalesces outstanding
 * requests, but every request still costs a message, so the client allows at most one per interval.
 * The first request always passes; a request that arrives before the interval elapsed is rejected
 * without remembering it, so the next attempt after the interval is not starved.</p>
 *
 * <p>Thread-safe: the client raises requests from the transport thread and the viewer service from
 * the main thread. A clock that moved backwards is treated as a fresh start instead of blocking
 * requests until the clock catches up.</p>
 */
public final class KeyFrameRequestThrottle {

    /**
     * Default minimum spacing between two requests, in milliseconds.
     */
    public static final long DEFAULT_MIN_INTERVAL_MS = 1000L;

    private final long minIntervalMs;

    private boolean hasRequested;

    private long lastRequestMs;

    /**
     * Creates a throttle with the default one-second spacing.
     */
    public KeyFrameRequestThrottle() {
        this(DEFAULT_MIN_INTERVAL_MS);
    }

    /**
     * Creates a throttle with an explicit spacing.
     *
     * @param minIntervalMs minimum spacing in milliseconds; must be positive
     * @throws IllegalArgumentException if {@code minIntervalMs} is not positive
     */
    public KeyFrameRequestThrottle(final long minIntervalMs) {
        if (minIntervalMs <= 0L) {
            throw new IllegalArgumentException("minIntervalMs must be positive");
        }
        this.minIntervalMs = minIntervalMs;
    }

    /**
     * Returns whether a request may be sent now, and records it when it may.
     *
     * @param nowMs current monotonic time in milliseconds
     * @return true when the caller should send the request
     */
    public synchronized boolean tryAcquire(final long nowMs) {
        if (hasRequested && nowMs >= lastRequestMs && nowMs - lastRequestMs < minIntervalMs) {
            return false;
        }
        hasRequested = true;
        lastRequestMs = nowMs;
        return true;
    }
}
