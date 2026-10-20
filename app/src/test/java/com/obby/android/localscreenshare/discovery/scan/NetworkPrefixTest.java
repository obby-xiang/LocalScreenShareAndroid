package com.obby.android.localscreenshare.discovery.scan;

import org.junit.Test;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class NetworkPrefixTest {

    @Test
    public void slash24EnumeratesUsableHostsAndSkipsItself() {
        final NetworkPrefix.Result result = NetworkPrefix.candidates(ip("192.168.1.50"), 24);

        assertFalse(result.isPartial());
        assertEquals(253, result.size());
        assertEquals("192.168.1.1", host(result, 0));
        assertEquals("192.168.1.254", host(result, result.size() - 1));
        assertFalse(result.getAddresses().contains(ip("192.168.1.50")));
        assertFalse(result.getAddresses().contains(ip("192.168.1.0")));
        assertFalse(result.getAddresses().contains(ip("192.168.1.255")));
    }

    @Test
    public void slash22KeepsNonStandardMasks() {
        final NetworkPrefix.Result result = NetworkPrefix.candidates(ip("10.0.0.5"), 22);

        assertFalse(result.isPartial());
        assertEquals(1021, result.size());
        assertEquals("10.0.0.1", host(result, 0));
        assertEquals("10.0.3.254", host(result, result.size() - 1));
        assertTrue(result.getAddresses().contains(ip("10.0.2.7")));
        assertFalse(result.getAddresses().contains(ip("10.0.0.5")));
    }

    @Test
    public void slash16FallsBackToLocalSlash24AndIsMarkedPartial() {
        final NetworkPrefix.Result result = NetworkPrefix.candidates(ip("10.1.2.3"), 16);

        assertTrue(result.isPartial());
        assertEquals(253, result.size());
        assertEquals("10.1.2.1", host(result, 0));
        assertEquals("10.1.2.254", host(result, result.size() - 1));
        assertTrue(result.getAddresses().contains(ip("10.1.2.200")));
        assertFalse(result.getAddresses().contains(ip("10.1.3.200")));
    }

    @Test
    public void slash23StaysWithinTheDefaultBound() {
        final NetworkPrefix.Result result = NetworkPrefix.candidates(ip("10.0.1.1"), 23);

        assertFalse(result.isPartial());
        assertEquals(509, result.size());
        assertEquals("10.0.0.1", host(result, 0));
        assertEquals("10.0.1.254", host(result, result.size() - 1));
    }

    @Test
    public void slash31AndSlash32HaveNoUsableHosts() {
        assertEquals(0, NetworkPrefix.candidates(ip("10.0.0.0"), 31).size());
        assertEquals(0, NetworkPrefix.candidates(ip("10.0.0.7"), 32).size());
    }

    @Test
    public void customBoundTruncatesEvenTheSlash24Fallback() {
        final NetworkPrefix.Result result = NetworkPrefix.candidates(ip("192.168.1.50"), 24, 100);

        assertTrue(result.isPartial());
        assertEquals(100, result.size());
    }

    @Test
    public void invalidPrefixLengthIsRejected() {
        assertIllegalArgument(-1);
        assertIllegalArgument(33);
    }

    @Test
    public void nonPositiveBoundIsRejected() {
        try {
            NetworkPrefix.candidates(ip("10.0.0.1"), 24, 0);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertEquals("maxHosts must be positive: 0", expected.getMessage());
        }
    }

    private static void assertIllegalArgument(final int prefixLength) {
        try {
            NetworkPrefix.candidates(ip("10.0.0.1"), prefixLength);
            fail("expected IllegalArgumentException for prefix " + prefixLength);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("prefixLength out of range"));
        }
    }

    private static String host(final NetworkPrefix.Result result, final int index) {
        return result.getAddresses().get(index).getHostAddress();
    }

    private static Inet4Address ip(final String literal) {
        try {
            return (Inet4Address) InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(literal, e);
        }
    }
}
