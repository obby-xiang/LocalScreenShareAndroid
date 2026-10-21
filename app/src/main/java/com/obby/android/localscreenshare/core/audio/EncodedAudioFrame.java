package com.obby.android.localscreenshare.core.audio;

/**
 * One encoded audio frame produced by an {@link AudioEncoderBridge}.
 *
 * <p>The payload is the raw codec frame, without ADTS headers: the receiver configures its decoder
 * from the descriptor's AudioSpecificConfig instead. The timestamp is the presentation time of the
 * frame's first sample on the same monotonic microsecond base as the video stream.</p>
 */
public final class EncodedAudioFrame {

    private final long ptsUs;

    private final byte[] payload;

    /**
     * Creates an encoded frame.
     *
     * @param ptsUs   presentation timestamp in microseconds; non-negative
     * @param payload encoded bytes; non-null and non-empty, copied
     * @throws IllegalArgumentException if an invariant is violated
     */
    public EncodedAudioFrame(long ptsUs, byte[] payload) {
        if (ptsUs < 0) {
            throw new IllegalArgumentException("ptsUs must be non-negative");
        }
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("payload must not be null or empty");
        }
        this.ptsUs = ptsUs;
        this.payload = payload.clone();
    }

    /**
     * Returns the presentation timestamp in microseconds.
     */
    public long getPtsUs() {
        return ptsUs;
    }

    /**
     * Returns a copy of the encoded payload.
     */
    public byte[] getPayload() {
        return payload.clone();
    }
}
