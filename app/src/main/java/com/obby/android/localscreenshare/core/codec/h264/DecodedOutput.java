package com.obby.android.localscreenshare.core.codec.h264;

public final class DecodedOutput {

    private final long ptsUs;
    private final boolean formatChanged;
    private final boolean endOfStream;

    public DecodedOutput(long ptsUs, boolean formatChanged, boolean endOfStream) {
        this.ptsUs = ptsUs;
        this.formatChanged = formatChanged;
        this.endOfStream = endOfStream;
    }

    public long getPtsUs() {
        return ptsUs;
    }

    public boolean isFormatChanged() {
        return formatChanged;
    }

    public boolean isEndOfStream() {
        return endOfStream;
    }
}
