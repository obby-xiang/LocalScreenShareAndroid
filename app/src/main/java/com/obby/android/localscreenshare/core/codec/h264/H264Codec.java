package com.obby.android.localscreenshare.core.codec.h264;

import com.obby.android.localscreenshare.core.codec.CodecDescriptor;
import com.obby.android.localscreenshare.core.codec.CodecIds;
import com.obby.android.localscreenshare.core.codec.CodecRegistration;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;

/**
 * Static registration entry point for the H.264 (AVC) codec.
 *
 * <p>{@link #registration(MediaCodecBridgeFactory)} builds the H.264 codec descriptor and wires the
 * encoder factory, decoder factory and policy to one injected {@link MediaCodecBridgeFactory}. The
 * registration is returned to the caller and is deliberately <b>not</b> registered into
 * {@code CodecRegistry.withDefaults()}: H.264 requires a surface and a media-codec bridge that the
 * JVM cannot provide, so it must be opted into explicitly rather than added to the JPEG-only
 * default registry.</p>
 *
 * <p>{@code androidRegistration()} is intentionally absent from this batch; the Android-backed
 * registration belongs to a later task.</p>
 */
public final class H264Codec {

    public static final String MIME = "video/avc";

    public static final String CONFIG_PART_SPS = "sps";

    public static final String CONFIG_PART_PPS = "pps";

    private H264Codec() {
    }

    /**
     * Builds the H.264 codec registration around {@code bridgeFactory}.
     *
     * <p>Both surface flags are {@code true}: H.264 is decoded onto an output {@code Surface} and,
     * on the encode side, samples are produced from an input {@code Surface}. The codec carries its
     * decode configuration as SPS/PPS config parts (rather than deriving it lazily), which is why
     * {@code carriesConfigParts} is set.</p>
     */
    public static CodecRegistration registration(MediaCodecBridgeFactory bridgeFactory) {
        if (bridgeFactory == null) {
            throw new IllegalArgumentException("bridgeFactory must not be null");
        }
        CodecDescriptor descriptor = new CodecDescriptor(CodecIds.H264, MIME,
                BitstreamFormat.ANNEX_B, true, true, true);
        return new CodecRegistration(descriptor, new H264EncoderFactory(bridgeFactory),
                new H264DecoderFactory(bridgeFactory), new H264CodecPolicy(), 10);
    }
}
