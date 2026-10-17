package com.obby.android.localscreenshare.core.stream;

import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;

/**
 * Result of polling a bounded access-unit queue.
 */
public final class QueuePollResult {

    private final EncodedAccessUnit accessUnit;
    private final boolean resyncRequired;

    private QueuePollResult(EncodedAccessUnit accessUnit, boolean resyncRequired) {
        this.accessUnit = accessUnit;
        this.resyncRequired = resyncRequired;
    }

    public EncodedAccessUnit getAccessUnit() {
        return accessUnit;
    }

    public boolean isResyncRequired() {
        return resyncRequired;
    }

    public static QueuePollResult frame(EncodedAccessUnit accessUnit) {
        if (accessUnit == null) {
            throw new IllegalArgumentException("access unit must not be null");
        }
        return new QueuePollResult(accessUnit, false);
    }

    public static QueuePollResult resync() {
        return new QueuePollResult(null, true);
    }

    public static QueuePollResult empty() {
        return new QueuePollResult(null, false);
    }
}
