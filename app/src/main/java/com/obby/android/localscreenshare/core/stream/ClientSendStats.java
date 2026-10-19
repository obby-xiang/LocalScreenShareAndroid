package com.obby.android.localscreenshare.core.stream;

/**
 * Immutable snapshot of a client send session's counters.
 *
 * <p>{@code queuedUnits} and {@code queuedBytes} describe the current epoch's queue.
 * {@code droppedUnits} accumulates for the session's lifetime, including units dropped by previous
 * epochs' queues. {@code resyncCount} counts resynchronization episodes rather than rejected
 * offers, and {@code sentUnits} and {@code descriptorSends} count successfully completed transport
 * calls.</p>
 */
public final class ClientSendStats {

    private final long queuedUnits;
    private final long queuedBytes;
    private final long droppedUnits;
    private final long resyncCount;
    private final long sentUnits;
    private final long descriptorSends;

    public ClientSendStats(long queuedUnits, long queuedBytes, long droppedUnits,
                           long resyncCount, long sentUnits, long descriptorSends) {
        this.queuedUnits = queuedUnits;
        this.queuedBytes = queuedBytes;
        this.droppedUnits = droppedUnits;
        this.resyncCount = resyncCount;
        this.sentUnits = sentUnits;
        this.descriptorSends = descriptorSends;
    }

    public long getQueuedUnits() {
        return queuedUnits;
    }

    public long getQueuedBytes() {
        return queuedBytes;
    }

    public long getDroppedUnits() {
        return droppedUnits;
    }

    public long getResyncCount() {
        return resyncCount;
    }

    public long getSentUnits() {
        return sentUnits;
    }

    public long getDescriptorSends() {
        return descriptorSends;
    }
}
