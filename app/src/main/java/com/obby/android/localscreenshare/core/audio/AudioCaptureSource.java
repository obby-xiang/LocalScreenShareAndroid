package com.obby.android.localscreenshare.core.audio;

/**
 * Source of interleaved 16-bit PCM samples.
 *
 * <p>The interface exists so the pipeline's framing, timestamping and failure handling are pure
 * JVM code: the Android implementation captures playback audio through
 * {@code AudioPlaybackCaptureConfiguration}, while tests supply a fake. A source produces samples
 * only; it never encodes, never allocates a queue and never calls back into the pipeline.</p>
 *
 * <p><b>Lifecycle.</b> {@link #open(int, int)} acquires the device and may fail; {@link #read}
 * blocks for at most one block of samples; {@link #close()} releases the device and is idempotent.
 * The pair is {@code open}/{@code close} on purpose: a capture device is a stream, not a service.</p>
 */
public interface AudioCaptureSource {

    /**
     * Platform-provided mapping between a captured frame position and the monotonic clock.
     */
    final class CaptureTimestamp {

        private final long framePosition;

        private final long ns;

        /**
         * Creates a timestamp.
         *
         * @param framePosition frame position the timestamp refers to; non-negative
         * @param ns            monotonic time in nanoseconds; positive
         */
        public CaptureTimestamp(long framePosition, long ns) {
            this.framePosition = framePosition;
            this.ns = ns;
        }

        /**
         * Returns the frame position the timestamp refers to.
         */
        public long getFramePosition() {
            return framePosition;
        }

        /**
         * Returns the monotonic time of that frame position in nanoseconds.
         */
        public long getNs() {
            return ns;
        }
    }

    /**
     * Opens the capture device.
     *
     * @param sampleRate sample rate in hertz
     * @param channels   channel count, one or two
     * @throws AudioCaptureException with the reason the device could not be acquired
     */
    void open(int sampleRate, int channels) throws AudioCaptureException;

    /**
     * Reads up to {@code count} samples into {@code buffer}.
     *
     * @return the number of samples read, zero when no data is available yet
     * @throws AudioCaptureException if the device failed
     */
    int read(short[] buffer, int offset, int count) throws AudioCaptureException;

    /**
     * Returns whether the device is open.
     */
    boolean isOpen();

    /**
     * Returns the configured sample rate.
     */
    int getSampleRate();

    /**
     * Returns the configured channel count.
     */
    int getChannels();

    /**
     * Returns the platform capture timestamp for the current read position, or {@code null} when
     * the platform cannot provide one.
     *
     * <p>When present, the timestamp maps a captured frame position to the monotonic clock and
     * lets the pipeline derive each frame's presentation time from the device's own clock instead
     * of from a wall-clock start plus an assumed rate.</p>
     */
    CaptureTimestamp getTimestamp();

    /**
     * Releases the device; idempotent and non-throwing.
     */
    void close();
}
