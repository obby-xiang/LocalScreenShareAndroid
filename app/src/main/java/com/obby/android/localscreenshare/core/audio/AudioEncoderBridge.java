package com.obby.android.localscreenshare.core.audio;

/**
 * Minimal audio encoder contract used by {@link AacEncoder}.
 *
 * <p>The bridge hides {@code MediaCodec}, so the framing, timestamping and error classification of
 * the audio pipeline stay testable on the JVM. An implementation owns exactly one codec instance
 * and is driven by a single thread.</p>
 *
 * <p><b>Output.</b> {@link #dequeueOutput()} returns one encoded frame per call and {@code null}
 * when nothing is ready; the caller drains until {@code null}. The AudioSpecificConfig becomes
 * available through {@link #getAudioSpecificConfig()} once the encoder has emitted its output
 * format, which for AAC happens before the first frame.</p>
 */
public interface AudioEncoderBridge {

    /**
     * Creates and starts the encoder.
     *
     * @throws AudioCaptureException with {@code ENCODER_FAILED} when the codec cannot be used
     */
    void configure(int sampleRate, int channels, int bitrateBps) throws AudioCaptureException;

    /**
     * Queues one frame of interleaved 16-bit PCM for encoding.
     *
     * @param pcm   interleaved samples
     * @param offset first sample of the frame
     * @param count  number of samples (channel-interleaved), equal to one encoder frame
     * @param ptsUs  presentation timestamp of the frame's first sample
     * @throws AudioCaptureException with {@code ENCODER_FAILED} when the codec rejects the frame
     */
    void queueInput(short[] pcm, int offset, int count, long ptsUs) throws AudioCaptureException;

    /**
     * Returns the next encoded frame, or {@code null} when no output is ready yet.
     *
     * @throws AudioCaptureException with {@code ENCODER_FAILED} when the codec fails
     */
    EncodedAudioFrame dequeueOutput() throws AudioCaptureException;

    /**
     * Returns the AudioSpecificConfig reported by the encoder, or {@code null} before the encoder
     * emitted its output format.
     */
    byte[] getAudioSpecificConfig();

    /**
     * Releases the codec; idempotent and non-throwing.
     */
    void close();
}
