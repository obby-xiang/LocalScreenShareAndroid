package com.obby.android.localscreenshare.trust;

import com.obby.android.localscreenshare.trust.support.FakeTlsPinStorage;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TlsPinStoreTest {

    private static final String ID_A = "AAAAAAAAAAAAAAAAAAAAAAAAAA";

    private static final String ID_B = "BBBBBBBBBBBBBBBBBBBBBBBBBB";

    private static final String FINGERPRINT = "fingerprint-one";

    private static final String OTHER_FINGERPRINT = "fingerprint-two";

    private FakeTlsPinStorage mStorage;

    private TlsPinStore mStore;

    @Before
    public void setUp() {
        mStorage = new FakeTlsPinStorage();
        mStore = new TlsPinStore(mStorage);
    }

    @Test
    public void pinIsPersistedAndReloaded() {
        mStore.pin(ID_A, FINGERPRINT);

        final TlsPinStore reloaded = new TlsPinStore(mStorage);

        final TlsPin pin = reloaded.find(ID_A);
        assertNotNull(pin);
        assertEquals(FINGERPRINT, pin.getFingerprint());
        assertTrue(pin.getPinnedAtMs() > 0L);
        assertTrue(mStorage.writeCount() > 0);
    }

    @Test
    public void pinningAgainReplacesTheFingerprint() {
        mStore.pin(ID_A, FINGERPRINT);

        mStore.pin(ID_A, OTHER_FINGERPRINT);

        assertEquals(1, mStore.pins().size());
        assertEquals(OTHER_FINGERPRINT, mStore.find(ID_A).getFingerprint());
    }

    @Test
    public void unpinRemovesAndPersistsTheRemoval() {
        mStore.pin(ID_A, FINGERPRINT);

        assertTrue(mStore.unpin(ID_A));
        assertFalse(mStore.unpin(ID_A));
        assertNull(mStore.find(ID_A));
        assertNull(new TlsPinStore(mStorage).find(ID_A));
    }

    @Test
    public void pinsAreListedMostRecentFirst() {
        mStore.pin(ID_A, FINGERPRINT);
        mStore.pin(ID_B, OTHER_FINGERPRINT);

        final List<TlsPin> pins = mStore.pins();

        assertEquals(2, pins.size());
        assertEquals(ID_B, pins.get(0).getId());
        assertEquals(ID_A, pins.get(1).getId());
    }

    @Test
    public void corruptedDocumentIsTreatedAsEmpty() {
        mStorage.setValue("{not json");

        final TlsPinStore store = new TlsPinStore(mStorage);

        assertTrue(store.pins().isEmpty());
        assertNull(store.find(ID_A));
    }

    @Test
    public void malformedEntriesAreDropped() {
        mStorage.setValue("{\"pins\":[{\"id\":\"\",\"fingerprint\":\"x\"},"
            + "{\"id\":\"" + ID_A + "\",\"fingerprint\":\"" + FINGERPRINT + "\","
            + "\"pinnedAtMs\":7}]}");

        final TlsPinStore store = new TlsPinStore(mStorage);

        assertEquals(1, store.pins().size());
        assertEquals(ID_A, store.find(ID_A).getId());
    }

    @Test
    public void failedWriteKeepsThePinReadableInMemory() {
        mStorage.failWrites();

        mStore.pin(ID_A, FINGERPRINT);

        assertNotNull(mStore.find(ID_A));
        assertNull(mStorage.value());
    }

    @Test
    public void idIsTrimmedOnWriteAndLookup() {
        mStore.pin("  " + ID_A + "  ", "  " + FINGERPRINT + "  ");

        final TlsPin pin = mStore.find(" " + ID_A + " ");

        assertNotNull(pin);
        assertEquals(ID_A, pin.getId());
        assertEquals(FINGERPRINT, pin.getFingerprint());
    }

    @Test
    public void addressHintIsRememberedAndPersisted() {
        mStore.rememberAddress("192.168.1.7", 8443, ID_A);

        assertEquals(ID_A, mStore.lastDeviceId("192.168.1.7", 8443));
        assertEquals(ID_A, new TlsPinStore(mStorage).lastDeviceId("192.168.1.7", 8443));
        assertNull(mStore.lastDeviceId("192.168.1.7", 9443));
    }

    @Test
    public void addressHintNeverBecomesATrustDecision() {
        mStore.pin(ID_A, FINGERPRINT);
        mStore.rememberAddress("192.168.1.7", 8443, ID_B);

        assertNotNull(mStore.find(ID_A));
        assertNull(mStore.find(ID_B));
        assertEquals(ID_B, mStore.lastDeviceId("192.168.1.7", 8443));
    }

    @Test
    public void blankAddressHintIsIgnored() {
        mStore.rememberAddress("   ", 8443, ID_A);
        mStore.rememberAddress("192.168.1.7", 8443, "  ");

        assertNull(mStore.lastDeviceId("192.168.1.7", 8443));
        assertTrue(mStore.pins().isEmpty());
        assertEquals(0, mStorage.writeCount());
    }
}
