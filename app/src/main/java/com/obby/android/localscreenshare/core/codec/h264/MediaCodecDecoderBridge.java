package com.obby.android.localscreenshare.core.codec.h264;

/**
 * SPI for the decoder side of a platform media codec.
 *
 * <p>The bridge hides {@code MediaCodec} and {@code ByteBuffer} so the H.264 adapter runs unchanged
 * on the JVM with a fake implementation. One bridge owns one codec instance and is not thread-safe;
 * the owning decoder must drive every call from a single thread.</p>
 *
 * <p><b>Surface ownership.</b> The output surface arrives in {@link DecoderFormat} and belongs to
 * the caller's render target; the bridge configures against it and never releases it. A surface
 * change requires a new decoder and a new bridge, not a mutation of this one.</p>
 *
 * <p><b>Buffer protocol.</b> An input buffer index is obtained with
 * {@link #dequeueInputBuffer(long)}, filled and handed back with
 * {@link #queueInputBuffer(int, byte[], long)}; the index is invalid afterwards. Output buffers are
 * released inside {@link #dequeueOutput(long)}, so the caller never sees an output index.</p>
 *
 * <p><b>Lifecycle.</b> {@link #configure(DecoderFormat)}, {@link #start()} and {@link #stop()} follow
 * the order and idempotency rules of the encoder bridge; only {@link #release()} frees the codec.</p>
 */
public interface MediaCodecDecoderBridge {

    /** Configures the codec with the Annex B parameter sets and render surface in {@code format}. */
    void configure(DecoderFormat format);

    /** Starts the codec so queued input can be decoded. */
    void start();

    /** Stops the codec. Idempotent; safe on a codec that was never started. */
    void stop();

    /** Releases the codec. Idempotent; the render surface is not released. */
    void release();

    /**
     * Returns the next available input buffer index, or {@code -1} on timeout. The index is consumed
     * by {@link #queueInputBuffer(int, byte[], long)}.
     */
    int dequeueInputBuffer(long timeoutUs);

    /** Copies {@code data} into input buffer {@code index} and queues it as one complete access unit. */
    void queueInputBuffer(int index, byte[] data, long ptsUs);

    /**
     * Dequeues the next decoded output, or {@code null} when the timeout elapses with no output
     * available. The Android implementation releases the underlying output buffer internally
     * (rendering to the configured surface) before returning a non-null output.
     */
    DecodedOutput dequeueOutput(long timeoutUs);
}
