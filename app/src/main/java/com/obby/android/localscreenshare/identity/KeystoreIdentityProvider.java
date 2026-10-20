package com.obby.android.localscreenshare.identity;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Derives the identity from a keystore-backed key pair and records the result.
 *
 * <p>The derivation and change detection live here, above the {@link IdentityKeyStore} abstraction,
 * so they run unchanged on the JVM with a software keystore in tests. On the device the keystore is
 * {@link AndroidKeystoreIdentityProvider}.</p>
 *
 * <p>{@code lastKnownFingerprint} is a record only: it is read to decide whether the key changed
 * since the previous run and written after every load, never used as the identity itself. When it
 * is null — a fresh install — the identity is new but not "changed".</p>
 *
 * <p>Instances are stateless apart from their collaborators and may be shared; key loading and
 * generation block, so callers cache the returned identity.</p>
 */
public final class KeystoreIdentityProvider implements IdentityProvider {

    @NonNull
    private final IdentityKeyStore mKeyStore;

    @NonNull
    private final Supplier<String> mLastKnownFingerprint;

    @NonNull
    private final Consumer<DeviceIdentity> mRecorder;

    /**
     * @param keyStore storage of the key pair
     * @param lastKnownFingerprint supplier of the fingerprint recorded by an earlier run, or null
     * @param recorder sink that persists the derived identity for the next change comparison
     */
    public KeystoreIdentityProvider(@NonNull final IdentityKeyStore keyStore,
        @NonNull final Supplier<String> lastKnownFingerprint,
        @NonNull final Consumer<DeviceIdentity> recorder) {
        mKeyStore = Objects.requireNonNull(keyStore, "keyStore");
        mLastKnownFingerprint = Objects.requireNonNull(lastKnownFingerprint, "lastKnownFingerprint");
        mRecorder = Objects.requireNonNull(recorder, "recorder");
    }

    @NonNull
    @Override
    public DeviceIdentity loadOrCreate() throws IdentityUnavailableException {
        return derive("unable to load the device identity", mKeyStore::loadOrCreate);
    }

    @NonNull
    @Override
    public DeviceIdentity reset() throws IdentityUnavailableException {
        return derive("unable to reset the device identity", mKeyStore::reset);
    }

    @NonNull
    private DeviceIdentity derive(@NonNull final String failureMessage,
        @NonNull final KeyStoreOperation operation) throws IdentityUnavailableException {
        final KeyPair keyPair;
        try {
            keyPair = operation.run();
        } catch (GeneralSecurityException | IOException e) {
            throw new IdentityUnavailableException(failureMessage, e);
        }

        final DeviceIdentity identity =
            DeviceIdentity.fromKeyPair(keyPair, mLastKnownFingerprint.get());
        mRecorder.accept(identity);
        return identity;
    }

    /** Key source; the checked exceptions are converted once by {@link #derive}. */
    @FunctionalInterface
    private interface KeyStoreOperation {
        KeyPair run() throws GeneralSecurityException, IOException;
    }
}
