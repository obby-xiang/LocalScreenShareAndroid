package com.obby.android.localscreenshare.core.codec.mediacodec;

/**
 * Outcome of one {@link MediaCodecDecoderBridge#dequeueOutput(long)} call.
 *
 * <p>A non-null instance reports exactly one of three things: an output-format change, an
 * end-of-stream buffer, or a decoded frame. A decoded frame still owns a platform output buffer:
 * {@link #getBufferIndex()} names it, and the caller must release it exactly once through
 * {@link MediaCodecDecoderBridge#present(int, long)} or
 * {@link MediaCodecDecoderBridge#discard(int)}. Nothing is rendered by the dequeue call itself, so
 * the caller decides when — or whether — a frame reaches the surface.</p>
 *
 * <p>A format-change entry carries no buffer and reports {@link #NO_BUFFER} plus a timestamp of
 * {@code 0}. Instances are immutable, own no resources and are safe to keep after the frame was
 * released, because they carry an index rather than a buffer reference.</p>
 */
public final class DecodedOutput {

    /**
     * Buffer index of an entry that carries no output buffer (a format change).
     */
    public static final int NO_BUFFER = -1;

    private final int bufferIndex;
    private final long ptsUs;
    private final boolean formatChanged;
    private final boolean endOfStream;

    /**
     * Creates one dequeue outcome.
     *
     * @param bufferIndex  platform output buffer index, or {@link #NO_BUFFER} for a format change
     * @param ptsUs        presentation timestamp in microseconds, or {@code 0} without a buffer
     * @param formatChanged whether this entry only announces an output-format change
     * @param endOfStream  whether the codec reported the end of the stream
     */
    public DecodedOutput(int bufferIndex, long ptsUs, boolean formatChanged, boolean endOfStream) {
        this.bufferIndex = bufferIndex;
        this.ptsUs = ptsUs;
        this.formatChanged = formatChanged;
        this.endOfStream = endOfStream;
    }

    /**
     * Returns the platform output buffer index, or {@link #NO_BUFFER} when there is none.
     */
    public int getBufferIndex() {
        return bufferIndex;
    }

    /**
     * Returns whether this entry owns an output buffer that must be presented or discarded.
     */
    public boolean hasBuffer() {
        return bufferIndex != NO_BUFFER;
    }

    /**
     * Presentation timestamp in microseconds, or {@code 0} for a format-change entry.
     */
    public long getPtsUs() {
        return ptsUs;
    }

    /**
     * True when this entry only announces an output-format change and carries no frame.
     */
    public boolean isFormatChanged() {
        return formatChanged;
    }

    /**
     * True when the codec reported the end of the stream.
     */
    public boolean isEndOfStream() {
        return endOfStream;
    }
}
