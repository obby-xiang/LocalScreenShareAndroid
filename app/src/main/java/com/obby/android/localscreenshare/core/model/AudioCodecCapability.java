package com.obby.android.localscreenshare.core.model;

/**
 * Immutable audio decode capability of one client.
 *
 * <p>The capability is keyed by codec id and carries the decoder's bounds. A zero bound means
 * "unconstrained", mirroring the video {@code CodecCapability}, so a client that only knows it has
 * an AAC decoder can advertise {@link #any(String)}. A null MIME means unspecified. Instances are
 * immutable and safe to share between threads.</p>
 */
public final class AudioCodecCapability {

    private final String codecId;

    private final String mime;

    private final int maxSampleRate;

    private final int maxChannels;

    private final int maxBitrateBps;

    /**
     * Creates a capability.
     *
     * @throws IllegalArgumentException if {@code codecId} is null or blank, or a bound is negative
     */
    public AudioCodecCapability(String codecId, String mime, int maxSampleRate, int maxChannels,
                                int maxBitrateBps) {
        if (codecId == null || codecId.trim().isEmpty()) {
            throw new IllegalArgumentException("codecId must not be null or blank");
        }
        if (maxSampleRate < 0 || maxChannels < 0 || maxBitrateBps < 0) {
            throw new IllegalArgumentException("capability bounds must be non-negative");
        }
        this.codecId = codecId;
        this.mime = mime;
        this.maxSampleRate = maxSampleRate;
        this.maxChannels = maxChannels;
        this.maxBitrateBps = maxBitrateBps;
    }

    /**
     * Returns an unconstrained capability for {@code codecId}.
     */
    public static AudioCodecCapability any(String codecId) {
        return new AudioCodecCapability(codecId, null, 0, 0, 0);
    }

    /**
     * Returns whether this capability can decode a track with the given parameters.
     *
     * <p>A zero bound on either side admits any value, so a legacy client that declared only the
     * codec id is always compatible.</p>
     */
    public boolean accepts(int sampleRate, int channels, int bitrateBps) {
        return (maxSampleRate == 0 || sampleRate <= maxSampleRate)
                && (maxChannels == 0 || channels <= maxChannels)
                && (maxBitrateBps == 0 || bitrateBps <= maxBitrateBps);
    }

    public String getCodecId() {
        return codecId;
    }

    public String getMime() {
        return mime;
    }

    public int getMaxSampleRate() {
        return maxSampleRate;
    }

    public int getMaxChannels() {
        return maxChannels;
    }

    public int getMaxBitrateBps() {
        return maxBitrateBps;
    }
}
