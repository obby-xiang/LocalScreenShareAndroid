package com.obby.android.localscreenshare.core.stream;

/**
 * Immutable snapshot of a client send session's counters.
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
