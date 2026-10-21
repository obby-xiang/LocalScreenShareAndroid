package com.obby.android.localscreenshare.core.audio;

/**
 * Maps a captured frame position onto the monotonic microsecond timeline.
 *
 * <p>Audio timestamps must share the video stream's time base so the two tracks can be lined up on
 * one session clock. The platform reports a timestamp as a pair of a frame position and a
 * monotonic nanosecond value; this mapper turns "the frame I am about to encode starts at sample
 * {@code frameStart}" into a presentation time, using the pair as the anchor and integer arithmetic
 * only, so a long capture never accumulates rounding drift.</p>
 *
 * <p><b>Fallback.</b> When the platform provides no timestamp (or the pair is implausible, for
 * example after the device reset its capture position), the presentation time is derived from the
 * anchor taken when the pipeline opened: {@code fallbackStartNs + frameStart * 1e9 / sampleRate}.
 * Both formulas are exact integer divisions of the frame index, so the only error is the
 * sub-microsecond truncation of one conversion, never an error that grows per frame.</p>
 */
public final class AudioTimestampMapper {

    /**
     * Largest distance, in seconds, a frame may lie behind the timestamped position.
     *
     * <p>A frame older than this means the timestamp is stale (the read loop fell behind after a
     * stall) rather than that the frame simply precedes the timestamped one.</p>
     */
    private static final long MAX_LAG_SECONDS = 2L;

    /**
     * Largest distance, in seconds, a frame may lie ahead of the timestamped position.
     */
    private static final long MAX_LEAD_SECONDS = 60L;

    private AudioTimestampMapper() {
    }

    /**
     * Returns the presentation time of a frame, in microseconds.
     *
     * @param frameStart            index of the frame's first sample since the capture opened;
     *                              non-negative
     * @param timestampFramePosition frame position reported by the platform, or a negative value
     *                              when no timestamp is available
     * @param timestampNs           monotonic time of {@code timestampFramePosition}, or a
     *                              non-positive value when unavailable
     * @param fallbackStartNs       monotonic time the capture opened; used when the platform has no
     *                              usable timestamp
     * @param sampleRate            capture sample rate in hertz; must be positive
     * @return the presentation time in microseconds, non-negative
     * @throws IllegalArgumentException if {@code frameStart} is negative or {@code sampleRate} is
     *                                  not positive
     */
    public static long ptsUsForFrame(long frameStart, long timestampFramePosition, long timestampNs,
                                     long fallbackStartNs, int sampleRate) {
        if (frameStart < 0) {
            throw new IllegalArgumentException("frameStart must be non-negative");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive");
        }
        if (!isUsable(timestampFramePosition, timestampNs)) {
            return fallbackPtsUs(frameStart, fallbackStartNs, sampleRate);
        }
        final long delta = frameStart - timestampFramePosition;
        if (delta < -MAX_LAG_SECONDS * sampleRate || delta > MAX_LEAD_SECONDS * sampleRate) {
            // The pair does not describe this frame: the capture position was reset, or the
            // timestamp is older than the tolerated lag. The linear derivation is the safer answer.
            return fallbackPtsUs(frameStart, fallbackStartNs, sampleRate);
        }
        final long ptsNs = timestampNs + delta * 1_000_000_000L / sampleRate;
        if (ptsNs <= 0) {
            return fallbackPtsUs(frameStart, fallbackStartNs, sampleRate);
        }
        return ptsNs / 1_000L;
    }

    /**
     * Returns the presentation time of a frame using a platform timestamp that may be null.
     *
     * @param frameStart      index of the frame's first sample since the capture opened
     * @param timestamp       platform timestamp, or null when unavailable
     * @param fallbackStartNs monotonic time the capture opened
     * @param sampleRate      capture sample rate in hertz
     * @return the presentation time in microseconds
     */
    public static long ptsUsForFrame(long frameStart, AudioCaptureSource.CaptureTimestamp timestamp,
                                     long fallbackStartNs, int sampleRate) {
        if (timestamp == null) {
            return ptsUsForFrame(frameStart, -1L, -1L, fallbackStartNs, sampleRate);
        }
        return ptsUsForFrame(frameStart, timestamp.getFramePosition(), timestamp.getNs(),
                fallbackStartNs, sampleRate);
    }

    /**
     * Returns the duration of {@code samples} samples in microseconds.
     *
     * @throws IllegalArgumentException if {@code samples} is negative or the rate is not positive
     */
    public static long durationUs(long samples, int sampleRate) {
        if (samples < 0) {
            throw new IllegalArgumentException("samples must be non-negative");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive");
        }
        return samples * 1_000_000L / sampleRate;
    }

    private static boolean isUsable(long framePosition, long ns) {
        return framePosition >= 0 && ns > 0;
    }

    private static long fallbackPtsUs(long frameStart, long fallbackStartNs, int sampleRate) {
        final long startNs = Math.max(0L, fallbackStartNs);
        return (startNs + frameStart * 1_000_000_000L / sampleRate) / 1_000L;
    }
}
