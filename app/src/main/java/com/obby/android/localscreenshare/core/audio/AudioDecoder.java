package com.obby.android.localscreenshare.core.audio;

import com.obby.android.localscreenshare.core.model.AudioDescriptor;

/**
 * Audio decoder contract.
 *
 * <p>The client side never parses ADTS and never assumes the platform returns one buffer per
 * payload: {@link #decode(byte[], long)} returns the PCM the codec produced for that payload, or
 * {@code null} when the codec needs more input before it can emit samples. The returned buffer
 * carries the format the decoder actually used.</p>
 *
 * <p><b>Lifecycle.</b> {@link #configure(AudioDescriptor)} may be called again for a new epoch;
 * {@link #close()} is idempotent and non-throwing. Implementations are driven by a single thread
 * and add no locking of their own.</p>
 */
public interface AudioDecoder {

    /**
     * Configures the decoder for an audio descriptor, replacing any previous configuration.
     *
     * @throws AudioDecoderException if the codec is missing or rejects the configuration
     */
    void configure(AudioDescriptor descriptor) throws AudioDecoderException;

    /**
     * Decodes one encoded frame.
     *
     * @param payload encoded bytes of the current epoch; non-empty
     * @param ptsUs   presentation timestamp of the frame's first sample
     * @return decoded PCM, or null when the codec produced no output for this input
     * @throws AudioDecoderException if decoding failed
     */
    AudioPcm decode(byte[] payload, long ptsUs) throws AudioDecoderException;

    /**
     * Releases the codec; idempotent and non-throwing.
     */
    void close();
}
