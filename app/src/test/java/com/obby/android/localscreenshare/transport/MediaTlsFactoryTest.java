package com.obby.android.localscreenshare.transport;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the protocol selection of {@link MediaTlsFactory}.
 *
 * <p>Enabling a protocol version a socket does not implement throws, so the factory intersects the
 * supported set with its preference instead of assigning it. These tests pin that order and the
 * failure mode of a platform without any supported version.</p>
 */
public class MediaTlsFactoryTest {

    @Test
    public void commonProtocols_keepsThePreferredOrder() {
        String[] common = MediaTlsFactory.commonProtocols(
                new String[]{"TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3"});

        assertArrayEquals(new String[]{"TLSv1.3", "TLSv1.2"}, common);
    }

    @Test
    public void commonProtocols_keepsOnlyWhatThePlatformSupports() {
        assertArrayEquals(new String[]{"TLSv1.2"},
                MediaTlsFactory.commonProtocols(new String[]{"TLSv1.2"}));
        assertArrayEquals(new String[]{"TLSv1.3"},
                MediaTlsFactory.commonProtocols(new String[]{"TLSv1.3", "TLSv1.1"}));
    }

    @Test
    public void commonProtocols_rejectsAPlatformWithoutASupportedVersion() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> MediaTlsFactory.commonProtocols(new String[]{"TLSv1", "SSLv3"}));

        assertTrue(error.getMessage().contains("TLS"));
        assertTrue(Arrays.asList("TLSv1.3", "TLSv1.2").containsAll(
                Arrays.asList(MediaTlsFactory.commonProtocols(new String[]{"TLSv1.2"}))));
    }
}
