package com.obby.android.localscreenshare.core.model;

/**
 * Immutable encoded audio frame.
 *
 * <p>Audio access units are self-contained: they carry no key-frame concept, no decode timestamp
 * and no join information, so a receiver can start decoding at any frame of the current epoch. The
 * sequence restarts at zero for every config epoch, and the presentation timestamp is the capture
 * time of the frame's first sample on the same monotonic microsecond base as the video stream.</p>
 *
 * <p><b>Invariants.</b> The epoch must be at least one, the sequence and presentation timestamp
 * must be non-negative and the payload must be non-null and non-empty: an empty frame carries
 * nothing and would only make the queue accounting lie. The payload is copied on the way in and
 * out, so an instance is immutable and safe to share between threads.</p>
 */
public final class AudioAccessUnit {

    private final int configEpoch;
    private final long sequence;
    private final long ptsUs;
    private final byte[] payload;

    /**
     * Creates an audio access unit.
     *
     * @param configEpoch configuration epoch the frame was encoded for; must be at least one
     * @param sequence    index within the epoch; must be non-negative
     * @param ptsUs       presentation timestamp in microseconds; must be non-negative
     * @param payload     encoded bytes; must be non-null and non-empty, and is copied
     * @throws IllegalArgumentException if any invariant above is violated
     */
    public AudioAccessUnit(int configEpoch, long sequence, long ptsUs, byte[] payload) {
        if (configEpoch < 1) {
            throw new IllegalArgumentException("configEpoch must be >= 1");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must be >= 0");
        }
        if (ptsUs < 0) {
            throw new IllegalArgumentException("ptsUs must be >= 0");
        }
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("payload must not be null or empty");
        }
        this.configEpoch = configEpoch;
        this.sequence = sequence;
        this.ptsUs = ptsUs;
        this.payload = payload.clone();
    }

    /**
     * Returns the config epoch of the descriptor this frame was encoded for.
     */
    public int getConfigEpoch() {
        return configEpoch;
    }

    /**
     * Returns the frame index within its epoch.
     */
    public long getSequence() {
        return sequence;
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

    /**
     * Returns the payload length without copying the payload.
     */
    public int getPayloadLength() {
        return payload.length;
    }
}
