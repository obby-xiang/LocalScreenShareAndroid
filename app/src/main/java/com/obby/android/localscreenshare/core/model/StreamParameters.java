package com.obby.android.localscreenshare.core.model;

/**
 * Immutable encoder-side rate and cadence hints for a stream.
 *
 * <p>All values must be non-negative. They are hints, not guarantees: an encoder clamps them to
 * the range its codec supports (for example, a bitrate of zero is clamped to a positive value and
 * a key-frame interval is rounded up to whole seconds). Parameters never carry stream identity or
 * configuration; updating them does not advance the config epoch.</p>
 */
public final class StreamParameters {

    /** Default hints: 4 Mbps, 30 frames per second and a 1 second key-frame interval. */
    public static final StreamParameters DEFAULT =
            new StreamParameters(4_000_000, 30, 1000);

    private final int targetBitrateBps;
    private final int frameRateHint;
    private final int keyFrameIntervalMs;

    /**
     * Creates a parameter set.
     *
     * @throws IllegalArgumentException if any value is negative
     */
    public StreamParameters(int targetBitrateBps, int frameRateHint, int keyFrameIntervalMs) {
        if (targetBitrateBps < 0) {
            throw new IllegalArgumentException("targetBitrateBps must be >= 0");
        }
        if (frameRateHint < 0) {
            throw new IllegalArgumentException("frameRateHint must be >= 0");
        }
        if (keyFrameIntervalMs < 0) {
            throw new IllegalArgumentException("keyFrameIntervalMs must be >= 0");
        }
        this.targetBitrateBps = targetBitrateBps;
        this.frameRateHint = frameRateHint;
        this.keyFrameIntervalMs = keyFrameIntervalMs;
    }

    public int getTargetBitrateBps() {
        return targetBitrateBps;
    }

    public int getFrameRateHint() {
        return frameRateHint;
    }

    public int getKeyFrameIntervalMs() {
        return keyFrameIntervalMs;
    }

    /** Returns a copy with another target bitrate; this instance is unchanged. */
    public StreamParameters withTargetBitrate(int newTargetBitrateBps) {
        return new StreamParameters(newTargetBitrateBps, frameRateHint, keyFrameIntervalMs);
    }

    /** Returns a copy with another frame-rate hint; this instance is unchanged. */
    public StreamParameters withFrameRateHint(int newFrameRateHint) {
        return new StreamParameters(targetBitrateBps, newFrameRateHint, keyFrameIntervalMs);
    }

    /** Returns a copy with another key-frame interval; this instance is unchanged. */
    public StreamParameters withKeyFrameInterval(int newKeyFrameIntervalMs) {
        return new StreamParameters(targetBitrateBps, frameRateHint, newKeyFrameIntervalMs);
    }
}
