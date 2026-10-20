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
 * <p>No profile, level or B-frame field is carried here; the bridge supplies the remaining platform
 * keys itself, so the codec's own defaults apply to everything not listed above.</p>
 */
public final class EncoderFormat {

    private final int width;
    private final int height;
    private final int bitrateBps;
    private final int frameRate;
    private final int keyFrameIntervalSeconds;

    public EncoderFormat(int width, int height, int bitrateBps, int frameRate,
            int keyFrameIntervalSeconds) {
        this.width = width;
        this.height = height;
        this.bitrateBps = bitrateBps;
        this.frameRate = frameRate;
        this.keyFrameIntervalSeconds = keyFrameIntervalSeconds;
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
}
