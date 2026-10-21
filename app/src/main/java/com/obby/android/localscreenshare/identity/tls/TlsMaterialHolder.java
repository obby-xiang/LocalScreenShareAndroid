package com.obby.android.localscreenshare.identity.tls;

import androidx.annotation.NonNull;

import com.obby.android.localscreenshare.identity.AndroidKeystoreKeyStore;
import com.obby.android.localscreenshare.identity.IdentityUnavailableException;
import com.obby.android.localscreenshare.identity.SpkiId;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Objects;

/**
 * Process-wide cache of this installation's {@link TlsMaterial}.
 *
 * <p>Signing a certificate is cheap but not free, and both endpoints ask for the same pair, so the
 * material is kept in memory. The cache is keyed implicitly by the SPKI fingerprint: every call
 * re-reads the key pair (a keystore handle, not the private key bytes) and rebuilds the material
 * when the derived fingerprint differs. That is what makes {@code IdentityManager.reset()} take
 * effect without a second invalidation path — the next handshake signs a certificate for the new
 * key instead of presenting one whose private key no longer exists.</p>
 *
 * <p>Callers must not touch the keystore from a constructor or a field initializer; the first call
 * blocks while the keystore opens.</p>
 */
public final class TlsMaterialHolder {

    /**
     * Supplies the identity key pair; production reads the AndroidKeyStore.
     */
    public interface KeyPairSource {
        /**
         * Returns the identity key pair, generating it when missing.
         *
         * @throws GeneralSecurityException if the key cannot be created
         * @throws IOException if the keystore cannot be opened
         */
        @NonNull
        KeyPair loadOrCreate() throws GeneralSecurityException, IOException;
    }

    @NonNull
    private final KeyPairSource mSource;

    // Guarded by this; null until the first load.
    private TlsMaterial mMaterial;

    /**
     * Creates a holder over an explicit key source.
     */
    public TlsMaterialHolder(@NonNull final KeyPairSource source) {
        mSource = Objects.requireNonNull(source, "source");
    }

    /**
     * Returns the process-wide holder backed by the AndroidKeyStore identity key.
     */
    @NonNull
    public static TlsMaterialHolder get() {
        return InstanceHolder.INSTANCE;
    }

    /**
     * Returns the TLS material of the current identity, rebuilding it after a key change.
     *
     * @throws IdentityUnavailableException if the keystore cannot provide a key
     */
    @NonNull
    public TlsMaterial material() throws IdentityUnavailableException {
        final KeyPair keyPair;
        try {
            keyPair = mSource.loadOrCreate();
        } catch (GeneralSecurityException | IOException e) {
            throw new IdentityUnavailableException("identity key unavailable", e);
        }

        final String fingerprint = SpkiId.fingerprint(keyPair.getPublic().getEncoded());
        final TlsMaterial cached = mMaterial;
        if (cached != null && cached.getFingerprint().equals(fingerprint)) {
            return cached;
        }

        synchronized (this) {
            if (mMaterial != null && mMaterial.getFingerprint().equals(fingerprint)) {
                return mMaterial;
            }
            try {
                mMaterial = TlsMaterial.fromKeyPair(keyPair);
            } catch (GeneralSecurityException e) {
                throw new IdentityUnavailableException("TLS material unavailable", e);
            }
            return mMaterial;
        }
    }

    /**
     * Drops the cached material; the next call signs a certificate again.
     */
    public synchronized void clear() {
        mMaterial = null;
    }

    // Holds the process-wide instance; the keystore is opened on the first call to get().
    private static final class InstanceHolder {
        private static final TlsMaterialHolder INSTANCE =
            new TlsMaterialHolder(new AndroidKeystoreKeyStore()::loadOrCreate);
    }
}
