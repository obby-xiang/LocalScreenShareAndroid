package com.obby.android.localscreenshare.core.codec.h264;

import android.view.Surface;

public final class DecoderFormat {

    private final int width;
    private final int height;
    private final byte[] csd0;
    private final byte[] csd1;
    private final Surface outputSurface;

    public DecoderFormat(int width, int height, byte[] csd0, byte[] csd1, Surface outputSurface) {
        this.width = width;
        this.height = height;
        this.csd0 = csd0 == null ? null : csd0.clone();
        this.csd1 = csd1 == null ? null : csd1.clone();
        this.outputSurface = outputSurface;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public byte[] getCsd0() {
        return csd0 == null ? null : csd0.clone();
    }

    public byte[] getCsd1() {
        return csd1 == null ? null : csd1.clone();
    }

    public Surface getOutputSurface() {
        return outputSurface;
    }
}
