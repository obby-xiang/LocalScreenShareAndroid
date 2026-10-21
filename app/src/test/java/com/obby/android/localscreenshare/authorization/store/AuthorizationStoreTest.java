package com.obby.android.localscreenshare.authorization.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.obby.android.localscreenshare.authorization.AuthorizationPolicy;
import com.obby.android.localscreenshare.authorization.store.support.FakeAuthorizationStorage;

import org.junit.Test;

import java.util.List;

/**
 * Unit tests for {@link AuthorizationStore}.
 */
public class AuthorizationStoreTest {

    private static final String DEVICE_ID = "AAAAAAAAAAAAAAAAAAAAAAAAAA";

    private static final String FINGERPRINT = "ZmFrZS1maW5nZXJwcmludC1mYWtlLWZpbmdlcnByaW50";

    @Test
    public void anEmptyDocumentAuthorizesNobody() {
        final AuthorizationStore store = new AuthorizationStore(new FakeAuthorizationStorage());

        assertNull(store.find(DEVICE_ID));
        assertTrue(store.entries().isEmpty());
    }

    @Test
    public void authorizeWritesTheDocumentAndFindsTheIdentity() {
        final FakeAuthorizationStorage storage = new FakeAuthorizationStorage();
        final AuthorizationStore store = new AuthorizationStore(storage);

        final AuthorizationEntry entry =
            store.authorize(DEVICE_ID, FINGERPRINT, "Laptop", 1_700_000_000_000L);

        assertEquals(DEVICE_ID, entry.getDeviceId());
        assertEquals("Laptop", entry.getDeviceName());
        assertEquals(1, storage.getWriteCount());
        assertNotNull(storage.getDocument());
        assertEquals(entry, store.find(DEVICE_ID));
    }

    @Test
    public void anAuthorizationSurvivesARestart() {
        final FakeAuthorizationStorage storage = new FakeAuthorizationStorage();
        new AuthorizationStore(storage).authorize(DEVICE_ID, FINGERPRINT, "Laptop", 1L);

        final AuthorizationStore reloaded = new AuthorizationStore(storage);

        assertEquals(FINGERPRINT, reloaded.find(DEVICE_ID).getFingerprint());
        assertEquals("Laptop", reloaded.find(DEVICE_ID).getDeviceName());
    }

    @Test
    public void authorizingTheSameIdAgainReplacesTheEntry() {
        final AuthorizationStore store = new AuthorizationStore(new FakeAuthorizationStorage());

        store.authorize(DEVICE_ID, FINGERPRINT, "Old", 1L);
        store.authorize(DEVICE_ID, FINGERPRINT, "New", 2L);

        assertEquals(1, store.entries().size());
        assertEquals("New", store.find(DEVICE_ID).getDeviceName());
        assertEquals(2L, store.find(DEVICE_ID).getGrantedAtMs());
    }

    @Test
    public void revokeRemovesTheEntryAndReportsWhetherItExisted() {
        final AuthorizationStore store = new AuthorizationStore(new FakeAuthorizationStorage());
        store.authorize(DEVICE_ID, FINGERPRINT, "Laptop", 1L);

        assertTrue(store.revoke(DEVICE_ID));
        assertFalse(store.revoke(DEVICE_ID));
        assertNull(store.find(DEVICE_ID));
    }

    @Test
    public void entriesAreReturnedMostRecentlyGrantedFirst() {
        final AuthorizationStore store = new AuthorizationStore(new FakeAuthorizationStorage());
        store.authorize("id-1", FINGERPRINT, "First", 1L);
        store.authorize("id-2", FINGERPRINT, "Second", 2L);

        final List<AuthorizationEntry> entries = store.entries();

        assertEquals(2, entries.size());
        assertEquals("id-2", entries.get(0).getDeviceId());
        assertEquals("id-1", entries.get(1).getDeviceId());
    }

    @Test
    public void aCorruptedDocumentAuthorizesNobody() {
        final FakeAuthorizationStorage storage = new FakeAuthorizationStorage("{not json");
        final AuthorizationStore store = new AuthorizationStore(storage);

        assertTrue(store.entries().isEmpty());
        assertNull(store.find(DEVICE_ID));
    }

    @Test
    public void aMalformedEntryIsDroppedAndTheOtherOnesAreKept() {
        final FakeAuthorizationStorage storage = new FakeAuthorizationStorage(
            "{\"entries\":[{\"deviceId\":\"\",\"fingerprint\":\"x\",\"grantedAtMs\":1},"
                + "{\"deviceId\":\"" + DEVICE_ID + "\",\"fingerprint\":\"" + FINGERPRINT
                + "\",\"grantedAtMs\":2}]}");

        final AuthorizationStore store = new AuthorizationStore(storage);

        assertEquals(1, store.entries().size());
        assertNotNull(store.find(DEVICE_ID));
    }

    @Test
    public void aFailedWriteKeepsTheInMemoryEntry() {
        final FakeAuthorizationStorage storage = new FakeAuthorizationStorage();
        storage.setWriteFailing(true);
        final AuthorizationStore store = new AuthorizationStore(storage);

        store.authorize(DEVICE_ID, FINGERPRINT, "Laptop", 1L);

        assertNotNull(store.find(DEVICE_ID));
        assertNull(storage.getDocument());
    }

    @Test
    public void aGrantOnlyMatchesWithTheSameFingerprint() {
        final AuthorizationStore store = new AuthorizationStore(new FakeAuthorizationStorage());
        final AuthorizationEntry entry = store.authorize(DEVICE_ID, FINGERPRINT, "Laptop", 1L);

        assertTrue(AuthorizationPolicy.isGrantValid(entry.getDeviceId(), entry.getFingerprint(),
            DEVICE_ID, FINGERPRINT));
        assertFalse(AuthorizationPolicy.isGrantValid(entry.getDeviceId(), entry.getFingerprint(),
            DEVICE_ID, "another-fingerprint"));
        assertFalse(AuthorizationPolicy.isGrantValid(entry.getDeviceId(), entry.getFingerprint(),
            null, null));
    }
}
