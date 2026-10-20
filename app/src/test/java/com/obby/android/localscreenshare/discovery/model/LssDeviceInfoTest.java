package com.obby.android.localscreenshare.discovery.model;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class LssDeviceInfoTest {

    private static final String DEVICE_ID = "L7Q3K9M2F4X8B6N1C5V0Z3T7R2";

    @Test
    public void builderAppliesUnknownDefaults() {
        LssDeviceInfo device = LssDeviceInfo.builder().id(DEVICE_ID).build();

        assertEquals(DeviceRole.UNKNOWN, device.getRole());
        assertEquals(ShareState.UNKNOWN, device.getState());
        assertEquals(JoinPolicy.UNKNOWN, device.getJoin());
        assertEquals(AuthMode.UNKNOWN, device.getAuth());
        assertEquals(LssDeviceInfo.DEFAULT_PROTOCOL_VERSION, device.getProtocolVersion());
        assertEquals(DevicePresence.HEARD, device.getPresence());
        assertTrue(device.getAddresses().isEmpty());
        assertFalse(device.isSecure());
        assertFalse(device.isFavorite());
        assertFalse(device.isIdentityChanged());
        assertNull(device.getName());
        assertNull(device.primaryAddress());
    }

    @Test
    public void blankIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> LssDeviceInfo.builder().id(" ").build());
        assertThrows(IllegalArgumentException.class, () -> LssDeviceInfo.builder().id(null).build());
    }

    @Test
    public void blankNameIsNormalizedToNull() {
        LssDeviceInfo device = LssDeviceInfo.builder().id(DEVICE_ID).name("   ").build();

        assertNull(device.getName());
        assertEquals("LSS-L7Q3K9M2", device.displayName());
    }

    @Test
    public void displayNameFallsBackToShortId() {
        LssDeviceInfo unnamed = LssDeviceInfo.builder().id(DEVICE_ID).build();
        LssDeviceInfo named = LssDeviceInfo.builder().id(DEVICE_ID).name("Living room").build();

        assertEquals("L7Q3K9M2", unnamed.shortId());
        assertEquals("LSS-L7Q3K9M2", unnamed.displayName());
        assertEquals("Living room", named.displayName());
    }

    @Test
    public void shortIdHandlesIdsShorterThanThePrefix() {
        LssDeviceInfo device = LssDeviceInfo.builder().id("abc").build();

        assertEquals("ABC", device.shortId());
        assertEquals("LSS-ABC", device.displayName());
    }

    @Test
    public void addressesAreDefensivelyCopiedAndSorted() {
        List<DeviceAddress> source = new ArrayList<>(Arrays.asList(
            new DeviceAddress("[fe80::1]", 8080, DiscoveryChannel.NSD),
            new DeviceAddress("192.168.1.9", 8080, DiscoveryChannel.NSD)));

        LssDeviceInfo device = LssDeviceInfo.builder().id(DEVICE_ID).addresses(source).build();
        source.clear();

        assertEquals(2, device.getAddresses().size());
        assertEquals("192.168.1.9", device.primaryAddress().getHost());
        assertThrows(UnsupportedOperationException.class,
            () -> device.getAddresses().add(new DeviceAddress("10.0.0.1", 1, DiscoveryChannel.SCAN)));
    }

    @Test
    public void confirmedAddressesSortBeforeHeardOnes() {
        DeviceAddress heard = new DeviceAddress("192.168.1.2", 8080, DiscoveryChannel.NSD);
        DeviceAddress confirmed = new DeviceAddress("192.168.1.3", 8080, DiscoveryChannel.MULTICAST, true, 5_000L);

        LssDeviceInfo device = LssDeviceInfo.builder().id(DEVICE_ID)
            .addresses(Arrays.asList(heard, confirmed)).build();

        assertEquals("192.168.1.3", device.primaryAddress().getHost());
    }

    @Test
    public void familyIsDerivedFromLiteral() {
        assertEquals(DeviceAddress.FAMILY_IPV4,
            new DeviceAddress("10.0.0.1", 1, DiscoveryChannel.SCAN).getFamily());
        assertEquals(DeviceAddress.FAMILY_IPV6,
            new DeviceAddress("[2001:db8::1]", 1, DiscoveryChannel.SCAN).getFamily());
        assertEquals(DeviceAddress.FAMILY_IPV6,
            new DeviceAddress("2001:db8::1", 1, DiscoveryChannel.SCAN).getFamily());
    }

    @Test
    public void addressMergeKeyCombinesChannelHostAndPort() {
        DeviceAddress address = new DeviceAddress("10.0.0.1", 8080, DiscoveryChannel.NSD);

        assertEquals("NSD|10.0.0.1|8080", address.getKey());
        assertEquals(address, new DeviceAddress("10.0.0.1", 8080, DiscoveryChannel.NSD));
        assertNotEquals(address, new DeviceAddress("10.0.0.1", 8080, DiscoveryChannel.SCAN));
    }

    @Test
    public void blankHostAndInvalidPortAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new DeviceAddress(" ", 1, DiscoveryChannel.NSD));
        assertThrows(IllegalArgumentException.class,
            () -> new DeviceAddress("10.0.0.1", 70_000, DiscoveryChannel.NSD));
    }

    @Test
    public void valueEqualityCoversEveryField() {
        LssDeviceInfo first = LssDeviceInfo.builder().id(DEVICE_ID).name("A")
            .addresses(Collections.singletonList(new DeviceAddress("10.0.0.1", 8080, DiscoveryChannel.NSD)))
            .build();
        LssDeviceInfo same = LssDeviceInfo.builder().id(DEVICE_ID).name("A")
            .addresses(Collections.singletonList(new DeviceAddress("10.0.0.1", 8080, DiscoveryChannel.NSD)))
            .build();
        LssDeviceInfo other = LssDeviceInfo.builder().id(DEVICE_ID).name("B")
            .addresses(Collections.singletonList(new DeviceAddress("10.0.0.1", 8080, DiscoveryChannel.NSD)))
            .build();

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, other);
        assertFalse(first.equals(null));
    }
}
