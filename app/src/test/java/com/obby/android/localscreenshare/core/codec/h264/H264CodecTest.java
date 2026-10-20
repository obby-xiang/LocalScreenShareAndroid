package com.obby.android.localscreenshare.core.codec.h264;

import com.obby.android.localscreenshare.core.codec.CodecIds;
import com.obby.android.localscreenshare.core.codec.CodecRegistration;
import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.codec.jpeg.JpegCodec;
import com.obby.android.localscreenshare.core.codec.mediacodec.MediaCodecBridgeFactory;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.support.FakeMediaCodecBridgeFactory;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for the H.264 codec registration produced by
 * {@link H264Codec#registration(MediaCodecBridgeFactory)}.
 */
public class H264CodecTest {

    private static final int EXPECTED_FALLBACK_PRIORITY = 10;

    @Test
    public void registration_hasH264DescriptorAndSurfaceFlags() {
        CodecRegistration registration =
                H264Codec.registration(new FakeMediaCodecBridgeFactory());

        assertEquals(CodecIds.H264, registration.getDescriptor().getCodecId());
        assertEquals("video/avc", registration.getDescriptor().getMime());
        assertEquals(H264Codec.MIME, registration.getDescriptor().getMime());
        assertEquals(BitstreamFormat.ANNEX_B, registration.getDescriptor().getBitstreamFormat());
        assertTrue(registration.getDescriptor().isSurfaceInputRequired());
        assertTrue(registration.getDescriptor().isSurfaceOutputRequired());
        assertTrue(registration.getDescriptor().hasConfigParts());
        assertEquals(EXPECTED_FALLBACK_PRIORITY, registration.getFallbackPriority());
        assertEquals("sps", H264Codec.CONFIG_PART_SPS);
        assertEquals("pps", H264Codec.CONFIG_PART_PPS);
    }

    @Test
    public void registration_usesH264PolicyAndFactories() {
        FakeMediaCodecBridgeFactory bridgeFactory = new FakeMediaCodecBridgeFactory();

        CodecRegistration registration = H264Codec.registration(bridgeFactory);

        assertTrue(registration.getPolicy() instanceof H264CodecPolicy);
        assertTrue(registration.getEncoderFactory() instanceof H264EncoderFactory);
        assertTrue(registration.getDecoderFactory() instanceof H264DecoderFactory);
    }

    @Test
    public void registration_doesNotChangeWithDefaults() {
        assertEquals(1, CodecRegistry.withDefaults().getCodecIds().size());
        assertTrue(CodecRegistry.withDefaults().contains(CodecIds.JPEG));

        H264Codec.registration(new FakeMediaCodecBridgeFactory());

        assertEquals(1, CodecRegistry.withDefaults().getCodecIds().size());
        assertTrue(CodecRegistry.withDefaults().contains(CodecIds.JPEG));
        assertFalse(CodecRegistry.withDefaults().contains(CodecIds.H264));
    }

    @Test
    public void registryWithJpegAndH264_exposesBoth() {
        CodecRegistry registry = new CodecRegistry();
        registry.register(JpegCodec.registration());
        CodecRegistration h264 = H264Codec.registration(new FakeMediaCodecBridgeFactory());
        registry.register(h264);

        assertEquals(2, registry.getCodecIds().size());
        assertTrue(registry.contains(CodecIds.JPEG));
        assertTrue(registry.contains(CodecIds.H264));
        assertSame(h264, registry.require(CodecIds.H264));
        assertFalse(CodecRegistry.withDefaults().contains(CodecIds.H264));
    }
}
