package com.obby.android.localscreenshare.core.stream;

import com.obby.android.localscreenshare.core.codec.OverflowAction;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;

import java.util.ArrayDeque;

/**
 * A bounded FIFO of access units constrained by byte count, unit count, and
 * residency time. All state mutations happen under a single lock.
 */
public final class BoundedAccessUnitQueue {

    private final StreamQueueLimits limits;
    private final OverflowAction action;

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private long totalBytes;
    private boolean resyncRequired;
    private long droppedUnits;
    private long droppedBytes;

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

    public synchronized int size() {
        return entries.size();
    }

    public synchronized long bytes() {
        return totalBytes;
    }

    public synchronized long droppedUnits() {
        return droppedUnits;
    }

    public synchronized long droppedBytes() {
        return droppedBytes;
    }

    public synchronized boolean isResyncRequired() {
        return resyncRequired;
    }

    public synchronized void clear() {
        entries.clear();
        totalBytes = 0;
        resyncRequired = false;
    }

    private void enqueue(EncodedAccessUnit accessUnit, long nowMs) {
        entries.addLast(new Entry(accessUnit, nowMs));
        totalBytes += accessUnit.getPayloadLength();
    }

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
