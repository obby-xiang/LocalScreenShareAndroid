package com.obby.android.localscreenshare.core.model;

/**
 * Immutable encoder-side rate and cadence hints for a stream.
 */
public final class StreamParameters {

    public static final StreamParameters DEFAULT =
            new StreamParameters(4_000_000, 30, 1000);

    private final int targetBitrateBps;
    private final int frameRateHint;
    private final int keyFrameIntervalMs;

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

    public StreamParameters withTargetBitrate(int newTargetBitrateBps) {
        return new StreamParameters(newTargetBitrateBps, frameRateHint, keyFrameIntervalMs);
    }

    public StreamParameters withFrameRateHint(int newFrameRateHint) {
        return new StreamParameters(targetBitrateBps, newFrameRateHint, keyFrameIntervalMs);
    }

    public StreamParameters withKeyFrameInterval(int newKeyFrameIntervalMs) {
        return new StreamParameters(targetBitrateBps, frameRateHint, newKeyFrameIntervalMs);
    }
}
