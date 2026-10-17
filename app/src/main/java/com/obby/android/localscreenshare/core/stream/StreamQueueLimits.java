package com.obby.android.localscreenshare.core.stream;

/**
 * Immutable capacity and residency bounds for a bounded access-unit queue.
 */
public final class StreamQueueLimits {

    // Temporary tuning values pending real-device measurement; not a production conclusion.
    private static final long DEFAULT_MAX_BYTES = 4L * 1024L * 1024L;
    private static final int DEFAULT_MAX_UNITS = 120;
    private static final long DEFAULT_MAX_AGE_MS = 2000L;

    private final long maxBytes;
    private final int maxUnits;
    private final long maxAgeMs;

    public StreamQueueLimits(long maxBytes, int maxUnits, long maxAgeMs) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        if (maxUnits <= 0) {
            throw new IllegalArgumentException("maxUnits must be positive");
        }
        if (maxAgeMs <= 0) {
            throw new IllegalArgumentException("maxAgeMs must be positive");
        }
        this.maxBytes = maxBytes;
        this.maxUnits = maxUnits;
        this.maxAgeMs = maxAgeMs;
    }

    public long getMaxBytes() {
        return maxBytes;
    }

    public int getMaxUnits() {
        return maxUnits;
    }

    public long getMaxAgeMs() {
        return maxAgeMs;
    }

    public static StreamQueueLimits defaults() {
        return new StreamQueueLimits(DEFAULT_MAX_BYTES, DEFAULT_MAX_UNITS, DEFAULT_MAX_AGE_MS);
    }
}
