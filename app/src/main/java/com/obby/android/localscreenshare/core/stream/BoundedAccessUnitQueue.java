package com.obby.android.localscreenshare.core.stream;

import com.obby.android.localscreenshare.core.codec.OverflowAction;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.util.TimeSource;

import java.util.ArrayDeque;

/**
 * A bounded FIFO of access units constrained by byte count, unit count, and
 * residency time.
 *
 * <p>Every public method synchronizes on the queue instance, so the entries, the byte counter and
 * the resync flag always change together; offers and polls may come from different threads.
 * Timestamps are supplied by the caller (millisecond values from a {@link TimeSource}), which
 * keeps residency decisions deterministic in tests.</p>
 *
 * <p><b>Independently decodable frames</b> ({@link OverflowAction#KEEP_LATEST_FRAME}): when the
 * queue is full the old entries are dropped and the newest complete unit is kept, and an oversized
 * unit clears the queue and is rejected. Neither case sets the resync flag, because dropping whole
 * frames leaves a receiver able to continue with the next one. Expired entries are discarded from
 * the head by {@link #poll(long)}. Every dropped unit and its bytes are counted.</p>
 *
 * <p><b>Reference-dependent frames</b> ({@link OverflowAction#DROP_UNTIL_JOIN_POINT}): overflow,
 * an oversized unit, or even a single expired entry breaks decodability, so the whole queue is
 * cleared and the resync flag is set. While the flag is set only join points are accepted; the
 * first join point is accepted after clearing the queue and clears the flag when it is polled, so
 * the receiver can resume on it. Rejected units never clear an existing resync flag.</p>
 *
 * <p>{@link #clear()} drops the entries and the resync flag but keeps the drop counters, which
 * accumulate for the lifetime of the queue instance. A new config epoch gets a new queue, so
 * per-epoch state does not leak across epochs.</p>
 */
public final class BoundedAccessUnitQueue {

    private final StreamQueueLimits limits;
    private final OverflowAction action;

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private long totalBytes;
    private boolean resyncRequired;
    private long droppedUnits;
    private long droppedBytes;

    /**
     * Creates a queue for one epoch.
     *
     * @param limits capacity and residency bounds; required
     * @param action overflow policy of the codec, taken from its
     *               {@link com.obby.android.localscreenshare.core.codec.CodecPolicy}; required
     * @throws IllegalArgumentException if either argument is null
     */
    public BoundedAccessUnitQueue(StreamQueueLimits limits, OverflowAction action) {
        if (limits == null) {
            throw new IllegalArgumentException("limits must not be null");
        }
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        this.limits = limits;
        this.action = action;
    }

    /**
     * Offers a unit for transmission.
     *
     * <p>A unit larger than the byte limit is never retained. For independently decodable frames
     * it clears the queue and is rejected; for reference-dependent frames the clear also sets the
     * resync flag. When the queue is full, the independently decodable policy drops the old
     * entries and keeps the new unit, while the reference-dependent policy clears and enters
     * resync, retaining the new unit only when it is a join point. A unit arriving while resync is
     * required is retained only if it is a join point, after clearing the queue.</p>
     *
     * @param accessUnit unit to enqueue; must not be null
     * @param nowMs      arrival time used later for the residency bound
     * @return what happened to the unit; a rejected unit is not retained
     * @throws IllegalArgumentException if {@code accessUnit} is null
     */
    public synchronized QueueOfferResult offer(EncodedAccessUnit accessUnit, long nowMs) {
        if (accessUnit == null) {
            throw new IllegalArgumentException("access unit must not be null");
        }
        int payloadLength = accessUnit.getPayloadLength();
        boolean join = accessUnit.getFlags().isJoinPoint();

        if (payloadLength > limits.getMaxBytes()) {
            clearEntriesCounted();
            if (action == OverflowAction.DROP_UNTIL_JOIN_POINT) {
                resyncRequired = true;
            }
            return QueueOfferResult.REJECTED_TOO_LARGE;
        }

        if (action == OverflowAction.KEEP_LATEST_FRAME) {
            if (entries.size() + 1 > limits.getMaxUnits()
                    || totalBytes + payloadLength > limits.getMaxBytes()) {
                clearEntriesCounted();
                enqueue(accessUnit, nowMs);
                return QueueOfferResult.ACCEPTED_KEPT_LATEST;
            }
            enqueue(accessUnit, nowMs);
            return QueueOfferResult.ACCEPTED;
        }

        if (resyncRequired) {
            if (!join) {
                return QueueOfferResult.REJECTED_NOT_JOIN_POINT;
            }
            clearEntriesCounted();
            enqueue(accessUnit, nowMs);
            return QueueOfferResult.ACCEPTED_AFTER_RESYNC;
        }

        if (entries.size() + 1 > limits.getMaxUnits()
                || totalBytes + payloadLength > limits.getMaxBytes()) {
            clearEntriesCounted();
            resyncRequired = true;
            if (!join) {
                return QueueOfferResult.REJECTED_NOT_JOIN_POINT;
            }
            enqueue(accessUnit, nowMs);
            return QueueOfferResult.ACCEPTED_AFTER_RESYNC;
        }

        enqueue(accessUnit, nowMs);
        return QueueOfferResult.ACCEPTED;
    }

    /**
     * Removes and returns the next usable unit, resolving expired entries first.
     *
     * <p>For independently decodable frames only the expired head entries are discarded, and the
     * next fresh unit is returned or the queue is reported empty. For reference-dependent frames a
     * single expired entry invalidates the whole queue: it is cleared, the resync flag is set and
     * {@link QueuePollResult#resync()} is returned, so the caller knows it must wait for a join
     * point instead of sending a unit whose references are gone. A join point polled while the
     * resync flag is set clears the flag, because sending it restores a decodable chain.</p>
     *
     * @param nowMs current time used for the residency bound
     * @return the next unit, a resync signal, or an empty result
     */
    public synchronized QueuePollResult poll(long nowMs) {
        if (action == OverflowAction.KEEP_LATEST_FRAME) {
            while (!entries.isEmpty()) {
                Entry head = entries.peekFirst();
                if (nowMs - head.enqueuedMs > limits.getMaxAgeMs()) {
                    entries.pollFirst();
                    dropped(head);
                } else {
                    break;
                }
            }
            if (entries.isEmpty()) {
                return QueuePollResult.empty();
            }
            Entry head = entries.pollFirst();
            totalBytes -= head.accessUnit.getPayloadLength();
            return QueuePollResult.frame(head.accessUnit);
        }

        if (!entries.isEmpty() && hasExpiredEntry(nowMs)) {
            clearEntriesCounted();
            resyncRequired = true;
            return QueuePollResult.resync();
        }
        if (entries.isEmpty()) {
            return QueuePollResult.empty();
        }
        Entry head = entries.pollFirst();
        totalBytes -= head.accessUnit.getPayloadLength();
        if (head.accessUnit.getFlags().isJoinPoint() && resyncRequired) {
            resyncRequired = false;
        }
        return QueuePollResult.frame(head.accessUnit);
    }

    /** Returns the number of queued units. */
    public synchronized int size() {
        return entries.size();
    }

    /** Returns the total payload bytes currently queued. */
    public synchronized long bytes() {
        return totalBytes;
    }

    /** Returns the number of units dropped since this queue was created. */
    public synchronized long droppedUnits() {
        return droppedUnits;
    }

    /** Returns the payload bytes dropped since this queue was created. */
    public synchronized long droppedBytes() {
        return droppedBytes;
    }

    /**
     * Returns whether the queue is waiting for a join point before it can deliver a decodable
     * sequence. Only the reference-dependent policy sets this flag.
     */
    public synchronized boolean isResyncRequired() {
        return resyncRequired;
    }

    /**
     * Drops all queued units and clears the resync flag, without resetting the drop counters.
     *
     * <p>Used by {@code ClientSendSession.detach()}; a config epoch change creates a new queue
     * instead of clearing the current one.</p>
     */
    public synchronized void clear() {
        entries.clear();
        totalBytes = 0;
        resyncRequired = false;
    }

    private void enqueue(EncodedAccessUnit accessUnit, long nowMs) {
        entries.addLast(new Entry(accessUnit, nowMs));
        totalBytes += accessUnit.getPayloadLength();
    }

    /** Drops every entry and counts it, so a clear is visible in the drop statistics. */
    private void clearEntriesCounted() {
        while (!entries.isEmpty()) {
            dropped(entries.pollFirst());
        }
    }

    private void dropped(Entry entry) {
        int length = entry.accessUnit.getPayloadLength();
        totalBytes -= length;
        droppedUnits++;
        droppedBytes += length;
    }

    /**
     * Returns whether any entry exceeds the residency bound. For reference-dependent frames a
     * single expired entry invalidates the whole FIFO, so the check scans past the head.
     */
    private boolean hasExpiredEntry(long nowMs) {
        for (Entry entry : entries) {
            if (nowMs - entry.enqueuedMs > limits.getMaxAgeMs()) {
                return true;
            }
        }
        return false;
    }

    private static final class Entry {

        final EncodedAccessUnit accessUnit;
        final long enqueuedMs;

        Entry(EncodedAccessUnit accessUnit, long enqueuedMs) {
            this.accessUnit = accessUnit;
            this.enqueuedMs = enqueuedMs;
        }
    }
}
