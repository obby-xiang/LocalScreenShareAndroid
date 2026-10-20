package com.obby.android.localscreenshare.discovery.scan;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class InterfaceFilterTest {

    @Test
    public void wifiAndEthernetAreEligible() {
        assertTrue(InterfaceFilter.isEligible(wifi("wlan0", "192.168.1.5", 24)));
        assertTrue(InterfaceFilter.isEligible(
            LocalInterface.ipv4("eth0", "10.0.0.5", 22, LocalInterface.Transport.ETHERNET)));
    }

    @Test
    public void vpnAndCellularAreExcluded() {
        assertFalse(InterfaceFilter.isEligible(
            LocalInterface.ipv4("tun0", "10.8.0.2", 24, LocalInterface.Transport.VPN)));
        assertFalse(InterfaceFilter.isEligible(
            LocalInterface.ipv4("wlan1", "10.20.0.5", 24, LocalInterface.Transport.CELLULAR)));
    }

    @Test
    public void loopbackAndPointToPointAreExcluded() {
        assertFalse(InterfaceFilter.isEligible(new LocalInterface("lo", ipv4("127.0.0.1"), 8,
            LocalInterface.Transport.OTHER, false, true)));
        assertFalse(InterfaceFilter.isEligible(new LocalInterface("wlan0", ipv4("10.0.0.5"), 24,
            LocalInterface.Transport.WIFI, true, false)));
    }

    @Test
    public void tunnelAndVirtualNamesAreExcludedEvenWhenTheTransportLooksEligible() {
        for (final String name : new String[] {"tun0", "ppp0", "rmnet_data0", "dummy0", "p2p0",
            "P2P-wlan0-0"}) {
            assertTrue(name, InterfaceFilter.hasExcludedName(name));
            assertFalse(name, InterfaceFilter.isEligible(wifi(name, "10.0.0.5", 24)));
        }
        assertTrue(InterfaceFilter.hasExcludedName(null));
        assertTrue(InterfaceFilter.hasExcludedName("  "));
        assertFalse(InterfaceFilter.hasExcludedName("wlan0"));
    }

    @Test
    public void eligiblePreservesOrder() {
        final List<LocalInterface> candidates = new ArrayList<>();
        candidates.add(wifi("wlan0", "192.168.1.5", 24));
        candidates.add(LocalInterface.ipv4("tun0", "10.8.0.2", 24, LocalInterface.Transport.VPN));
        candidates.add(LocalInterface.ipv4("eth0", "10.0.0.9", 24,
            LocalInterface.Transport.ETHERNET));

        final List<LocalInterface> eligible = InterfaceFilter.eligible(candidates);

        assertEquals(2, eligible.size());
        assertEquals("wlan0", eligible.get(0).getName());
        assertEquals("eth0", eligible.get(1).getName());
    }

    @Test
    public void anIpv6OnlyInterfaceProducesNoCandidate() {
        // Enumeration is IPv4-only: a link with no IPv4 address is simply absent from the list,
        // so it can never become a scan target.
        assertTrue(InterfaceFilter.eligible(new ArrayList<>()).isEmpty());
    }

    @Test
    public void prefixLengthIsValidated() {
        try {
            new LocalInterface("wlan0", ipv4("10.0.0.5"), 33, LocalInterface.Transport.WIFI,
                false, false);
            org.junit.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("prefixLength out of range"));
        }
    }

    private static LocalInterface wifi(final String name, final String address, final int prefix) {
        return LocalInterface.ipv4(name, address, prefix, LocalInterface.Transport.WIFI);
    }

    private static java.net.Inet4Address ipv4(final String literal) {
        try {
            return (java.net.Inet4Address) java.net.InetAddress.getByName(literal);
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException(literal, e);
        }
    }
}
