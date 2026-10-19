package com.obby.android.localscreenshare.core.codec.h264;

/**
 * One encoder output from {@link MediaCodecEncoderBridge#dequeueOutput(long)}.
 *
 * <p>The flags mirror the platform buffer flags and describe the sample, not the stream: a control
 * entry normally sets exactly one of {@link #isFormatChanged()}, {@link #isCodecConfig()} or
 * {@link #isEndOfStream()}, while a displayable sample has all three {@code false}.
 * {@link #isKeyFrame()} is the codec's own keyframe flag and is treated by the H.264 adapter as a
 * diagnostic hint only: a codec may flag a non-IDR I-frame as a key frame, so joinability is always
 * re-derived from the payload.</p>
 *
 * <p>{@code data} is {@code null} for a format-change marker, holds the encoded bytes otherwise, and
 * is copied defensively. Presentation timestamps are in microseconds.</p>
 */
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
