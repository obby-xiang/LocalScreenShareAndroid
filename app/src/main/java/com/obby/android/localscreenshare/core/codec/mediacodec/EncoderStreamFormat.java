package com.obby.android.localscreenshare.core.codec.mediacodec;

import com.obby.android.localscreenshare.core.model.ColorInfo;

/**
 * Snapshot of the encoder's output format, returned by
 * {@link MediaCodecEncoderBridge#getOutputFormat()}.
 *
 * <p>This is a data carrier only: it deliberately does not validate the size, so a fake can produce
 * a non-positive size and let {@code H264Encoder} treat it as a terminal configuration failure
 * instead of silently falling back to the requested coded size. The CSD blobs are the platform's
 * {@code csd-0}/{@code csd-1} bytes, which may be absent or use AVCC framing; the config extractor
 * normalizes them. Color may be {@link ColorInfo#UNSPECIFIED} when the platform reports none.</p>
 *
 * <p>Byte arrays are copied defensively and the instance is immutable.</p>
 */
public final class EncoderStreamFormat {

    private final byte[] csd0;
    private final byte[] csd1;
    private final int width;
    private final int height;
    private final ColorInfo colorInfo;

    public EncoderStreamFormat(byte[] csd0, byte[] csd1, int width, int height,
            ColorInfo colorInfo) {
        this.csd0 = csd0 == null ? null : csd0.clone();
        this.csd1 = csd1 == null ? null : csd1.clone();
        this.width = width;
        this.height = height;
        this.colorInfo = colorInfo;
    }

    public byte[] getCsd0() {
        return csd0 == null ? null : csd0.clone();
    }

    public byte[] getCsd1() {
        return csd1 == null ? null : csd1.clone();
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public ColorInfo getColorInfo() {
        return colorInfo;
    }
}
