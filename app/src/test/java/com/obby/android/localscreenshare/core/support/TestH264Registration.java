package com.obby.android.localscreenshare.core.support;

import com.obby.android.localscreenshare.core.codec.CodecDescriptor;
import com.obby.android.localscreenshare.core.codec.CodecRegistration;
import com.obby.android.localscreenshare.core.codec.h264.H264Codec;
import com.obby.android.localscreenshare.core.codec.h264.MediaCodecBridgeFactory;

/**
 * Builds an H.264 registration whose surface requirements are disabled for JVM tests.
 *
 * <p>The test JVM cannot create an Android {@code Surface}, so client policy tests that need a
 * decoder instance would otherwise be blocked by the surface gate. This helper keeps the H.264
 * encoder/decoder factories, policy, codec descriptor (mime, Annex B, config parts) and fallback
 * priority, and clears both {@code surfaceInputRequired} and {@code surfaceOutputRequired}.</p>
 *
 * <p>This is not evidence about real Surface behaviour: it neither proves that a real Surface is
 * available nor that a missing Surface blocks or unblocks decoding, and it says nothing about
 * Surface availability changes. Surface-backed decoding must be covered by device tests.</p>
 */
public final class TestH264Registration {

    public static CodecRegistration withoutSurfaceRequirement(
            MediaCodecBridgeFactory bridgeFactory) {
        CodecRegistration base = H264Codec.registration(bridgeFactory);
        CodecDescriptor descriptor = base.getDescriptor();
        CodecDescriptor surfaceFree = new CodecDescriptor(
                descriptor.getCodecId(),
                descriptor.getMime(),
                descriptor.getBitstreamFormat(),
                false,
                false,
                descriptor.hasConfigParts());
        return new CodecRegistration(surfaceFree, base.getEncoderFactory(),
                base.getDecoderFactory(), base.getPolicy(), base.getFallbackPriority());
    }

    private TestH264Registration() {
    }
}
