package com.obby.android.localscreenshare.core.stream;

import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;

/**
 * Result of polling a bounded access-unit queue.
 *
 * <p>Exactly one of three outcomes: a unit to send, an empty queue, or a resync signal - no unit
 * and {@link #isResyncRequired()} true, meaning the queue discarded its contents because the
 * decode chain was broken and the caller must wait for a join point. Instances are immutable.</p>
 */
public final class QueuePollResult {

    private final EncodedAccessUnit accessUnit;
    private final boolean resyncRequired;

    private QueuePollResult(EncodedAccessUnit accessUnit, boolean resyncRequired) {
        this.accessUnit = accessUnit;
        this.resyncRequired = resyncRequired;
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
     * Creates a result carrying a unit.
     *
     * @throws IllegalArgumentException if {@code accessUnit} is null
     */
    public static QueuePollResult frame(EncodedAccessUnit accessUnit) {
        if (accessUnit == null) {
            throw new IllegalArgumentException("access unit must not be null");
        }
        return new QueuePollResult(accessUnit, false);
    }

    /**
     * Creates the signal that the queue dropped its contents and needs a join point.
     */
    public static QueuePollResult resync() {
        return new QueuePollResult(null, true);
    }

    /**
     * Creates the result for a queue with nothing to send.
     */
    public static QueuePollResult empty() {
        return new QueuePollResult(null, false);
    }
}
