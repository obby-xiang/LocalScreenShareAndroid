package com.obby.android.localscreenshare.core.stream;

/**
 * Outcome of attempting to offer an access unit into a bounded queue.
 *
 * <p>The queue itself produces the first five values; {@code REJECTED_NOT_READY},
 * {@code REJECTED_EPOCH} and {@code REJECTED_CLOSED} are reserved for the
 * {@code ClientSendSession} layer.</p>
 */
public enum QueueOfferResult {
    ACCEPTED,
    ACCEPTED_KEPT_LATEST,
    ACCEPTED_AFTER_RESYNC,
    REJECTED_NOT_JOIN_POINT,
    REJECTED_TOO_LARGE,
    REJECTED_NOT_READY,
    REJECTED_EPOCH,
    REJECTED_CLOSED
}
