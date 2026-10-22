package com.obby.android.localscreenshare.discovery.manual;

import com.obby.android.localscreenshare.discovery.support.FakeManualDeviceStorage;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link ManualDeviceStore}.
 *
 * <p>The store is the part of TB-DISC-011 that must survive a restart, so the tests follow one
 * document through a second store instance: what was added, what a changed device replaces, what a
 * removal deletes, and what a corrupt or unwritable file does. The merge into the device list itself
 * is covered by {@code DeviceStoreTest}, because the store keys entries by device id and this store
 * only has to hand back the id the confirming handshake returned.</p>
 */
public class ManualDeviceStoreTest {

    private static final String ID_A = "AAAAAAAAAAAAAAAAAAAAAAAAAA";

    private static final String ID_B = "BBBBBBBBBBBBBBBBBBBBBBBBBB";

    private static final String FINGERPRINT = "fingerprint-one";

    private FakeManualDeviceStorage mStorage;

    private ManualDeviceStore mStore;

    @Before
    public void setUp() {
        mStorage = new FakeManualDeviceStorage();
        mStore = new ManualDeviceStore(mStorage);
    }

    @Test
    public void addIsPersistedAndReloaded() {
        mStore.add(device(ID_A, "10.0.0.5", 8080, "Kitchen"));

        final ManualDeviceStore reloaded = new ManualDeviceStore(mStorage);

        assertEquals(1, reloaded.devices().size());
        final ManualDevice device = reloaded.find(ID_A);
        assertNotNull(device);
        assertEquals("10.0.0.5", device.getHost());
        assertEquals(8080, device.getPort());
        assertEquals("Kitchen", device.getName());
        assertEquals(FINGERPRINT, device.getFingerprint());
        assertTrue(mStorage.writes() > 0);
    }

    @Test
    public void aRestoredEntryStartsUnreachableAndKeepsItsAddress() {
        mStore.add(device(ID_A, "10.0.0.5", 8080, "Kitchen"));

        final ManualDevice restored = new ManualDeviceStore(mStorage).find(ID_A);

        assertNotNull(restored);
        assertTrue(restored.isAt("10.0.0.5", 8080));
        assertFalse(restored.isAt("10.0.0.5", 8081));
    }

    @Test
    public void addReplacesTheSameIdAndKeepsOneRecord() {
        mStore.add(device(ID_A, "10.0.0.5", 8080, "Kitchen"));

        assertTrue(mStore.add(device(ID_A, "10.0.0.9", 9090, "Living room")));

        assertEquals(1, mStore.devices().size());
        final ManualDevice device = mStore.find(ID_A);
        assertEquals("10.0.0.9", device.getHost());
        assertEquals(9090, device.getPort());
        assertEquals("Living room", device.getName());
        assertEquals(1, new ManualDeviceStore(mStorage).devices().size());
    }

    @Test
    public void addingAnotherIdAtTheSameAddressDropsTheStaleRecord() {
        mStore.add(device(ID_A, "10.0.0.5", 8080, "Old identity"));

        assertTrue(mStore.add(device(ID_B, "10.0.0.5", 8080, "New identity")));

        assertEquals(1, mStore.devices().size());
        assertNull(mStore.find(ID_A));
        assertEquals(ID_B, mStore.findByAddress("10.0.0.5", 8080).getId());
        assertEquals(1, new ManualDeviceStore(mStorage).devices().size());
    }

    @Test
    public void addingTheSameRecordAgainDoesNotWriteTheDocument() {
        mStore.add(device(ID_A, "10.0.0.5", 8080, "Kitchen"));
        final int writes = mStorage.writes();

        assertFalse(mStore.add(device(ID_A, "10.0.0.5", 8080, "Kitchen")));

        assertEquals(writes, mStorage.writes());
        assertEquals(1, mStore.devices().size());
    }

    @Test
    public void removeDropsTheRecordFromMemoryAndDisk() {
        mStore.add(device(ID_A, "10.0.0.5", 8080, null));

        assertTrue(mStore.remove(ID_A));
        assertFalse(mStore.remove(ID_A));

        assertEquals(0, mStore.devices().size());
        assertTrue(new ManualDeviceStore(mStorage).devices().isEmpty());
    }

    @Test
    public void removeOfAnUnknownIdReturnsFalse() {
        assertFalse(mStore.remove(ID_A));
        assertEquals(0, mStorage.writes());
    }

    @Test
    public void findByAddressMatchesHostAndPort() {
        mStore.add(device(ID_A, "10.0.0.5", 8080, null));

        assertSame(mStore.find(ID_A), mStore.findByAddress("10.0.0.5", 8080));
        assertNull(mStore.findByAddress("10.0.0.5", 8081));
        assertNull(mStore.findByAddress("10.0.0.6", 8080));
    }

    @Test
    public void devicesReturnsTheMostRecentFirst() {
        mStore.add(device(ID_A, "10.0.0.5", 8080, null));
        mStore.add(device(ID_B, "10.0.0.6", 9090, null));

        final List<ManualDevice> devices = mStore.devices();

        assertEquals(2, devices.size());
        assertEquals(ID_B, devices.get(0).getId());
        assertEquals(ID_A, devices.get(1).getId());
    }

    @Test
    public void corruptDocumentFallsBackToAnEmptyStore() {
        mStorage.setDocument("{not json");

        final ManualDeviceStore store = new ManualDeviceStore(mStorage);

        assertEquals(0, store.devices().size());
    }

    @Test
    public void recordsWithoutAnIdOrWithABadPortAreSkipped() {
        mStorage.setDocument("["
            + "{\"id\":\"\",\"ip\":\"10.0.0.5\",\"port\":8080},"
            + "{\"id\":\"bad-port\",\"ip\":\"10.0.0.6\",\"port\":70000},"
            + "{\"id\":\"no-address\",\"port\":8080},"
            + "{\"id\":\"good\",\"ip\":\"10.0.0.7\",\"port\":9090}"
            + "]");

        final ManualDeviceStore store = new ManualDeviceStore(mStorage);

        assertEquals(1, store.devices().size());
        assertNotNull(store.find("good"));
    }

    @Test
    public void duplicateIdsInTheDocumentAreLoadedOnce() {
        mStorage.setDocument("["
            + "{\"id\":\"dup\",\"ip\":\"10.0.0.7\",\"port\":9090},"
            + "{\"id\":\"dup\",\"ip\":\"10.0.0.8\",\"port\":9091}"
            + "]");

        final ManualDeviceStore store = new ManualDeviceStore(mStorage);

        assertEquals(1, store.devices().size());
        assertEquals("10.0.0.7", store.find("dup").getHost());
    }

    @Test
    public void aFailedWriteKeepsTheInMemoryList() {
        mStorage.setFailWrites(true);

        mStore.add(device(ID_A, "10.0.0.5", 8080, null));

        assertEquals(1, mStore.devices().size());
        assertNull(mStorage.document());

        mStorage.setFailWrites(false);
        mStore.add(device(ID_B, "10.0.0.6", 9090, null));

        // The retry writes the complete list, not just the record added after the failure.
        assertEquals(2, new ManualDeviceStore(mStorage).devices().size());
    }

    private static ManualDevice device(final String id, final String host, final int port,
        final String name) {
        return ManualDevice.create(id, FINGERPRINT, name, host, port);
    }
}
