package com.obby.android.localscreenshare.core.codec.h264;

import com.obby.android.localscreenshare.core.codec.CodecDescriptor;
import com.obby.android.localscreenshare.core.codec.CodecIds;
import com.obby.android.localscreenshare.core.codec.CodecRegistration;
import com.obby.android.localscreenshare.core.codec.KeyFrameRequester;
import com.obby.android.localscreenshare.core.codec.mediacodec.AndroidMediaCodecBridgeFactory;
import com.obby.android.localscreenshare.core.codec.mediacodec.MediaCodecBridgeFactory;
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
 * <p>{@link #registration(MediaCodecBridgeFactory)} is the injected-bridge entry point used by JVM
 * tests to supply a fake or any custom bridge; {@link #androidRegistration()} is the production
 * entry point and delegates to the same builder with an {@link AndroidMediaCodecBridgeFactory}, so
 * both produce registrations with identical fields. {@code CodecRegistry.withDefaults()} still
 * registers only JPEG, so H.264 is never added by default. This batch does not connect the legacy
 * screen-share path: {@code MainActivity}, {@code service}, {@code client} and {@code server} are
 * not modified or integrated.</p>
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
     *
     * <p>The fallback priority is {@code 10}, lower than the JPEG registration's {@code 100}, so a
     * hub proposing an explicit common fallback prefers H.264 when both are registered. The priority
     * is metadata for that proposal only; it never switches an active stream by itself.</p>
     */
    public static CodecRegistration registration(MediaCodecBridgeFactory bridgeFactory) {
        return registration(bridgeFactory, KeyFrameRequester.NONE);
    }

    /**
     * Builds the H.264 codec registration whose decoders report dropped frames.
     *
     * <p>The requester travels with the registration, so every decoder created by the client
     * session from this registration reports its need for a fresh random-access point without the
     * session taking a dependency on the client endpoint.</p>
     */
    public static CodecRegistration registration(MediaCodecBridgeFactory bridgeFactory,
                                                 KeyFrameRequester keyFrameRequester) {
        if (bridgeFactory == null) {
            throw new IllegalArgumentException("bridgeFactory must not be null");
        }
        if (keyFrameRequester == null) {
            throw new IllegalArgumentException("keyFrameRequester must not be null");
        }
        CodecDescriptor descriptor = new CodecDescriptor(CodecIds.H264, MIME,
                BitstreamFormat.ANNEX_B, true, true, true);
        return new CodecRegistration(descriptor, new H264EncoderFactory(bridgeFactory),
                new H264DecoderFactory(bridgeFactory, keyFrameRequester),
                new H264CodecPolicy(), 10);
    }

    /**
     * Builds the Android-backed H.264 registration for production use.
     *
     * <p>Delegates to {@link #registration(MediaCodecBridgeFactory)} with an
     * {@link AndroidMediaCodecBridgeFactory}, so the descriptor (codec id, mime, bitstream format,
     * both surface flags and the config-parts flag), the encoder/decoder factories, the policy and
     * the fallback priority are identical to an injected-factory registration. As with
     * {@link #registration(MediaCodecBridgeFactory)}, the result is returned to the caller and is
     * deliberately <b>not</b> registered into {@code CodecRegistry.withDefaults()}.</p>
     */
    public static CodecRegistration androidRegistration() {
        return registration(new AndroidMediaCodecBridgeFactory());
    }

    /**
     * Builds the production registration whose decoders report dropped frames.
     *
     * <p>Used by the viewer service so a decoder that had to shed a frame asks the server for a
     * fresh join point instead of waiting for the next periodic key frame.</p>
     */
    public static CodecRegistration androidRegistration(
            final KeyFrameRequester keyFrameRequester) {
        return registration(new AndroidMediaCodecBridgeFactory(), keyFrameRequester);
    }
}
