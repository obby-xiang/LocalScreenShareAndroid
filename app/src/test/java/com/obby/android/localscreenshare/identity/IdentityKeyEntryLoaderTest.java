package com.obby.android.localscreenshare.identity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStoreException;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Replacement decision of the identity key entry.
 *
 * <p>The central case is {@link #aTransientReadFailureNeverDeletesTheKey()}: the 2026-10-21 device
 * test lost the identity of a running session because an interrupted keystore read was answered with
 * a deletion, and the announcement then named a key no certificate could prove. The remaining cases
 * pin the other half — an entry that really is unusable is replaced once, reported, and never
 * replaced twice within one process — and the second confirmation that keeps one failed read from
 * being enough evidence.</p>
 */
public class IdentityKeyEntryLoaderTest {

    @After
    public void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    public void aTransientReadFailureNeverDeletesTheKey() throws Exception {
        final KeyPair stored = newKeyPair();
        final FakeStore store = new FakeStore(stored);
        store.failRead(new KeyStoreException(new InterruptedException("scan cancelled")));

        try {
            loader(store, keyPair -> true).loadOrCreate();
            fail("a transient failure must be reported, not answered with a new key");
        } catch (IdentityKeyEntryLoader.KeyEntryUnavailableException e) {
            assertEquals(KeyEntryFailurePolicy.Verdict.TRANSIENT, e.getVerdict());
        }

        assertEquals(0, store.deleteCount());
        assertEquals(0, store.createCount());
        assertSame(stored, store.stored());
    }

    @Test
    public void aTransientFailureDuringTheSecondConfirmationAlsoKeepsTheKey() throws Exception {
        final KeyPair stored = newKeyPair();
        final FakeStore store = new FakeStore(stored);
        store.failRead(new KeyStoreException(new InterruptedException("scan cancelled")));
        store.failRead(new KeyStoreException(new InterruptedException("scan cancelled")));

        try {
            loader(store, keyPair -> false).loadOrCreate();
            fail("a transient failure must be reported, not answered with a new key");
        } catch (IdentityKeyEntryLoader.KeyEntryUnavailableException e) {
            assertEquals(KeyEntryFailurePolicy.Verdict.TRANSIENT, e.getVerdict());
        }

        assertEquals(0, store.deleteCount());
        assertEquals(0, store.createCount());
    }

    @Test
    public void anInterruptedThreadNeverReachesTheKeystore() throws Exception {
        final FakeStore store = new FakeStore(newKeyPair());
        Thread.currentThread().interrupt();

        try {
            loader(store, keyPair -> true).loadOrCreate();
            fail("an interrupted caller must not load the key at all");
        } catch (IdentityKeyEntryLoader.KeyEntryUnavailableException e) {
            assertEquals(KeyEntryFailurePolicy.Verdict.TRANSIENT, e.getVerdict());
        }

        assertEquals(0, store.readCount());
        assertEquals(0, store.deleteCount());
    }

    @Test
    public void aUsableEntryIsReused() throws Exception {
        final KeyPair stored = newKeyPair();
        final FakeStore store = new FakeStore(stored);

        final IdentityKeyEntryLoader.LoadResult result =
            loader(store, keyPair -> true).loadOrCreate();

        assertEquals(IdentityKeyEntryLoader.Outcome.REUSED, result.getOutcome());
        assertSame(stored, result.getKeyPair());
        assertEquals(0, store.deleteCount());
    }

    @Test
    public void anEntryWithoutKeyMaterialIsCreated() throws Exception {
        final FakeStore store = new FakeStore(null);

        final IdentityKeyEntryLoader.LoadResult result =
            loader(store, keyPair -> true).loadOrCreate();

        assertEquals(IdentityKeyEntryLoader.Outcome.CREATED, result.getOutcome());
        assertEquals(1, store.createCount());
        assertEquals(0, store.deleteCount());
    }

    @Test
    public void oneUnusableAnswerIsNotEnoughToDeleteTheKey() throws Exception {
        final KeyPair stored = newKeyPair();
        final FakeStore store = new FakeStore(stored);

        // The first probe refuses, the confirmation accepts: the entry is fine and must survive.
        final int[] probeCount = {0};

        final IdentityKeyEntryLoader.LoadResult result =
            loader(store, keyPair -> ++probeCount[0] >= 2).loadOrCreate();

        assertEquals(IdentityKeyEntryLoader.Outcome.REUSED, result.getOutcome());
        assertEquals(2, store.readCount());
        assertEquals(0, store.deleteCount());
    }

    @Test
    public void aPermanentlyInvalidatedEntryIsReplacedOnceAndReported() throws Exception {
        final FakeStore store = new FakeStore(newKeyPair());
        store.failRead(new KeyPermanentlyInvalidatedException());
        store.failRead(new KeyPermanentlyInvalidatedException());

        final IdentityKeyEntryLoader.LoadResult result =
            loader(store, keyPair -> true).loadOrCreate();

        assertEquals(IdentityKeyEntryLoader.Outcome.REPLACED, result.getOutcome());
        assertTrue(result.isReplaced());
        // The old key cannot be read at all, so there is no previous fingerprint to report; the
        // reason carries what the keystore said.
        assertNull(result.getPreviousFingerprint());
        assertEquals("key permanently invalidated", result.getReplacementReason());
        assertEquals(1, store.deleteCount());
        assertEquals(1, store.createCount());
    }

    @Test
    public void anEntryThatRefusesTheProbeIsReplacedWithBothFingerprints() throws Exception {
        final KeyPair stored = newKeyPair();
        final FakeStore store = new FakeStore(stored);

        final IdentityKeyEntryLoader.LoadResult result =
            loader(store, keyPair -> false).loadOrCreate();

        assertEquals(IdentityKeyEntryLoader.Outcome.REPLACED, result.getOutcome());
        assertEquals(fingerprintOf(stored), result.getPreviousFingerprint());
        assertEquals("key refused the usability probe", result.getReplacementReason());
        assertNotNull(result.getKeyPair());
        assertEquals(1, store.deleteCount());
    }

    @Test
    public void aSecondReplacementInTheSameProcessIsRefused() throws Exception {
        final FakeStore store = new FakeStore(newKeyPair());
        store.failRead(new KeyPermanentlyInvalidatedException());
        store.failRead(new KeyPermanentlyInvalidatedException());
        final IdentityKeyEntryLoader loader = loader(store, keyPair -> true);
        assertEquals(IdentityKeyEntryLoader.Outcome.REPLACED, loader.loadOrCreate().getOutcome());

        // The replacement is unusable as well: the process must not keep chasing the keystore.
        store.failRead(new KeyPermanentlyInvalidatedException());
        store.failRead(new KeyPermanentlyInvalidatedException());
        try {
            loader.loadOrCreate();
            fail("a second replacement in one process must be refused");
        } catch (IdentityKeyEntryLoader.KeyEntryUnavailableException e) {
            assertEquals(KeyEntryFailurePolicy.Verdict.UNUSABLE, e.getVerdict());
        }

        assertEquals(1, store.deleteCount());
        assertEquals(1, store.createCount());
    }

    @Test
    public void aFailureToCreateIsReportedWithItsVerdict() throws Exception {
        final FakeStore store = new FakeStore(null);
        store.failCreate(new IOException("keystore is not ready"));

        try {
            loader(store, keyPair -> true).loadOrCreate();
            fail("a keystore that cannot create a key must be reported");
        } catch (IdentityKeyEntryLoader.KeyEntryUnavailableException e) {
            assertEquals(KeyEntryFailurePolicy.Verdict.TRANSIENT, e.getVerdict());
        }

        assertEquals(0, store.deleteCount());
    }

    @NonNull
    private static IdentityKeyEntryLoader loader(@NonNull final FakeStore store,
        @NonNull final IdentityKeyEntryLoader.UsabilityProbe probe) {
        return new IdentityKeyEntryLoader(store, probe);
    }

    @NonNull
    private static String fingerprintOf(@NonNull final KeyPair keyPair) {
        return SpkiId.fingerprint(keyPair.getPublic().getEncoded());
    }

    @NonNull
    private static KeyPair newKeyPair() {
        try {
            final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("EC is unavailable", e);
        }
    }

    /**
     * Stands in for the Android exception the policy matches by simple name.
     */
    private static final class KeyPermanentlyInvalidatedException extends GeneralSecurityException {

        private static final long serialVersionUID = 1L;

        KeyPermanentlyInvalidatedException() {
            super("key permanently invalidated");
        }
    }

    /**
     * Entry store with programmable read failures, so the loader's decision can be observed without
     * an AndroidKeyStore; deletions and creations are counted because "zero deletions on a transient
     * failure" is the property under test.
     */
    private static final class FakeStore implements IdentityKeyEntryLoader.EntryStore {

        /**
         * One read answer.
         */
        @FunctionalInterface
        private interface ReadAction {
            @Nullable
            KeyPair read() throws GeneralSecurityException, IOException;
        }

        @Nullable
        private KeyPair mStored;

        @NonNull
        private final List<ReadAction> mReads = new ArrayList<>();

        private int mReadCount;

        private int mDeleteCount;

        private int mCreateCount;

        @Nullable
        private IOException mCreateFailure;

        FakeStore(@Nullable final KeyPair stored) {
            mStored = stored;
        }

        void failRead(@NonNull final GeneralSecurityException failure) {
            mReads.add(() -> {
                throw failure;
            });
        }

        void failCreate(@NonNull final IOException failure) {
            mCreateFailure = failure;
        }

        @Nullable
        KeyPair stored() {
            return mStored;
        }

        int readCount() {
            return mReadCount;
        }

        int deleteCount() {
            return mDeleteCount;
        }

        int createCount() {
            return mCreateCount;
        }

        @Nullable
        @Override
        public KeyPair read() throws GeneralSecurityException, IOException {
            mReadCount++;
            if (mReadCount <= mReads.size()) {
                return mReads.get(mReadCount - 1).read();
            }
            return mStored;
        }

        @Override
        public void delete() {
            mDeleteCount++;
            mStored = null;
        }

        @NonNull
        @Override
        public KeyPair create() throws IOException {
            if (mCreateFailure != null) {
                throw mCreateFailure;
            }
            mCreateCount++;
            mStored = newKeyPair();
            return mStored;
        }
    }
}
