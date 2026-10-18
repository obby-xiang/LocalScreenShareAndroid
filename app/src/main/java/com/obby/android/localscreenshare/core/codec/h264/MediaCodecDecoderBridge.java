package com.obby.android.localscreenshare.core.codec.h264;

public interface MediaCodecDecoderBridge {

    void configure(DecoderFormat format);

    void start();

    void stop();

    void release();

    /** Returns the next available input buffer index, or {@code -1} on timeout. */
    int dequeueInputBuffer(long timeoutUs);

    void queueInputBuffer(int index, byte[] data, long ptsUs);

    /**
     * Dequeues the next decoded output, or {@code null} when the timeout elapses with no output
     * available. The Android implementation releases the underlying output buffer internally
     * (rendering to the configured surface) before returning a non-null output.
     */
    DecodedOutput dequeueOutput(long timeoutUs);
}
