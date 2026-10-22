package com.obby.android.localscreenshare.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.obby.android.localscreenshare.core.audio.AudioCodecIds;
import com.obby.android.localscreenshare.core.codec.CodecIds;
import com.obby.android.localscreenshare.core.model.AudioCodecCapability;
import com.obby.android.localscreenshare.core.stream.ClientCapabilities;
import com.obby.android.localscreenshare.core.stream.CodecCapability;

import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unit tests for {@link ClientCapabilitiesFactory}.
 *
 * <p>They pin the one rule the HELLO depends on: JPEG is always advertised, because the viewer draws
 * it without a {@code MediaCodec} decoder, while every probed capability is preserved exactly as
 * found. The tests also pin that the caller's map is copied rather than modified, since the probe
 * result is reused by the media session.</p>
 */
public class ClientCapabilitiesFactoryTest {

    @Test
    public void h264OnlyProbe_isCompletedWithJpeg() {
        CodecCapability h264 = new CodecCapability(CodecIds.H264, "video/avc", "baseline", "4.0",
                1920, 1080, 60, 8_000_000, true, true);
        Map<String, CodecCapability> probed = new LinkedHashMap<>();
        probed.put(CodecIds.H264, h264);

        ClientCapabilities advertised = ClientCapabilitiesFactory.advertised(probed, null);

        assertTrue(advertised.supports(CodecIds.H264));
        assertTrue(advertised.supports(CodecIds.JPEG));
        assertEquals(2, advertised.getCodecIds().size());
        assertSame(h264, advertised.getCapability(CodecIds.H264));
        assertEquals(0, advertised.getCapability(CodecIds.JPEG).getMaxWidth());
        assertFalse(advertised.getCapability(CodecIds.JPEG).isSurfaceOutput());
        assertFalse("the probe result must be copied, not modified", probed.containsKey(CodecIds.JPEG));
    }

    @Test
    public void jpegAlreadyProbed_keepsItsDetails() {
        CodecCapability jpeg = new CodecCapability(CodecIds.JPEG, "image/jpeg", null, null,
                4096, 4096, 0, 0, false, false);
        Map<String, CodecCapability> probed = new LinkedHashMap<>();
        probed.put(CodecIds.H264, CodecCapability.any(CodecIds.H264));
        probed.put(CodecIds.JPEG, jpeg);

        ClientCapabilities advertised = ClientCapabilitiesFactory.advertised(probed,
                Collections.emptyMap());

        assertEquals(2, advertised.getCodecIds().size());
        assertSame(jpeg, advertised.getCapability(CodecIds.JPEG));
        assertEquals(4096, advertised.getCapability(CodecIds.JPEG).getMaxWidth());
    }

    @Test
    public void audioCapabilities_arePassedThrough() {
        AudioCodecCapability aac = new AudioCodecCapability(AudioCodecIds.AAC,
                AudioCodecIds.AAC_MIME, 48_000, 2, 320_000);
        Map<String, AudioCodecCapability> audio = new LinkedHashMap<>();
        audio.put(AudioCodecIds.AAC, aac);

        ClientCapabilities withAudio = ClientCapabilitiesFactory.advertised(
                Collections.singletonMap(CodecIds.H264, CodecCapability.any(CodecIds.H264)), audio);
        ClientCapabilities withoutAudio = ClientCapabilitiesFactory.advertised(
                Collections.singletonMap(CodecIds.H264, CodecCapability.any(CodecIds.H264)),
                Collections.emptyMap());

        assertTrue(withAudio.supportsAudio(AudioCodecIds.AAC));
        assertSame(aac, withAudio.getAudioCapability(AudioCodecIds.AAC));
        assertEquals(48_000, withAudio.getAudioCapability(AudioCodecIds.AAC).getMaxSampleRate());
        assertTrue(withoutAudio.getAudioCodecIds().isEmpty());
        assertTrue(withoutAudio.supports(CodecIds.JPEG));
    }

    @Test
    public void nullProbe_advertisesJpegOnly() {
        ClientCapabilities advertised = ClientCapabilitiesFactory.advertised(null, null);

        assertEquals(Collections.singleton(CodecIds.JPEG), advertised.getCodecIds());
        assertTrue(advertised.getAudioCodecIds().isEmpty());
        assertEquals(0, advertised.getCapability(CodecIds.JPEG).getMaxHeight());
    }
}
