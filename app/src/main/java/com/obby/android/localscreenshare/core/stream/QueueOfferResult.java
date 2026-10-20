package com.obby.android.localscreenshare.core.stream;

/**
 * Outcome of attempting to offer an access unit into a bounded queue.
 *
 * <p>The queue itself produces the first five values; {@code REJECTED_NOT_READY},
 * {@code REJECTED_EPOCH} and {@code REJECTED_CLOSED} are reserved for the
 * {@code ClientSendSession} layer.</p>
 */
public enum QueueOfferResult {
    /**
     * Queued within every limit.
     */
    ACCEPTED,
    /**
     * Queued after the independent-frame policy dropped older units to keep this one.
     */
    ACCEPTED_KEPT_LATEST,
    /**
     * Accepted as the join point that ends a resync; older entries were cleared first.
     */
    ACCEPTED_AFTER_RESYNC,
    /**
     * Rejected because the queue is waiting for a join point and this unit is not one.
     */
    REJECTED_NOT_JOIN_POINT,
    /**
     * Rejected because the payload alone exceeds the byte limit; it is never retained.
     */
    REJECTED_TOO_LARGE,
    /**
     * Session-level: no descriptor has been accepted yet.
     */
    REJECTED_NOT_READY,
    /**
     * Session-level: the unit belongs to another stream or another config epoch.
     */
    REJECTED_EPOCH,
    /**
     * Session-level: the session was detached.
     */
    REJECTED_CLOSED
}
