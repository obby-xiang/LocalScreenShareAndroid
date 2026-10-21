package com.obby.android.localscreenshare.core.stream;

import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;

/**
 * Result of polling a bounded access-unit queue.
 *
 * <p>Exactly one of three outcomes: a unit to send, an empty queue, or a resync signal - no unit
 * and {@link #isResyncRequired()} true, meaning the queue discarded its contents because the
 * decode chain was broken and the caller must wait for a join point. Instances are immutable.</p>
 *
 * <p><b>Queue residency.</b> A unit result also carries the millisecond timestamp the unit was
 * enqueued at, which is what lets the send session measure how long the unit waited. The value is
 * {@link #UNKNOWN_ENQUEUED_MS} for an empty or resync result and for callers that construct a
 * result without a timestamp, so a probe can skip the sample instead of inventing one.</p>
 */
public final class QueuePollResult {

    /**
     * Enqueue timestamp marker for a result that carries no unit timing.
     */
    public static final long UNKNOWN_ENQUEUED_MS = Long.MIN_VALUE;

    private final EncodedAccessUnit accessUnit;
    private final boolean resyncRequired;
    private final long enqueuedMs;

    private QueuePollResult(EncodedAccessUnit accessUnit, boolean resyncRequired,
                            long enqueuedMs) {
        this.accessUnit = accessUnit;
        this.resyncRequired = resyncRequired;
        this.enqueuedMs = enqueuedMs;
    }

    /**
     * Returns the unit to send, or {@code null} for an empty or resync result.
     */
    public EncodedAccessUnit getAccessUnit() {
        return accessUnit;
    }

    /**
     * Returns whether the queue discarded its contents and now needs a join point.
     */
    public boolean isResyncRequired() {
        return resyncRequired;
    }

    /**
     * Returns the time the unit was enqueued at, in milliseconds, or
     * {@link #UNKNOWN_ENQUEUED_MS} when the result carries no such information.
     */
    public long getEnqueuedMs() {
        return enqueuedMs;
    }

    /**
     * Creates a result carrying a unit without its enqueue timestamp.
     *
     * <p>Kept for callers that do not measure queue residency; the queue itself always uses
     * {@link #frame(EncodedAccessUnit, long)}.</p>
     *
     * @throws IllegalArgumentException if {@code accessUnit} is null
     */
    public static QueuePollResult frame(EncodedAccessUnit accessUnit) {
        return frame(accessUnit, UNKNOWN_ENQUEUED_MS);
    }

    /**
     * Creates a result carrying a unit and the time it was enqueued at.
     *
     * @param accessUnit unit polled from the queue; must not be null
     * @param enqueuedMs enqueue timestamp in milliseconds, or {@link #UNKNOWN_ENQUEUED_MS}
     * @throws IllegalArgumentException if {@code accessUnit} is null
     */
    public static QueuePollResult frame(EncodedAccessUnit accessUnit, long enqueuedMs) {
        if (accessUnit == null) {
            throw new IllegalArgumentException("access unit must not be null");
        }
        return new QueuePollResult(accessUnit, false, enqueuedMs);
    }

    /**
     * Creates the signal that the queue dropped its contents and needs a join point.
     */
    public static QueuePollResult resync() {
        return new QueuePollResult(null, true, UNKNOWN_ENQUEUED_MS);
    }

    /**
     * Creates the result for a queue with nothing to send.
     */
    public static QueuePollResult empty() {
        return new QueuePollResult(null, false, UNKNOWN_ENQUEUED_MS);
    }
}
