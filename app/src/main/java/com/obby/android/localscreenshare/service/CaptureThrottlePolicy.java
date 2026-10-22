package com.obby.android.localscreenshare.service;

/**
 * Decides whether a captured image passes the configured frame-rate limit.
 *
 * <p><b>Pure decision.</b> The policy works on the timestamps reported by the image source, so it
 * never reads a wall clock and its behaviour is fully determined by its arguments. A frame is
 * accepted when it is the first one, when the source clock moves backwards (a counter reset must
 * not mute the capture), or when at least one frame interval has elapsed since the last accepted
 * frame. Frames with an equal timestamp are throttled, so duplicate callbacks never produce two
 * encodings of the same picture.</p>
 *
 * <p><b>Guard values.</b> A non-positive frame rate is treated as one frame per second instead of
 * dividing by zero; the caller's validated snapshot normally guarantees a positive rate, but a
 * misconfigured preference must not turn the capture into a busy loop.</p>
 */
public final class CaptureThrottlePolicy {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    /**
     * The minimum accepted frame rate, used for a non-positive configured value.
     */
    static final int MIN_FRAME_RATE = 1;

    private CaptureThrottlePolicy() {
    }

    /**
     * Returns whether the image timestamp passes the configured frame rate.
     *
     * @param imageTimestampNs nanoseconds reported by the image source
     * @param lastAcceptedNs   timestamp of the last accepted frame, or a negative value when none
     *                         was accepted yet
     * @param frameRate        configured frames per second; non-positive values count as one
     * @return {@code true} when the frame may be encoded
     */
    public static boolean shouldAccept(long imageTimestampNs, long lastAcceptedNs, int frameRate) {
        if (lastAcceptedNs < 0L) {
            return true;
        }
        if (imageTimestampNs < lastAcceptedNs) {
            return true;
        }
        final int effectiveFrameRate = frameRate > 0 ? frameRate : MIN_FRAME_RATE;
        return imageTimestampNs - lastAcceptedNs >= NANOS_PER_SECOND / effectiveFrameRate;
    }
}
