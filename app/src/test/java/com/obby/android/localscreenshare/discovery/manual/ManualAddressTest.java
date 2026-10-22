package com.obby.android.localscreenshare.discovery.manual;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ManualAddressTest {

    private static final int DEFAULT_PORT = 8080;

    @Test
    public void ipv4WithoutPortUsesTheDefault() {
        final ManualAddress address = ManualAddress.parse("192.168.1.7", DEFAULT_PORT);

        assertEquals("192.168.1.7", address.getHost());
        assertEquals(DEFAULT_PORT, address.getPort());
        assertFalse(address.isIpv6());
    }

    @Test
    public void ipv4WithPortUsesTheGivenPort() {
        final ManualAddress address = ManualAddress.parse(" 192.168.1.7:9090 ", DEFAULT_PORT);

        assertEquals("192.168.1.7", address.getHost());
        assertEquals(9090, address.getPort());
    }

    @Test
    public void bracketedIpv6WithPortIsAccepted() {
        final ManualAddress address = ManualAddress.parse("[fe80::1]:9090", DEFAULT_PORT);

        assertEquals("fe80::1", address.getHost());
        assertEquals(9090, address.getPort());
        assertTrue(address.isIpv6());
        assertEquals("[fe80::1]", address.displayHost());
    }

    @Test
    public void bracketedIpv6WithoutPortUsesTheDefault() {
        final ManualAddress address = ManualAddress.parse("[2001:db8::5]", DEFAULT_PORT);

        assertEquals("2001:db8::5", address.getHost());
        assertEquals(DEFAULT_PORT, address.getPort());
        assertTrue(address.isIpv6());
    }

    @Test
    public void bareIpv6UsesTheDefaultPort() {
        final ManualAddress address = ManualAddress.parse("fe80::1", DEFAULT_PORT);

        assertEquals("fe80::1", address.getHost());
        assertEquals(DEFAULT_PORT, address.getPort());
        assertTrue(address.isIpv6());
    }

    @Test
    public void hostNamesAreRejected() {
        assertInvalid("example.local");
        assertInvalid("my-nas:8080");
    }

    @Test
    public void invalidPortsAreRejected() {
        assertInvalid("192.168.1.7:0");
        assertInvalid("192.168.1.7:70000");
        assertInvalid("192.168.1.7:abc");
        assertInvalid("192.168.1.7:");
        assertInvalid("[fe80::1]:99999");
        assertInvalid("[fe80::1]extra");
    }

    @Test
    public void invalidLiteralsAreRejected() {
        assertInvalid("");
        assertInvalid("   ");
        assertInvalid("192.168.1");
        assertInvalid("192.168.1.256");
        assertInvalid("192.168.1.7.8");
        assertInvalid("[fe80::1");
        assertInvalid("[]");
    }

    @Test
    public void defaultPortIsValidated() {
        try {
            ManualAddress.parse("192.168.1.7", 0);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("default port out of range"));
        }
    }

    @Test
    public void createAcceptsStoredLiteralsWithoutTheTypedGrammar() {
        final ManualAddress ipv4 = ManualAddress.create("10.0.0.5", 8080);
        final ManualAddress ipv6 = ManualAddress.create("FE80::1", 9090);

        assertEquals("10.0.0.5", ipv4.getHost());
        assertEquals(8080, ipv4.getPort());
        assertFalse(ipv4.isIpv6());
        assertEquals("fe80::1", ipv6.getHost());
        assertEquals(9090, ipv6.getPort());
        assertTrue(ipv6.isIpv6());
        assertEquals("[fe80::1]:9090", ipv6.toString());
    }

    @Test
    public void createRejectsBlankHostsNonLiteralsAndBadPorts() {
        assertCreateRejected("", 8080);
        assertCreateRejected("   ", 8080);
        assertCreateRejected("example.local", 8080);
        assertCreateRejected("10.0.0.5", 0);
        assertCreateRejected("10.0.0.5", 70000);
    }

    private static void assertCreateRejected(final String host, final int port) {
        try {
            ManualAddress.create(host, port);
            fail("expected IllegalArgumentException for " + host + ":" + port);
        } catch (IllegalArgumentException expected) {
            // Expected: a stored address must still be a literal with a usable port.
        }
    }

    private static void assertInvalid(final String input) {
        try {
            ManualAddress.parse(input, DEFAULT_PORT);
            fail("expected IllegalArgumentException for " + input);
        } catch (IllegalArgumentException expected) {
            // Expected: the input is not a literal address with an optional port.
        }
    }
}
