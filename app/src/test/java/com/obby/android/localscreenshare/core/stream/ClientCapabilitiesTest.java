package com.obby.android.localscreenshare.core.stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/** Unit tests for {@link ClientCapabilities} and {@link CodecCapability}. */
public class ClientCapabilitiesTest {

    @Test
    public void setConstructorTreatsIdsAsUnconstrained() {
        ClientCapabilities caps = new ClientCapabilities(java.util.Collections.singleton("h264"));

        assertTrue(caps.supports("h264"));
        assertFalse(caps.supports("jpeg"));
        assertNull(caps.getCapability("jpeg"));
        assertFalse(caps.getCapability("h264").isSurfaceOutput());
        assertEquals(0, caps.getCapability("h264").getMaxWidth());
    }

    @Test
    public void mapConstructorCarriesFullCapability() {
        CodecCapability h264 = new CodecCapability("h264", "video/avc", "baseline", "3.1",
                1920, 1080, 60, 8_000_000, true, false);
        Map<String, CodecCapability> map = new HashMap<>();
        map.put("h264", h264);
        ClientCapabilities caps = new ClientCapabilities(map);

        assertSame(h264, caps.getCapability("h264"));
        assertEquals(1920, caps.getCapability("h264").getMaxWidth());
        assertTrue(caps.getCapability("h264").isSurfaceOutput());
        assertEquals(java.util.Collections.singleton("h264"), caps.getCodecIds());
    }

    @Test
    public void capabilitiesAreUnmodifiable() {
        Map<String, CodecCapability> map = new HashMap<>();
        map.put("h264", CodecCapability.any("h264"));
        ClientCapabilities caps = new ClientCapabilities(map);

        assertThrows(caps.getCapabilities());
        assertThrows(caps.getCodecIds());
    }

    private static void assertThrows(java.util.Map<?, ?> map) {
        try {
            map.clear();
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("expected UnsupportedOperationException");
    }

    private static void assertThrows(java.util.Set<?> set) {
        try {
            set.clear();
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("expected UnsupportedOperationException");
    }
}
