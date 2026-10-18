package com.obby.android.localscreenshare.core.codec.h264;

import com.obby.android.localscreenshare.core.model.ColorInfo;

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
