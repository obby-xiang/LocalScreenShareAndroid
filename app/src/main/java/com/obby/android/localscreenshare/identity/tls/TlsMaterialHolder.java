package com.obby.android.localscreenshare.identity.tls;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.identity.AndroidKeystoreKeyStore;
import com.obby.android.localscreenshare.identity.IdentityUnavailableException;
import com.obby.android.localscreenshare.identity.KeystoreExecutor;
import com.obby.android.localscreenshare.identity.SpkiId;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-wide cache of this installation's {@link TlsMaterial} and the single source of the
 * identity a peer can verify.
 *
 * <p>Signing a certificate is cheap but not free, and both endpoints ask for the same pair, so the
 * material is kept in memory. The cache is keyed implicitly by the SPKI fingerprint: every call
 * re-reads the key pair (a keystore handle, not the private key bytes) and rebuilds the material
 * when the derived fingerprint differs. That is what makes {@code IdentityManager.reset()} take
 * effect without a second invalidation path — the next handshake signs a certificate for the new
 * key instead of presenting one whose private key no longer exists.</p>
 *
 * <p><b>One identity.</b> This holder is the only place the identity of a running session may come
 * from. The announcement, the {@code ServerInfo}, the {@code Register} self summary and the
 * {@code HELLO} of a viewer all derive their id and fingerprint from {@link #material()}, so the
 * value a peer sees always equals the certificate the handshake presents. The 2026-10-21 device
 * test found the opposite: the announcement named a key that was no longer the one in the keystore,
 * and every connection was refused as an identity mismatch (N3, root cause B).</p>
 *
 * <p><b>Identity changes.</b> When the material is rebuilt for a different key, every registered
 * {@link IdentityChangeListener} is notified once, after the cache was updated, so a running
 * session can refresh its announcement, its published server info and its notification instead of
 * advertising the old identity until the user restarts sharing.</p>
 *
 * <p>Callers must not touch the keystore from a constructor or a field initializer; the first call
 * blocks while the keystore opens. Keystore work runs on {@link KeystoreExecutor} and is therefore
 * serialized with the identity key read and cannot overlap a certificate signature.</p>
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

    /**
     * Receives the TLS material of a new identity key.
     *
     * <p>Called on the thread that rebuilt the material, which is a handshake or keystore thread, so
     * a listener that touches UI state has to post to its own thread. A listener that throws is
     * ignored: an observer must never break the keystore path.</p>
     */
    public interface IdentityChangeListener {
        /**
         * Reports that the identity key changed while the material was being read.
         *
         * @param previousFingerprint fingerprint of the material that was cached before
         * @param material material of the key that is now in the keystore
         */
        void onIdentityChanged(@NonNull String previousFingerprint, @NonNull TlsMaterial material);
    }

    @NonNull
    private final KeyPairSource mSource;

    @NonNull
    private final List<IdentityChangeListener> mListeners = new CopyOnWriteArrayList<>();

    // Last built material; null until the first load. Volatile because the cache is read on the
    // keystore thread and replaced on the calling thread.
    @Nullable
    private volatile TlsMaterial mMaterial;

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
        final TlsMaterial built;
        try {
            built = KeystoreExecutor.get().call(this::buildMaterial);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IdentityUnavailableException("TLS material read interrupted", e);
        } catch (GeneralSecurityException | IOException e) {
            throw new IdentityUnavailableException("TLS material unavailable", e);
        }

        final TlsMaterial installed;
        final String previousFingerprint;
        synchronized (this) {
            final TlsMaterial cached = mMaterial;
            if (cached != null && cached.getFingerprint().equals(built.getFingerprint())) {
                return cached;
            }
            previousFingerprint = cached == null ? null : cached.getFingerprint();
            mMaterial = built;
            installed = built;
        }
        if (previousFingerprint != null) {
            notifyIdentityChanged(previousFingerprint, installed);
        }
        return installed;
    }

    /**
     * Returns the material that was built last, without touching the keystore.
     *
     * <p>Used by main-thread callers that only need to render or announce the identity of a session
     * that already started: the keystore read behind {@link #material()} blocks and must stay off
     * the main thread. Null before the first load.</p>
     */
    @Nullable
    public TlsMaterial getCachedMaterial() {
        return mMaterial;
    }

    /**
     * Registers a listener for identity changes; registering the same instance twice is a no-op.
     */
    public void addIdentityChangeListener(@NonNull final IdentityChangeListener listener) {
        Objects.requireNonNull(listener, "listener");
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    /**
     * Removes a listener registered with {@link #addIdentityChangeListener(IdentityChangeListener)}.
     */
    public void removeIdentityChangeListener(@NonNull final IdentityChangeListener listener) {
        mListeners.remove(listener);
    }

    /**
     * Drops the cached material; the next call reads the key again.
     *
     * <p>Deliberately silent: a caller that drops the cache already knows why, and a key that is
     * replaced while the cache is empty has no observer to notify.</p>
     */
    public synchronized void clear() {
        mMaterial = null;
    }

    // Runs on the keystore thread: reads the key and signs a certificate only when the cached
    // material does not already belong to that key.
    @NonNull
    private TlsMaterial buildMaterial() throws GeneralSecurityException, IOException {
        final KeyPair keyPair = mSource.loadOrCreate();
        final byte[] spki = keyPair.getPublic() == null ? null : keyPair.getPublic().getEncoded();
        if (spki == null || spki.length == 0) {
            throw new GeneralSecurityException("identity key has no encoded public key");
        }
        final String fingerprint = SpkiId.fingerprint(spki);
        final TlsMaterial cached = mMaterial;
        if (cached != null && cached.getFingerprint().equals(fingerprint)) {
            return cached;
        }
        return TlsMaterial.fromKeyPair(keyPair);
    }

    private void notifyIdentityChanged(@NonNull final String previousFingerprint,
        @NonNull final TlsMaterial material) {
        for (final IdentityChangeListener listener : mListeners) {
            try {
                listener.onIdentityChanged(previousFingerprint, material);
            } catch (RuntimeException e) {
                // An observer that fails must not fail the keystore path; it logs its own failure.
            }
        }
    }

    // Holds the process-wide instance; the keystore is opened on the first call to get().
    private static final class InstanceHolder {
        private static final TlsMaterialHolder INSTANCE =
            new TlsMaterialHolder(new AndroidKeystoreKeyStore()::loadOrCreate);
    }
}
