package com.obby.android.localscreenshare.core.stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.obby.android.localscreenshare.core.model.AudioCodecCapability;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Unit tests for the audio side of {@link ClientCapabilities}.
 */
public class ClientCapabilitiesAudioTest {

    @Test
    public void videoOnlyCapabilitiesReportNoAudio() {
        ClientCapabilities capabilities =
                new ClientCapabilities(Collections.singleton("h264"));

        assertTrue(capabilities.getAudioCodecIds().isEmpty());
        assertTrue(capabilities.getAudioCapabilities().isEmpty());
        assertFalse(capabilities.supportsAudio("aac"));
        assertNull(capabilities.getAudioCapability("aac"));
        assertFalse(capabilities.supportsAudio(null));
        assertNull(capabilities.getAudioCapability(null));
    }

    @Test
    public void mapConstructorKeepsEmptyAudioMap() {
        Map<String, CodecCapability> video = new HashMap<>();
        video.put("h264", CodecCapability.any("h264"));

        ClientCapabilities capabilities = new ClientCapabilities(video);

        assertTrue(capabilities.supports("h264"));
        assertTrue(capabilities.getAudioCodecIds().isEmpty());
    }

    @Test
    public void audioCapabilitiesAreCarriedAndQueryable() {
        AudioCodecCapability aac = new AudioCodecCapability("aac", "audio/mp4a-latm",
                48_000, 2, 256_000);
        Map<String, AudioCodecCapability> audio = new HashMap<>();
        audio.put("aac", aac);

        ClientCapabilities capabilities =
                new ClientCapabilities(Collections.singletonMap("h264", null), audio);

        assertTrue(capabilities.supportsAudio("aac"));
        assertSame(aac, capabilities.getAudioCapability("aac"));
        assertEquals(Collections.singleton("aac"), capabilities.getAudioCodecIds());
        assertEquals(1, capabilities.getAudioCapabilities().size());
        assertFalse(capabilities.supportsAudio("opus"));
    }

    @Test
    public void nullAudioMapMeansNoAudioAndNullValueMeansUnconstrained() {
        Map<String, AudioCodecCapability> audio = new HashMap<>();
        audio.put("aac", null);

        ClientCapabilities capabilities = new ClientCapabilities(
                Collections.singletonMap("h264", null), audio);

        assertTrue(capabilities.supportsAudio("aac"));
        assertEquals(0, capabilities.getAudioCapability("aac").getMaxSampleRate());
        assertTrue(new ClientCapabilities(Collections.singletonMap("h264", null), null)
                .getAudioCodecIds().isEmpty());
    }

    @Test
    public void audioMapsAreUnmodifiable() {
        ClientCapabilities capabilities = new ClientCapabilities(
                Collections.singletonMap("h264", null),
                Collections.singletonMap("aac", AudioCodecCapability.any("aac")));

        assertThrows(UnsupportedOperationException.class,
                () -> capabilities.getAudioCapabilities().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> capabilities.getAudioCodecIds().clear());
    }

    @Test
    public void capabilityBoundsGateAcceptance() {
        AudioCodecCapability bounded = new AudioCodecCapability("aac", null, 44_100, 1, 96_000);
        AudioCodecCapability unconstrained = AudioCodecCapability.any("aac");

        assertTrue(bounded.accepts(44_100, 1, 96_000));
        assertFalse(bounded.accepts(48_000, 1, 96_000));
        assertFalse(bounded.accepts(44_100, 2, 96_000));
        assertFalse(bounded.accepts(44_100, 1, 128_000));
        assertTrue(unconstrained.accepts(48_000, 2, 192_000));
    }
}
