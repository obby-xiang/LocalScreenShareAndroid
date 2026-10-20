package com.obby.android.localscreenshare.discovery.favorite;

import com.obby.android.localscreenshare.discovery.support.FakeFavoriteStorage;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class FavoriteStoreTest {

    private static final String ID_A = "AAAAAAAAAAAAAAAAAAAAAAAAAA";

    private static final String ID_B = "BBBBBBBBBBBBBBBBBBBBBBBBBB";

    private static final String ID_C = "CCCCCCCCCCCCCCCCCCCCCCCCCC";

    private static final String FINGERPRINT = "fingerprint-one";

    private FakeFavoriteStorage mStorage;

    private FavoriteStore mStore;

    @Before
    public void setUp() {
        mStorage = new FakeFavoriteStorage();
        mStore = new FavoriteStore(mStorage);
    }

    @Test
    public void addIsPersistedAndReloaded() {
        mStore.add(favorite(ID_A, "10.0.0.5", 8080, "Kitchen", false));

        final FavoriteStore reloaded = new FavoriteStore(mStorage);

        assertEquals(1, reloaded.favorites().size());
        final FavoriteDevice favorite = reloaded.find(ID_A);
        assertNotNull(favorite);
        assertEquals("10.0.0.5", favorite.getHost());
        assertEquals(8080, favorite.getPort());
        assertEquals("Kitchen", favorite.getAlias());
        assertEquals(FINGERPRINT, favorite.getFingerprint());
        assertTrue(mStorage.writes() > 0);
    }

    @Test
    public void addReplacesTheSameIdAndKeepsTheSuccessTime() {
        mStore.add(favorite(ID_A, "10.0.0.5", 8080, "Old", false));
        mStore.recordSuccess(ID_A, "10.0.0.5", 8080, 500L);

        mStore.add(favorite(ID_A, "10.0.0.9", 9090, "New", false));

        assertEquals(1, mStore.favorites().size());
        final FavoriteDevice favorite = mStore.find(ID_A);
        assertNotNull(favorite);
        assertEquals("10.0.0.9", favorite.getHost());
        assertEquals(9090, favorite.getPort());
        assertEquals("New", favorite.getAlias());
        assertEquals(500L, favorite.getLastSuccessMs());
    }

    @Test
    public void removeDropsTheFavorite() {
        mStore.add(favorite(ID_A, "10.0.0.5", 8080, null, false));

        assertTrue(mStore.remove(ID_A));
        assertFalse(mStore.remove(ID_A));
        assertFalse(mStore.isFavorite(ID_A));
        assertTrue(new FavoriteStore(mStorage).favorites().isEmpty());
    }

    @Test
    public void favoritesCanBeFoundByIdentityAndAddress() {
        mStore.add(favorite(ID_A, "10.0.0.5", 8080, null, false));

        assertTrue(mStore.isFavorite(ID_A));
        assertSame(mStore.find(ID_A), mStore.findByAddress("10.0.0.5", 8080));
        assertNull(mStore.findByAddress("10.0.0.5", 8081));
    }

    @Test
    public void anAutomaticAliasFollowsARemoteRename() {
        mStore.add(favorite(ID_A, "10.0.0.5", 8080, "Kitchen", false));

        final FavoriteStore.SyncOutcome outcome = mStore.sync(ID_A, FINGERPRINT, "Living room",
            "10.0.0.5", 8080);

        assertEquals(FavoriteStore.SyncOutcome.UPDATED, outcome);
        assertEquals("Living room", mStore.find(ID_A).getAlias());
    }

    @Test
    public void aCustomAliasIsNeverOverwritten() {
        mStore.add(favorite(ID_A, "10.0.0.5", 8080, "Kitchen", true));

        final FavoriteStore.SyncOutcome outcome = mStore.sync(ID_A, FINGERPRINT, "Living room",
            "10.0.0.5", 8080);

        assertEquals(FavoriteStore.SyncOutcome.UNCHANGED, outcome);
        assertEquals("Kitchen", mStore.find(ID_A).getAlias());
    }

    @Test
    public void aChangedFingerprintIsFlaggedAndNeverOverwritten() {
        mStore.add(favorite(ID_A, "10.0.0.5", 8080, "Kitchen", false));

        final FavoriteStore.SyncOutcome outcome = mStore.sync(ID_A, "fingerprint-two", "Kitchen",
            "10.0.0.5", 8080);

        assertEquals(FavoriteStore.SyncOutcome.IDENTITY_CHANGED, outcome);
        final FavoriteDevice favorite = mStore.find(ID_A);
        assertEquals(FINGERPRINT, favorite.getFingerprint());
        assertTrue(favorite.isIdentityChanged());
        assertTrue(new FavoriteStore(mStorage).find(ID_A).isIdentityChanged());
    }

    @Test
    public void aMatchingFingerprintClearsTheWarning() {
        mStore.add(favorite(ID_A, "10.0.0.5", 8080, "Kitchen", false));
        mStore.sync(ID_A, "fingerprint-two", "Kitchen", "10.0.0.5", 8080);
        assertTrue(mStore.find(ID_A).isIdentityChanged());

        mStore.sync(ID_A, FINGERPRINT, "Kitchen", "10.0.0.5", 8080);

        assertFalse(mStore.find(ID_A).isIdentityChanged());
    }

    @Test
    public void anUnknownFingerprintProvesNothing() {
        mStore.add(FavoriteDevice.create(ID_A, null, "10.0.0.5", 8080, null, false));

        final FavoriteStore.SyncOutcome outcome = mStore.sync(ID_A, "fingerprint-two", null,
            "10.0.0.5", 8080);

        assertEquals(FavoriteStore.SyncOutcome.UNCHANGED, outcome);
        assertFalse(mStore.find(ID_A).isIdentityChanged());
        assertNull(mStore.find(ID_A).getFingerprint());
    }

    @Test
    public void confirmingAnIdentityRebindsTheFingerprint() {
        mStore.add(favorite(ID_A, "10.0.0.5", 8080, "Kitchen", false));
        mStore.sync(ID_A, "fingerprint-two", "Kitchen", "10.0.0.5", 8080);

        assertTrue(mStore.confirmIdentity(ID_A, "fingerprint-two"));

        final FavoriteDevice favorite = mStore.find(ID_A);
        assertEquals("fingerprint-two", favorite.getFingerprint());
        assertFalse(favorite.isIdentityChanged());
    }

    @Test
    public void probeTargetsPreferTheLastSuccessfulAddress() {
        mStore.add(favorite(ID_A, "10.0.0.5", 8080, "A", false));
        mStore.add(favorite(ID_B, "10.0.0.6", 8080, "B", false));
        mStore.add(favorite(ID_C, "10.0.0.7", 8080, "C", false));
        mStore.recordSuccess(ID_B, "10.0.0.6", 8080, 100L);
        mStore.recordSuccess(ID_C, "10.0.0.7", 8080, 200L);

        final List<FavoriteDevice> targets = mStore.probeTargets();

        assertEquals(3, targets.size());
        assertEquals(ID_C, targets.get(0).getId());
        assertEquals(ID_B, targets.get(1).getId());
        assertEquals(ID_A, targets.get(2).getId());
    }

    @Test
    public void aFavoriteWithoutAnAddressIsNotProbed() {
        mStore.add(FavoriteDevice.create(ID_A, null, "10.0.0.5", 0, null, false));

        assertTrue(mStore.probeTargets().isEmpty());
    }

    @Test
    public void recordSuccessUpdatesTheAddress() {
        mStore.add(favorite(ID_A, "10.0.0.5", 8080, "Kitchen", false));

        assertTrue(mStore.recordSuccess(ID_A, "10.0.0.9", 9090, 42L));
        assertFalse(mStore.recordSuccess(ID_B, "10.0.0.9", 9090, 42L));

        final FavoriteDevice favorite = mStore.find(ID_A);
        assertEquals("10.0.0.9", favorite.getHost());
        assertEquals(9090, favorite.getPort());
        assertEquals(42L, favorite.getLastSuccessMs());
    }

    @Test
    public void setAliasMarksTheAliasAsCustom() {
        mStore.add(favorite(ID_A, "10.0.0.5", 8080, "Kitchen", false));

        assertTrue(mStore.setAlias(ID_A, "My TV", true));
        assertFalse(mStore.setAlias(ID_B, "Unknown", true));

        assertEquals("My TV", mStore.find(ID_A).getAlias());
        assertTrue(mStore.find(ID_A).isCustomAlias());
        final FavoriteDevice reloaded = new FavoriteStore(mStorage).find(ID_A);
        assertTrue(reloaded.isCustomAlias());
    }

    @Test
    public void corruptJsonFallsBackToAnEmptyList() {
        mStorage.setDocument("{not json");

        final FavoriteStore store = new FavoriteStore(mStorage);

        assertTrue(store.favorites().isEmpty());
    }

    @Test
    public void legacyFavoritesAreImportedOnce() {
        mStorage.setLegacyDocument("["
            + "{\"serviceId\":\"legacy-a\",\"host\":\"10.0.0.5\",\"port\":8080,"
            + "\"name\":\"Old TV\"},"
            + "{\"id\":\"legacy-b\",\"ip\":\"10.0.0.6\",\"port\":9090}"
            + "]");

        assertEquals(2, mStore.migrateLegacy());

        final FavoriteDevice imported = mStore.find("legacy-a");
        assertNotNull(imported);
        assertNull(imported.getFingerprint());
        assertEquals("Old TV", imported.getAlias());
        assertEquals("10.0.0.6", mStore.find("legacy-b").getHost());
        assertEquals(0, mStore.migrateLegacy());
        assertNull(mStorage.readLegacy());
        assertEquals(2, new FavoriteStore(mStorage).favorites().size());
    }

    @Test
    public void legacyMigrationSkipsExistingAndMalformedEntries() {
        mStore.add(favorite("legacy-a", "10.0.0.5", 8080, null, false));
        mStorage.setLegacyDocument("["
            + "{\"serviceId\":\"legacy-a\",\"host\":\"10.0.0.5\",\"port\":8080},"
            + "{\"serviceId\":\"no-address\"},"
            + "{\"serviceId\":\"bad-port\",\"host\":\"10.0.0.7\",\"port\":70000}"
            + "]");

        assertEquals(0, mStore.migrateLegacy());

        assertEquals(1, mStore.favorites().size());
    }

    @Test
    public void corruptLegacyDocumentIsDiscarded() {
        mStorage.setLegacyDocument("[{");

        assertEquals(0, mStore.migrateLegacy());

        assertNull(mStorage.readLegacy());
    }

    @Test
    public void syncOfAnUnknownDeviceIsReportedAndIgnored() {
        assertEquals(FavoriteStore.SyncOutcome.NOT_FAVORITE,
            mStore.sync(ID_A, FINGERPRINT, "Name", "10.0.0.5", 8080));
        assertTrue(mStore.favorites().isEmpty());
    }

    private static FavoriteDevice favorite(final String id, final String host, final int port,
        final String alias, final boolean customAlias) {
        return FavoriteDevice.create(id, FINGERPRINT, host, port, alias, customAlias);
    }
}
