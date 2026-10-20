package com.obby.android.localscreenshare.core.codec.mediacodec;

import android.view.Surface;

/**
 * SPI for the encoder side of a platform media codec.
 *
 * <p>The bridge hides {@code MediaCodec}, {@code MediaFormat} and {@code ByteBuffer} so the H.264
 * adapter runs unchanged on the JVM with a fake implementation. One bridge owns one codec instance
 * and is neither thread-safe nor reusable.</p>
 *
 * <p><b>Call sequence.</b> {@link #configure(EncoderFormat)} and {@link #createInputSurface()} are
 * called once from the encoder's constructor, {@link #start()} once from {@code H264Encoder.start()},
 * {@link #stop()} from the drain thread as it exits, and {@link #release()} once from
 * {@code H264Encoder.close()}, and only after the drain thread is known to have terminated.
 * Parameter updates may occur while the codec is running.</p>
 *
 * <p><b>Ownership.</b> The bridge owns the input surface it creates and releases it in
 * {@link #release()}; the adapter never releases it. Output buffers are borrowed: they are released
 * inside {@link #dequeueOutput(long)} before a sample is returned, so an {@link EncodedSample} is a
 * detached copy.</p>
 *
 * <p><b>CSD timing.</b> {@code csd-0}/{@code csd-1} usually appear only after {@link #start()},
 * either as a format change or as a codec-config buffer. That delay is why the H.264 encoder cannot
 * build a stream descriptor before starting the codec.</p>
 */
public interface MediaCodecEncoderBridge {

    /**
     * Configures the codec for {@code format}. Called once, before {@link #start()}; an
     * implementation must not start the codec here.
     */
    void configure(EncoderFormat format);

    /**
     * Creates and returns the surface the producer renders into. Called once, after
     * {@link #configure(EncoderFormat)} and before {@link #start()}; the surface stays valid until
     * {@link #release()}.
     */
    Surface createInputSurface();

    /**
     * Starts the codec. Encoded output becomes available only after this call.
     */
    void start();

    /**
     * Stops the codec after the drain loop exits. Idempotent.
     */
    void stop();

    /**
     * Releases the codec and the input surface. Idempotent; never called while a call is in flight.
     */
    void release();

    /**
     * Requests an immediate sync frame (IDR). Best effort: the frame arrives through
     * {@link #dequeueOutput(long)}.
     */
    void requestSyncFrame();

    /**
     * Applies a new target bitrate without reconfiguring or restarting the codec.
     */
    void updateBitrate(int bitrateBps);

    /**
     * Dequeues the next encoded sample, or {@code null} when the timeout elapses with no sample
     * available. The Android implementation releases the underlying output buffer internally
     * before returning a non-null sample.
     */
    EncodedSample dequeueOutput(long timeoutUs);

    /**
     * Returns the latest output format snapshot, non-null only after a format change.
     */
    EncoderStreamFormat getOutputFormat();
}
