package com.obby.android.localscreenshare.identity;

import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.identity.support.FakeIdentityKeyStore;

import org.junit.Before;
import org.junit.Test;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class IdentityProviderContractTest {

    private FakeIdentityKeyStore mKeyStore;

    private IdentityProvider mProvider;

    // Stands in for the persisted comparison record; null means "never ran before".
    @Nullable
    private String mRecordedFingerprint;

    private final List<DeviceIdentity> mRecorded = new ArrayList<>();

    @Before
    public void setUp() {
        mKeyStore = new FakeIdentityKeyStore();
        mRecordedFingerprint = null;
        mRecorded.clear();
        mProvider = newProvider();
    }

    @Test
    public void firstLoadCreatesAndRecordsTheIdentity() throws Exception {
        final DeviceIdentity identity = mProvider.loadOrCreate();

        assertEquals(26, identity.getId().length());
        assertEquals(43, identity.getFingerprint().length());
        assertFalse(identity.isChanged());
        assertEquals(1, mKeyStore.loadCount());
        assertEquals(mRecordedFingerprint, identity.getFingerprint());
        assertEquals(1, mRecorded.size());
        assertEquals(identity, mRecorded.get(0));
    }

    @Test
    public void repeatedLoadsAreStableWithinAnInstallation() throws Exception {
        final DeviceIdentity first = mProvider.loadOrCreate();
        final DeviceIdentity second = mProvider.loadOrCreate();

        assertEquals(first, second);
        assertEquals(0, mKeyStore.resetCount());
        assertEquals(2, mKeyStore.loadCount());
    }

    @Test
    public void aLaterRunWithTheSameKeyIsNotAChange() throws Exception {
        final DeviceIdentity first = mProvider.loadOrCreate();

        // A new process builds a new provider over the same keystore and the same record.
        mProvider = newProvider();
        final DeviceIdentity second = mProvider.loadOrCreate();

        assertEquals(first.getId(), second.getId());
        assertEquals(first.getFingerprint(), second.getFingerprint());
        assertFalse(second.isChanged());
    }

    @Test
    public void resetProducesANewIdentityAndReportsTheChange() throws Exception {
        final DeviceIdentity before = mProvider.loadOrCreate();

        final DeviceIdentity after = mProvider.reset();

        assertNotEquals(before.getId(), after.getId());
        assertNotEquals(before.getFingerprint(), after.getFingerprint());
        assertTrue(after.isChanged());
        assertEquals(1, mKeyStore.resetCount());
        assertEquals(after.getFingerprint(), mRecordedFingerprint);
    }

    @Test
    public void aKeyMissingAfterRestoreReportsAChange() throws Exception {
        final DeviceIdentity before = mProvider.loadOrCreate();

        // SharedPreferences came back from the backup, the keystore key did not.
        mKeyStore.forgetKey();
        final DeviceIdentity after = newProvider().loadOrCreate();

        assertFalse(before.equals(after));
        assertTrue(after.isChanged());
        assertEquals(after.getFingerprint(), mRecordedFingerprint);
    }

    @Test
    public void resetThenReloadKeepsTheNewIdentity() throws Exception {
        mProvider.loadOrCreate();
        final DeviceIdentity reset = mProvider.reset();

        mProvider = newProvider();
        final DeviceIdentity reloaded = mProvider.loadOrCreate();

        assertEquals(reset.getId(), reloaded.getId());
        assertEquals(reset.getFingerprint(), reloaded.getFingerprint());
        assertFalse(reloaded.isChanged());
    }

    @Test
    public void unusableKeystoreSurfacesAsIdentityUnavailable() {
        mKeyStore.failOnAccess();

        try {
            mProvider.loadOrCreate();
            fail("an unusable keystore must not yield an identity");
        } catch (IdentityUnavailableException expected) {
            assertNotNull(expected.getCause());
            assertTrue(expected.getCause() instanceof GeneralSecurityException);
        }

        try {
            mProvider.reset();
            fail("an unusable keystore must not yield an identity");
        } catch (IdentityUnavailableException expected) {
            assertNotNull(expected.getCause());
        }
    }

    @Test
    public void instanceNameIsStableAndDerivedFromTheId() throws Exception {
        final DeviceIdentity identity = mProvider.loadOrCreate();
        final DeviceIdentity again = mProvider.loadOrCreate();

        assertEquals("LSS-" + identity.getId().substring(0, 8), identity.instanceName());
        assertEquals(identity.instanceName(), again.instanceName());
    }

    @Test
    public void noRecordYetMeansNoChange() throws Exception {
        assertNull(mRecordedFingerprint);

        final DeviceIdentity identity = mProvider.loadOrCreate();

        assertFalse(identity.isChanged());
        assertNotNull(mRecordedFingerprint);
    }

    private IdentityProvider newProvider() {
        return new DefaultIdentityProvider(mKeyStore, () -> mRecordedFingerprint, identity -> {
            mRecorded.add(identity);
            mRecordedFingerprint = identity.getFingerprint();
        });
    }
}
