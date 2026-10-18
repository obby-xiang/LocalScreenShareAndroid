package com.obby.android.localscreenshare.core.codec.h264;

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
