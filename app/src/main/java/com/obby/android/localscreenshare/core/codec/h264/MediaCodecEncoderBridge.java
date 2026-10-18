package com.obby.android.localscreenshare.core.codec.h264;

import android.view.Surface;

public interface MediaCodecEncoderBridge {

    void configure(EncoderFormat format);

    Surface createInputSurface();

    void start();

    void stop();

    void release();

    void requestSyncFrame();

    void updateBitrate(int bitrateBps);

    /**
     * Dequeues the next encoded sample, or {@code null} when the timeout elapses with no sample
     * available. The Android implementation releases the underlying output buffer internally
     * before returning a non-null sample.
     */
    EncodedSample dequeueOutput(long timeoutUs);

    /** Returns the latest output format snapshot, non-null only after a format change. */
    EncoderStreamFormat getOutputFormat();
}
