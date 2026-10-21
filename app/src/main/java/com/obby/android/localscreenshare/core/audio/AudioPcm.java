package com.obby.android.localscreenshare.core.audio;

/**
 * Decoded PCM buffer together with the format the decoder actually produced.
 *
 * <p>The format travels with every buffer because a platform decoder is allowed to change it: some
 * devices return a different sample rate or channel layout than requested. The player rebuilds its
 * output when the format changes instead of feeding mismatched samples into the sink.</p>
 */
public final class AudioPcm {

    private final byte[] data;

    private final int sampleRate;

    private final int channels;

    /**
     * Creates a buffer.
     *
     * @param data       little-endian 16-bit interleaved samples; required and copied
     * @param sampleRate actual sample rate in hertz; must be positive
     * @param channels   actual channel count; must be positive
     * @throws IllegalArgumentException if an invariant is violated
     */
    public AudioPcm(byte[] data, int sampleRate, int channels) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("data must not be null or empty");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive");
        }
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive");
        }
        this.data = data.clone();
        this.sampleRate = sampleRate;
        this.channels = channels;
    }

    /**
     * Returns a copy of the PCM bytes.
     */
    public byte[] getData() {
        return data.clone();
    }

    /**
     * Returns the byte length without copying.
     */
    public int getLength() {
        return data.length;
    }

    /**
     * Returns the sample rate in hertz.
     */
    public int getSampleRate() {
        return sampleRate;
    }

    /**
     * Returns the channel count.
     */
    public int getChannels() {
        return channels;
    }

    /**
     * Returns the duration of this buffer in microseconds.
     */
    public long getDurationUs() {
        final long samplesPerChannel = (long) data.length / (2L * channels);
        return samplesPerChannel * 1_000_000L / sampleRate;
    }
}
