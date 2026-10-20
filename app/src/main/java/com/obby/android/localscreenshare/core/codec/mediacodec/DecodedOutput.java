package com.obby.android.localscreenshare.core.codec.mediacodec;

/**
 * Outcome of one {@link MediaCodecDecoderBridge#dequeueOutput(long)} call.
 *
 * <p>A non-null instance reports exactly one of three things: an output-format change, an
 * end-of-stream buffer, or a decoded frame. The frame has already been rendered to the configured
 * surface by the bridge, so no pixel data is exposed here; only its presentation timestamp is
 * carried. A format-change entry carries no frame and reports a timestamp of {@code 0}.</p>
 *
 * <p>Instances are immutable and own no resources.</p>
 */
public final class DecodedOutput {

    private final long ptsUs;
    private final boolean formatChanged;
    private final boolean endOfStream;

    public DecodedOutput(long ptsUs, boolean formatChanged, boolean endOfStream) {
        this.ptsUs = ptsUs;
        this.formatChanged = formatChanged;
        this.endOfStream = endOfStream;
    }

    /**
     * Presentation timestamp in microseconds, or {@code 0} for a format-change entry.
     */
    public long getPtsUs() {
        return ptsUs;
    }

    /**
     * True when this entry only announces an output-format change and carries no frame.
     */
    public boolean isFormatChanged() {
        return formatChanged;
    }

    /**
     * True when the codec reported the end of the stream.
     */
    public boolean isEndOfStream() {
        return endOfStream;
    }
}
