package com.obby.android.localscreenshare.discovery.store;

import com.obby.android.localscreenshare.core.support.MutableClock;
import com.obby.android.localscreenshare.discovery.legacy.LegacyServiceInfo;
import com.obby.android.localscreenshare.discovery.model.DevicePresence;
import com.obby.android.localscreenshare.discovery.model.DiscoveryChannel;
import com.obby.android.localscreenshare.discovery.model.DeviceInfo;
import com.obby.android.localscreenshare.discovery.provider.DeviceObservation;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DeviceStoreTest {

    private static final String DEVICE_ID = "L7Q3K9M2F4X8B6N1C5V0Z3T7R2";

    private MutableClock mClock;

    private DeviceStore mStore;

    @Before
    public void setUp() {
        mClock = new MutableClock(10_000L);
        mStore = new DeviceStore(mClock);
    }

    @Test
    public void identityChangedFlagCanBeSetAndCleared() {
        mStore.onObserved(heard(DEVICE_ID, "10.0.0.5", 8080, mClock.nowMs()));

        assertTrue(mStore.setIdentityChanged(DEVICE_ID, true));
        assertTrue(mStore.device(DEVICE_ID).isIdentityChanged());

        assertTrue(mStore.setIdentityChanged(DEVICE_ID, false));
        assertFalse(mStore.device(DEVICE_ID).isIdentityChanged());

        assertFalse(mStore.setIdentityChanged("unknown-device", true));
    }

    @Test
    public void identityChangedFlagFollowsAFingerprintConflict() {
        mStore.onObserved(DeviceObservation.builder()
            .source(DiscoveryChannel.NSD).id(DEVICE_ID).fingerprint("fingerprint-one")
            .host("10.0.0.5").port(8080).build());

        mStore.onObserved(DeviceObservation.builder()
            .source(DiscoveryChannel.MULTICAST).id(DEVICE_ID).fingerprint("fingerprint-two")
            .host("10.0.0.6").port(8080).build());

        assertTrue(mStore.device(DEVICE_ID).isIdentityChanged());
        assertEquals("fingerprint-one", mStore.device(DEVICE_ID).getFingerprint());
    }

    @Test
    public void aConfirmedIdentityRebindsTheFingerprint() {
        mStore.onObserved(DeviceObservation.builder()
            .source(DiscoveryChannel.NSD).id(DEVICE_ID).fingerprint("fingerprint-one")
            .host("10.0.0.5").port(8080).build());
        mStore.onObserved(DeviceObservation.builder()
            .source(DiscoveryChannel.MULTICAST).id(DEVICE_ID).fingerprint("fingerprint-two")
            .host("10.0.0.5").port(8080).build());

        assertTrue(mStore.confirmIdentity(DEVICE_ID, "fingerprint-two"));

        assertEquals("fingerprint-two", mStore.device(DEVICE_ID).getFingerprint());
        assertFalse(mStore.device(DEVICE_ID).isIdentityChanged());
        assertFalse(mStore.confirmIdentity("unknown-device", "fingerprint-two"));
    }

    @Test
    public void heardObservationIsNotConfirmed() {
        mStore.onObserved(heard(DEVICE_ID, "10.0.0.5", 8080, mClock.nowMs()));

        assertEquals(1, mStore.snapshot().size());
        DeviceInfo device = mStore.snapshot().get(0);
        assertEquals(DevicePresence.HEARD, device.getPresence());
        assertFalse(device.isConfirmed());
        assertEquals(0L, device.getLastConfirmedMs());
    }

    @Test
    public void confirmOnlyWorksForKnownDevices() {
        assertFalse(mStore.confirm(DEVICE_ID, DiscoveryChannel.NSD, "10.0.0.5", 8080));

        mStore.onObserved(heard(DEVICE_ID, "10.0.0.5", 8080, mClock.nowMs()));

        assertTrue(mStore.confirm(DEVICE_ID, DiscoveryChannel.NSD, "10.0.0.5", 8080));
        assertEquals(DevicePresence.CONFIRMED, mStore.device(DEVICE_ID).getPresence());
        assertEquals(mClock.nowMs(), mStore.device(DEVICE_ID).getLastConfirmedMs());
        assertTrue(mStore.device(DEVICE_ID).primaryAddress().isConfirmed());
    }

    @Test
    public void confirmationExpiresIntoStaleThenRemoval() {
        mStore.onObserved(heard(DEVICE_ID, "10.0.0.5", 8080, mClock.nowMs()));
        mStore.confirm(DEVICE_ID, DiscoveryChannel.NSD, "10.0.0.5", 8080);

        mClock.advance(DeviceStore.CONFIRMED_TTL_MS - 1_000L);
        mStore.tick();
        assertEquals(DevicePresence.CONFIRMED, mStore.device(DEVICE_ID).getPresence());

        mClock.advance(2_000L);
        mStore.tick();
        assertEquals(DevicePresence.STALE, mStore.device(DEVICE_ID).getPresence());
        assertFalse(mStore.device(DEVICE_ID).primaryAddress().isConfirmed());

        mClock.advance(DeviceStore.REMOVE_AFTER_MS);
        mStore.tick();
        assertNull(mStore.device(DEVICE_ID));
        assertEquals(0, mStore.size());
    }

    @Test
    public void heardEntryTurnsStaleAfterHeardWindowAndExpiresAfterRemoveWindow() {
        mStore.onObserved(heard(DEVICE_ID, "10.0.0.5", 8080, mClock.nowMs()));

        mClock.advance(DeviceStore.HEARD_TTL_MS);
        mStore.tick();
        assertEquals(DevicePresence.HEARD, mStore.device(DEVICE_ID).getPresence());

        mClock.advance(1_000L);
        mStore.tick();
        assertEquals(DevicePresence.STALE, mStore.device(DEVICE_ID).getPresence());

        mClock.advance(DeviceStore.REMOVE_AFTER_MS);
        mStore.tick();
        assertNull(mStore.device(DEVICE_ID));
    }

    @Test
    public void goodbyeRemovesEntryImmediately() {
        mStore.onObserved(heard(DEVICE_ID, "10.0.0.5", 8080, mClock.nowMs()));

        mStore.onObserved(DeviceObservation.builder()
            .source(DiscoveryChannel.MULTICAST)
            .id(DEVICE_ID)
            .lastSeenMs(mClock.nowMs())
            .goodbye(true)
            .build());

        assertNull(mStore.device(DEVICE_ID));
        assertEquals(0, mStore.size());
    }

    @Test
    public void goodbyeKeepsFavoriteAsUnreachable() {
        mStore.onObserved(heard(DEVICE_ID, "10.0.0.5", 8080, mClock.nowMs()));
        assertTrue(mStore.setFavorite(DEVICE_ID, true));

        mStore.onObserved(DeviceObservation.builder()
            .source(DiscoveryChannel.MULTICAST)
            .id(DEVICE_ID)
            .lastSeenMs(mClock.nowMs())
            .goodbye(true)
            .build());

        assertEquals(1, mStore.size());
        assertEquals(DevicePresence.UNREACHABLE, mStore.device(DEVICE_ID).getPresence());
    }

    @Test
    public void favoriteIsNeverRemovedByTtl() {
        mStore.onObserved(heard(DEVICE_ID, "10.0.0.5", 8080, mClock.nowMs()));
        mStore.setFavorite(DEVICE_ID, true);

        mClock.advance(DeviceStore.REMOVE_AFTER_MS * 4L);
        mStore.tick();

        assertEquals(1, mStore.size());
        assertEquals(DevicePresence.UNREACHABLE, mStore.device(DEVICE_ID).getPresence());
    }

    @Test
    public void pinnedEntryIsNeverRemovedByTtl() {
        mStore.onObserved(heard(DEVICE_ID, "10.0.0.5", 8080, mClock.nowMs()));
        assertTrue(mStore.setPinned(DEVICE_ID, true));
        assertFalse(mStore.setPinned("unknown-device", true));

        mClock.advance(DeviceStore.REMOVE_AFTER_MS * 4L);
        mStore.tick();

        assertEquals(1, mStore.size());
        assertEquals(DevicePresence.UNREACHABLE, mStore.device(DEVICE_ID).getPresence());
    }

    @Test
    public void goodbyeKeepsPinnedEntryAsUnreachable() {
        mStore.onObserved(heard(DEVICE_ID, "10.0.0.5", 8080, mClock.nowMs()));
        assertTrue(mStore.setPinned(DEVICE_ID, true));

        mStore.onObserved(DeviceObservation.builder()
            .source(DiscoveryChannel.MULTICAST)
            .id(DEVICE_ID)
            .lastSeenMs(mClock.nowMs())
            .goodbye(true)
            .build());

        assertEquals(1, mStore.size());
        assertEquals(DevicePresence.UNREACHABLE, mStore.device(DEVICE_ID).getPresence());
    }

    @Test
    public void markAddressUnreachable_degradesAndRecoversOnConfirm() {
        mStore.onObserved(heard(DEVICE_ID, "10.0.0.5", 8080, mClock.nowMs()));
        mStore.confirm(DEVICE_ID, DiscoveryChannel.MANUAL, "10.0.0.5", 8080);
        assertEquals(DevicePresence.CONFIRMED, mStore.device(DEVICE_ID).getPresence());

        mStore.markAddressUnreachable(DiscoveryChannel.MANUAL, "10.0.0.5", 8080);

        assertEquals(1, mStore.size());
        assertEquals(DevicePresence.UNREACHABLE, mStore.device(DEVICE_ID).getPresence());

        mStore.confirm(DEVICE_ID, DiscoveryChannel.MANUAL, "10.0.0.5", 8080);

        assertEquals(DevicePresence.CONFIRMED, mStore.device(DEVICE_ID).getPresence());
    }

    @Test
    public void capacityEvictsTheOldestStaleEntry() {
        mClock.set(100_000L);
        for (int i = 0; i < DeviceStore.CAPACITY; i++) {
            mStore.onObserved(heard("dev-" + i, "10.0.0." + (i % 250 + 1), 8080, 100_000L - i * 100L));
        }
        assertEquals(DeviceStore.CAPACITY, mStore.size());

        mStore.onObserved(heard("newcomer", "10.0.1.1", 8080, 100_000L));

        assertEquals(DeviceStore.CAPACITY, mStore.size());
        assertNull(mStore.device("dev-" + (DeviceStore.CAPACITY - 1)));
        assertNotNull(mStore.device("newcomer"));
    }

    @Test
    public void capacityEvictsNonFavoritesFirst() {
        mClock.set(100_000L);
        for (int i = 0; i < DeviceStore.CAPACITY; i++) {
            mStore.onObserved(heard("dev-" + i, "10.0.0." + (i % 250 + 1), 8080, 100_000L - i * 100L));
        }
        // The oldest entry is a favorite, so the next-oldest stale entry has to be evicted instead.
        mStore.setFavorite("dev-" + (DeviceStore.CAPACITY - 1), true);

        mStore.onObserved(heard("newcomer", "10.0.1.1", 8080, 100_000L));

        assertEquals(DeviceStore.CAPACITY, mStore.size());
        assertNotNull(mStore.device("dev-" + (DeviceStore.CAPACITY - 1)));
        assertNull(mStore.device("dev-" + (DeviceStore.CAPACITY - 2)));
    }

    @Test
    public void listenersAreNotifiedOnlyWhenTheSnapshotChanges() {
        List<List<DeviceInfo>> calls = new ArrayList<>();
        DeviceStoreListener listener = calls::add;
        mStore.addListener(listener);

        mStore.onObserved(heard(DEVICE_ID, "10.0.0.5", 8080, mClock.nowMs()));
        assertEquals(1, calls.size());

        // The same observation at the same timestamp produces an identical snapshot.
        mStore.onObserved(heard(DEVICE_ID, "10.0.0.5", 8080, mClock.nowMs()));
        mStore.tick();
        assertEquals(1, calls.size());

        mStore.confirm(DEVICE_ID, DiscoveryChannel.NSD, "10.0.0.5", 8080);
        assertEquals(2, calls.size());
        assertEquals(DevicePresence.CONFIRMED, calls.get(1).get(0).getPresence());

        mStore.removeListener(listener);
        mStore.onObserved(heard("second", "10.0.0.6", 8080, mClock.nowMs()));
        assertEquals(2, calls.size());
    }

    @Test
    public void legacySnapshotUsesShortIdFallbackAndPrimaryAddress() {
        mStore.onObserved(heard(DEVICE_ID, "10.0.0.5", 8080, mClock.nowMs()));

        List<LegacyServiceInfo> services = mStore.legacySnapshot();

        assertEquals(1, services.size());
        assertEquals(DEVICE_ID, services.get(0).getId());
        assertEquals("LSS-L7Q3K9M2", services.get(0).getName());
        assertEquals("10.0.0.5", services.get(0).getHostAddress());
        assertEquals(8080, services.get(0).getPort());
    }

    @Test
    public void legacySnapshotSkipsDevicesWithoutAddressAndSortsByHost() {
        mStore.onObserved(heard("dev-b", "192.168.1.20", 8080, mClock.nowMs()));
        mStore.onObserved(heard("dev-a", "192.168.1.3", 8080, mClock.nowMs()));
        mStore.onObserved(DeviceObservation.builder()
            .source(DiscoveryChannel.NSD)
            .id("dev-no-address")
            .name("No address")
            .lastSeenMs(mClock.nowMs())
            .build());

        List<LegacyServiceInfo> services = mStore.legacySnapshot();

        assertEquals(2, services.size());
        assertEquals("192.168.1.20", services.get(0).getHostAddress());
        assertEquals("192.168.1.3", services.get(1).getHostAddress());
    }

    @Test
    public void clearPublishesEmptySnapshot() {
        List<List<DeviceInfo>> calls = new ArrayList<>();
        mStore.addListener(calls::add);
        mStore.onObserved(heard(DEVICE_ID, "10.0.0.5", 8080, mClock.nowMs()));

        mStore.clear();

        assertEquals(0, mStore.size());
        assertTrue(mStore.snapshot().isEmpty());
        assertEquals(2, calls.size());
        assertTrue(calls.get(1).isEmpty());
    }

    @Test
    public void removeDropsOneEntryAndPublishes() {
        List<List<DeviceInfo>> calls = new ArrayList<>();
        mStore.addListener(calls::add);
        mStore.onObserved(heard(DEVICE_ID, "10.0.0.5", 8080, mClock.nowMs()));
        mStore.onObserved(heard("other-device", "10.0.0.6", 8080, mClock.nowMs()));

        assertTrue(mStore.remove(DEVICE_ID));

        assertFalse(mStore.remove(DEVICE_ID));
        assertEquals(1, mStore.size());
        assertNull(mStore.device(DEVICE_ID));
        assertNotNull(mStore.device("other-device"));
        assertEquals(3, calls.size());
        assertEquals(1, calls.get(2).size());
    }

    @Test
    public void aRestoredManualEntryAndTheAnnouncedDeviceStayInOneEntry() {
        // The seed a restart performs: the id and address a manual probe confirmed.
        mStore.onObserved(DeviceObservation.builder()
            .source(DiscoveryChannel.MANUAL)
            .id(DEVICE_ID)
            .fingerprint("fingerprint-one")
            .host("10.0.0.5")
            .port(8080)
            .lastSeenMs(mClock.nowMs())
            .build());
        mStore.setPinned(DEVICE_ID, true);

        // The same device is then announced on another interface; the store keys by device id.
        mStore.onObserved(heard(DEVICE_ID, "192.168.1.9", 9090, mClock.nowMs()));

        assertEquals(1, mStore.size());
        final DeviceInfo device = mStore.device(DEVICE_ID);
        assertTrue(device.isManual());
        assertEquals(2, device.getAddresses().size());
        assertEquals("fingerprint-one", device.getFingerprint());
    }

    @Test
    public void theManualFlagFollowsThePinnedState() {
        mStore.onObserved(heard(DEVICE_ID, "10.0.0.5", 8080, mClock.nowMs()));
        assertFalse(mStore.device(DEVICE_ID).isManual());

        mStore.setPinned(DEVICE_ID, true);
        assertTrue(mStore.device(DEVICE_ID).isManual());

        mStore.setPinned(DEVICE_ID, false);
        assertFalse(mStore.device(DEVICE_ID).isManual());
    }

    private static DeviceObservation heard(String id, String host, int port, long nowMs) {
        return DeviceObservation.builder()
            .source(DiscoveryChannel.NSD)
            .id(id)
            .host(host)
            .port(port)
            .lastSeenMs(nowMs)
            .build();
    }
}
