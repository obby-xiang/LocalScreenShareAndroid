package com.obby.android.localscreenshare.identity;

import android.util.Log;

import androidx.annotation.NonNull;

import com.obby.android.localscreenshare.settings.Preferences;

/**
 * Process-wide holder of this installation's {@link DeviceIdentity}.
 *
 * <p>The identity is derived from the AndroidKeyStore key on the first call and cached in memory
 * afterwards, because key loading blocks. {@link #reset()} replaces the key, forgets the cache and
 * returns the new identity; the caller is responsible for the consequences — discovery state is
 * dropped, peers see an identity change, and favorites bound to the old fingerprint have to be
 * re-confirmed by their users.</p>
 *
 * <p>The persisted id and fingerprint are written after every load, but only as the comparison
 * record for the next start; the identity itself is always recomputed from the key. A restore from
 * backup brings the record back without the key, which is exactly how a changed identity is
 * detected.</p>
 *
 * <p>Thread-safe: the provider and the cached value are guarded by the instance monitor. Callers on
 * the main thread pay the keystore load once per process.</p>
 */
public final class IdentityManager {
    private static final String TAG = "IdentityManager";

    @NonNull
    private final IdentityProvider mProvider;

    // Guarded by this; null until the first load.
    private DeviceIdentity mIdentity;

    private IdentityManager() {
        mProvider = new KeystoreIdentityProvider(new AndroidKeystoreIdentityProvider(),
            () -> Preferences.get().getIdentityFingerprint(), this::recordIdentity);
    }

    /** Returns the process-wide instance. */
    @NonNull
    public static IdentityManager get() {
        return InstanceHolder.INSTANCE;
    }

    /**
     * Returns the identity of this installation, loading the key on first use.
     *
     * @throws IdentityUnavailableException if the keystore cannot provide a key
     */
    @NonNull
    public synchronized DeviceIdentity getIdentity() throws IdentityUnavailableException {
        if (mIdentity == null) {
            mIdentity = mProvider.loadOrCreate();
            if (mIdentity.isChanged()) {
                Log.w(TAG, "getIdentity: device identity changed since the last run");
            } else {
                Log.i(TAG, "getIdentity: device identity loaded");
            }
        }
        return mIdentity;
    }

    /**
     * Replaces the identity key and returns the new identity.
     *
     * <p>Everything the app cached about the previous identity becomes invalid: the caller must
     * clear the discovery store, restart advertisement and NSD registration, and treat stored
     * favorites as unconfirmed. The returned identity always reports
     * {@link DeviceIdentity#isChanged()} because its fingerprint differs from the recorded one.</p>
     *
     * @throws IdentityUnavailableException if the keystore cannot create a key
     */
    @NonNull
    public synchronized DeviceIdentity reset() throws IdentityUnavailableException {
        mIdentity = mProvider.reset();
        Log.i(TAG, "reset: device identity replaced");
        return mIdentity;
    }

    // Stores the comparison record and captures the pre-identity id once, so favorites written by
    // an older version can still be recognized.
    private void recordIdentity(@NonNull final DeviceIdentity identity) {
        final Preferences preferences = Preferences.get();
        final boolean isRecordStale = !identity.getId().equals(preferences.getIdentityId())
            || !identity.getFingerprint().equals(preferences.getIdentityFingerprint());
        if (isRecordStale) {
            preferences.recordIdentity(identity.getId(), identity.getFingerprint());
        }

        if (preferences.getLegacyIdentityServiceId() == null) {
            preferences.setLegacyIdentityServiceId(preferences.getServiceId());
        }
    }

    // Holds the process-wide instance; initialized on the first call to get().
    private static class InstanceHolder {
        private static final IdentityManager INSTANCE = new IdentityManager();
    }
}
