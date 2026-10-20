package com.obby.android.localscreenshare.discovery.store;

import com.obby.android.localscreenshare.core.support.MutableClock;
import com.obby.android.localscreenshare.discovery.model.AuthMode;
import com.obby.android.localscreenshare.discovery.model.DevicePresence;
import com.obby.android.localscreenshare.discovery.model.DeviceRole;
import com.obby.android.localscreenshare.discovery.model.DiscoveryChannel;
import com.obby.android.localscreenshare.discovery.model.JoinPolicy;
import com.obby.android.localscreenshare.discovery.model.LssDeviceInfo;
import com.obby.android.localscreenshare.discovery.model.ShareState;
import com.obby.android.localscreenshare.discovery.provider.DeviceObservation;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DeviceStoreMergeTest {

    private static final String DEVICE_ID = "L7Q3K9M2F4X8B6N1C5V0Z3T7R2";

    private MutableClock mClock;

    private DeviceStore mStore;

    @Before
    public void setUp() {
        mClock = new MutableClock(10_000L);
        mStore = new DeviceStore(mClock);
    }

    @Test
    public void multiChannelObservationsMergeIntoOneEntry() {
        mStore.onObserved(observed(DiscoveryChannel.NSD, DEVICE_ID, "10.0.0.5", 8080, mClock.nowMs()));
        mStore.onObserved(observed(DiscoveryChannel.MULTICAST, DEVICE_ID, "192.168.1.7", 9090, mClock.nowMs()));

        assertEquals(1, mStore.size());
        LssDeviceInfo device = mStore.device(DEVICE_ID);
        assertEquals(2, device.getAddresses().size());
        assertEquals(DevicePresence.HEARD, device.getPresence());
    }

    @Test
    public void confirmedAddressIsPreferredOverNewerHeardAddress() {
        mStore.onObserved(observed(DiscoveryChannel.NSD, DEVICE_ID, "10.0.0.5", 8080, mClock.nowMs()));
        mStore.onObserved(observed(DiscoveryChannel.MULTICAST, DEVICE_ID, "192.168.1.7", 9090, mClock.nowMs()));
        mStore.confirm(DEVICE_ID, DiscoveryChannel.MULTICAST, "192.168.1.7", 9090);

        LssDeviceInfo device = mStore.device(DEVICE_ID);

        assertEquals("192.168.1.7", device.primaryAddress().getHost());
        assertTrue(device.primaryAddress().isConfirmed());
        assertEquals(DevicePresence.CONFIRMED, device.getPresence());
    }

    @Test
    public void sparseLegacyObservationDoesNotEraseRicherFields() {
        mStore.onObserved(DeviceObservation.builder()
            .source(DiscoveryChannel.NSD)
            .id(DEVICE_ID)
            .name("Kitchen")
            .role(DeviceRole.SERVER)
            .state(ShareState.LIVE)
            .join(JoinPolicy.OPEN)
            .secure(true)
            .auth(AuthMode.NONE)
            .protocolVersion("1.0")
            .metadataVersion(3)
            .capabilities(3)
            .host("10.0.0.5")
            .port(8080)
            .lastSeenMs(mClock.nowMs())
            .build());

        mStore.onObserved(observed(DiscoveryChannel.NSD, DEVICE_ID, "10.0.0.5", 8080, mClock.nowMs()));

        LssDeviceInfo device = mStore.device(DEVICE_ID);
        assertEquals("Kitchen", device.getName());
        assertEquals(DeviceRole.SERVER, device.getRole());
        assertEquals(ShareState.LIVE, device.getState());
        assertEquals(JoinPolicy.OPEN, device.getJoin());
        assertEquals(AuthMode.NONE, device.getAuth());
        assertEquals("1.0", device.getProtocolVersion());
        assertEquals(3, device.getMetadataVersion());
        assertEquals(3, device.getCapabilities());
        assertTrue(device.isSecure());
    }

    @Test
    public void identityConflictKeepsTheEstablishedFingerprint() {
        mStore.onObserved(fingerprinted("fp-a"));
        mStore.onObserved(fingerprinted("fp-b"));

        LssDeviceInfo device = mStore.device(DEVICE_ID);

        assertEquals("fp-a", device.getFingerprint());
        assertTrue(device.isIdentityChanged());
    }

    @Test
    public void matchingFingerprintDoesNotFlagIdentityChange() {
        mStore.onObserved(fingerprinted("fp-a"));
        mStore.onObserved(fingerprinted("fp-a"));

        assertFalse(mStore.device(DEVICE_ID).isIdentityChanged());
    }

    @Test
    public void lostChannelRemovesOnlyItsOwnAddress() {
        mStore.onObserved(observed(DiscoveryChannel.NSD, DEVICE_ID, "10.0.0.5", 8080, mClock.nowMs()));
        mStore.onObserved(observed(DiscoveryChannel.MULTICAST, DEVICE_ID, "192.168.1.7", 9090, mClock.nowMs()));

        mStore.onObserved(DeviceObservation.builder()
            .source(DiscoveryChannel.NSD)
            .id(DEVICE_ID)
            .host("10.0.0.5")
            .port(8080)
            .lastSeenMs(mClock.nowMs())
            .lost(true)
            .build());

        LssDeviceInfo device = mStore.device(DEVICE_ID);
        assertEquals(1, device.getAddresses().size());
        assertEquals("192.168.1.7", device.primaryAddress().getHost());
        assertEquals(DevicePresence.HEARD, device.getPresence());
    }

    @Test
    public void lostAddressOfAnotherChannelIsIgnored() {
        mStore.onObserved(observed(DiscoveryChannel.NSD, DEVICE_ID, "10.0.0.5", 8080, mClock.nowMs()));

        mStore.onObserved(DeviceObservation.builder()
            .source(DiscoveryChannel.SCAN)
            .id(DEVICE_ID)
            .host("10.0.0.5")
            .port(8080)
            .lastSeenMs(mClock.nowMs())
            .lost(true)
            .build());

        assertEquals(1, mStore.device(DEVICE_ID).getAddresses().size());
    }

    @Test
    public void observationsWithoutIdAreIgnored() {
        mStore.onObserved(DeviceObservation.builder()
            .source(DiscoveryChannel.NSD)
            .host("10.0.0.5")
            .port(8080)
            .lastSeenMs(mClock.nowMs())
            .build());

        assertEquals(0, mStore.size());
    }

    @Test
    public void outOfOrderAnnouncementsWithinOneBootAreIgnored() {
        mStore.onObserved(named("boot-1", 5L, "First"));
        mStore.onObserved(named("boot-1", 4L, "Stale"));

        assertEquals("First", mStore.device(DEVICE_ID).getName());

        mStore.onObserved(named("boot-1", 6L, "Second"));
        assertEquals("Second", mStore.device(DEVICE_ID).getName());
    }

    @Test
    public void newBootResetsTheSequenceBaseline() {
        mStore.onObserved(named("boot-1", 5L, "First"));
        mStore.onObserved(named("boot-2", 1L, "Restarted"));

        assertEquals("Restarted", mStore.device(DEVICE_ID).getName());
    }

    @Test
    public void favoriteFlagSurvivesLaterObservations() {
        mStore.onObserved(observed(DiscoveryChannel.NSD, DEVICE_ID, "10.0.0.5", 8080, mClock.nowMs()));
        mStore.setFavorite(DEVICE_ID, true);

        mStore.onObserved(observed(DiscoveryChannel.NSD, DEVICE_ID, "10.0.0.5", 8080, mClock.nowMs()));

        assertTrue(mStore.device(DEVICE_ID).isFavorite());
    }

    @Test
    public void deviceIsUnknownBeforeAnyObservation() {
        assertNull(mStore.device(DEVICE_ID));
        assertFalse(mStore.setFavorite(DEVICE_ID, true));
    }

    @Test
    public void recentConfirmationIsReportedWithinItsWindowOnly() {
        mStore.onObserved(observed(DiscoveryChannel.MULTICAST, DEVICE_ID, "10.0.0.5", 8080,
            mClock.nowMs()));
        mStore.confirm(DEVICE_ID, DiscoveryChannel.MULTICAST, "10.0.0.5", 8080);

        assertTrue(mStore.isConfirmedWithin("10.0.0.5", 8080, 30_000L));
        assertFalse(mStore.isConfirmedWithin("10.0.0.6", 8080, 30_000L));
        assertFalse(mStore.isConfirmedWithin("10.0.0.5", 9090, 30_000L));

        mClock.advance(30_001L);

        assertFalse(mStore.isConfirmedWithin("10.0.0.5", 8080, 30_000L));
    }

    private DeviceObservation observed(DiscoveryChannel channel, String id, String host, int port, long nowMs) {
        return DeviceObservation.builder()
            .source(channel)
            .id(id)
            .host(host)
            .port(port)
            .lastSeenMs(nowMs)
            .build();
    }

    private DeviceObservation fingerprinted(String fingerprint) {
        return DeviceObservation.builder()
            .source(DiscoveryChannel.NSD)
            .id(DEVICE_ID)
            .fingerprint(fingerprint)
            .host("10.0.0.5")
            .port(8080)
            .lastSeenMs(mClock.nowMs())
            .build();
    }

    private DeviceObservation named(String boot, long seq, String name) {
        return DeviceObservation.builder()
            .source(DiscoveryChannel.MULTICAST)
            .id(DEVICE_ID)
            .name(name)
            .host("10.0.0.5")
            .port(8080)
            .boot(boot)
            .seq(seq)
            .lastSeenMs(mClock.nowMs())
            .build();
    }
}
