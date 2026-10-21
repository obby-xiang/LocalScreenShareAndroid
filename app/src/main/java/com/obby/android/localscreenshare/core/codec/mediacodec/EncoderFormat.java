package com.obby.android.localscreenshare.core.codec.mediacodec;

/**
 * Immutable encoder configuration handed to
 * {@link MediaCodecEncoderBridge#configure(EncoderFormat)}.
 *
 * <p>Values are already normalized by the caller: bitrate and frame rate are at least 1, and
 * {@link #getKeyFrameIntervalSeconds()} is the millisecond interval rounded up to whole seconds with
 * a floor of 1, because the platform key is expressed in seconds. The size is the requested coded
 * size; the actual coded size is only known once the codec reports its output format, which is why
 * the adapter publishes configuration after starting rather than before.</p>
 *
 * <p><b>Two frame rates.</b> {@link #getFrameRate()} is the target the rate controller uses, which
 * never caps the source by itself. {@link #getMaxFpsToEncoder()} is the optional cap a surface-input
 * encoder applies by dropping frames before encoding; zero means "do not ask". They are separate
 * because the platform treats them separately: one is a hint, the other is a request the encoder
 * may ignore.</p>
 *
 * <p>No profile, level or B-frame field is carried here; the bridge supplies the remaining platform
 * keys itself, so the codec's own defaults apply to everything not listed above.</p>
 */
public final class EncoderFormat {

    private final int width;

    private final int height;

    private final int bitrateBps;

    private final int frameRate;

    private final int keyFrameIntervalSeconds;

    private final float maxFpsToEncoder;

    /**
     * Creates a format with an optional frame-rate cap.
     *
     * <p>Passing zero for {@code maxFpsToEncoder} is the "no cap requested" case, which every
     * existing caller meant before the cap existed. There is deliberately one constructor so the
     * type stays a closed value with no overload that silently drops the cap.</p>
     *
     * @param width                  coded width
     * @param height                 coded height
     * @param bitrateBps             target bitrate in bits per second
     * @param frameRate              target frame rate
     * @param keyFrameIntervalSeconds key-frame interval in seconds
     * @param maxFpsToEncoder        maximum frame rate fed to the encoder, or zero to request none
     */
    public EncoderFormat(int width, int height, int bitrateBps, int frameRate,
            int keyFrameIntervalSeconds, float maxFpsToEncoder) {
        this.width = width;
        this.height = height;
        this.bitrateBps = bitrateBps;
        this.frameRate = frameRate;
        this.keyFrameIntervalSeconds = keyFrameIntervalSeconds;
        this.maxFpsToEncoder = maxFpsToEncoder;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getBitrateBps() {
        return bitrateBps;
    }

    public int getFrameRate() {
        return frameRate;
    }

    public int getKeyFrameIntervalSeconds() {
        return keyFrameIntervalSeconds;
    }

    /**
     * Returns the maximum frame rate to feed the encoder, or zero when no cap was requested.
     */
    public float getMaxFpsToEncoder() {
        return maxFpsToEncoder;
    }

    /**
     * Returns the same configuration without the frame-rate cap.
     *
     * <p>Used as the retry after a codec rejects the cap key, so a stream that would otherwise run
     * never fails because of an optional request.</p>
     */
    public EncoderFormat withoutMaxFpsToEncoder() {
        return new EncoderFormat(width, height, bitrateBps, frameRate, keyFrameIntervalSeconds, 0f);
    }

    @Override
    public String toString() {
        return "EncoderFormat{width=" + width + ", height=" + height + ", bitrateBps=" + bitrateBps
                + ", frameRate=" + frameRate + ", keyFrameIntervalSeconds="
                + keyFrameIntervalSeconds + ", maxFpsToEncoder=" + maxFpsToEncoder + '}';
    }
}
