package com.obby.android.localscreenshare.core.codec.h264;

public final class EncodedSample {

    private final byte[] data;
    private final long ptsUs;
    private final boolean keyFrame;
    private final boolean codecConfig;
    private final boolean formatChanged;
    private final boolean endOfStream;

    public EncodedSample(byte[] data, long ptsUs, boolean keyFrame, boolean codecConfig,
            boolean formatChanged, boolean endOfStream) {
        this.data = data == null ? null : data.clone();
        this.ptsUs = ptsUs;
        this.keyFrame = keyFrame;
        this.codecConfig = codecConfig;
        this.formatChanged = formatChanged;
        this.endOfStream = endOfStream;
    }

    public byte[] getData() {
        return data == null ? null : data.clone();
    }

    public long getPtsUs() {
        return ptsUs;
    }

    public boolean isKeyFrame() {
        return keyFrame;
    }

    public boolean isCodecConfig() {
        return codecConfig;
    }

    public boolean isFormatChanged() {
        return formatChanged;
    }

    public boolean isEndOfStream() {
        return endOfStream;
    }
}
