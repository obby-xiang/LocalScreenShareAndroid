package com.obby.android.localscreenshare.core.audio;

/**
 * Frame arithmetic of the playback head.
 *
 * <p>The sink reports a frame count and the session needs stream time, so both conversions live
 * here as pure functions: projecting a platform timestamp onto a requested instant, and turning the
 * distance between what was written and what was presented into the stream timestamp that is
 * audible right now.</p>
 *
 * <p><b>Why the conversions are not in the sink.</b> A frame count is not a time, and a
 * track-relative time is not the session clock: subtracting one from the other yields offsets of
 * the order of the device uptime. Keeping the arithmetic free of {@code AudioTrack} lets the units
 * be pinned on the JVM, where the platform cannot be asked.</p>
 *
 * <p><b>Buffer accounting.</b> {@link #audiblePtsUs(long, long, long, int)} subtracts the frames
 * still queued inside the track from the end of the last written buffer. The output buffer is
 * therefore measured rather than assumed, and it never appears as a constant offset.</p>
 */
public final class AudioPlayhead {

    /**
     * Marker for an unknown frame position; {@link #presentedFrames} and
     * {@link #audiblePtsUs} also return it when their inputs cannot be projected.
     */
    public static final long UNKNOWN_FRAMES = -1L;

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private static final long MICROS_PER_SECOND = 1_000_000L;

    private AudioPlayhead() {
    }

    /**
     * Projects a platform timestamp onto {@code nowNs}.
     *
     * <p>{@code AudioTrack.getTimestamp} reports a frame position together with the monotonic
     * instant that position was presented at, so the position at the measurement instant is the
     * reported one plus whatever elapsed since. Elapsed time is clamped at zero, because a timestamp
     * from the future means the track just started rather than that the position goes backwards.</p>
     *
     * @param framePosition frames presented at {@code timestampNs}, or a negative value when unknown
     * @param timestampNs   monotonic instant the position belongs to
     * @param nowNs         monotonic instant to project onto
     * @param sampleRate    frames per second; a non-positive value disables the projection
     * @return the frames presented at {@code nowNs}, or {@link #UNKNOWN_FRAMES}
     */
    public static long presentedFrames(long framePosition, long timestampNs, long nowNs,
                                       int sampleRate) {
        if (framePosition < 0L) {
            return UNKNOWN_FRAMES;
        }
        if (sampleRate <= 0) {
            return framePosition;
        }
        final long elapsedNs = nowNs - timestampNs;
        if (elapsedNs <= 0L) {
            return framePosition;
        }
        return framePosition + elapsedNs * sampleRate / NANOS_PER_SECOND;
    }

    /**
     * Returns the stream timestamp audible once {@code presentedFrames} have been played.
     *
     * <p>{@code writtenEndPtsUs} is the end of the last buffer handed to the sink, so subtracting
     * the duration still queued in the track gives the position leaving the speaker. Frames the
     * device reports ahead of the written count are clamped to zero instead of producing a position
     * beyond the last written sample.</p>
     *
     * @param writtenEndPtsUs stream timestamp at the end of the last written buffer
     * @param writtenFrames   frames written since the sink was opened
     * @param presentedFrames frames presented, as returned by
     *                        {@link #presentedFrames(long, long, long, int)}
     * @param sampleRate      frames per second; a non-positive value yields
     *                        {@link SessionClock#UNSET}
     * @return the audible stream timestamp in microseconds, or {@link SessionClock#UNSET}
     */
    public static long audiblePtsUs(long writtenEndPtsUs, long writtenFrames,
                                    long presentedFrames, int sampleRate) {
        if (writtenEndPtsUs == SessionClock.UNSET || presentedFrames < 0L || sampleRate <= 0) {
            return SessionClock.UNSET;
        }
        final long queuedFrames = Math.max(0L, writtenFrames - presentedFrames);
        return writtenEndPtsUs - queuedFrames * MICROS_PER_SECOND / sampleRate;
    }
}
