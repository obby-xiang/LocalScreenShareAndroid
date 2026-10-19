package com.obby.android.localscreenshare.core.codec.h264;

import android.view.Surface;

/**
 * Immutable decoder configuration handed to
 * {@link MediaCodecDecoderBridge#configure(DecoderFormat)}.
 *
 * <p>The two CSD blobs are Annex B parameter-set NAL units, start code included, produced by
 * {@link H264ConfigExtractor#toAnnexB(byte[])}. Config parts themselves are stored without start
 * codes, but the platform decoder expects Annex B here. The sizes are the descriptor's coded size,
 * not its visible size.</p>
 *
 * <p>The output surface is the render target's current surface; it is never owned or released by
 * the decoder. It may be {@code null} only for JVM fakes: the Android bridge rejects a null surface
 * at configure time, so a missing surface must be resolved by re-creating the decoder rather than by
 * mutating this format.</p>
 *
 * <p>Byte arrays are copied on the way in and on the way out.</p>
 */
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
