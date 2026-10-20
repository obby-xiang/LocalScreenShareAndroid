package com.obby.android.localscreenshare.identity;

import androidx.annotation.NonNull;

/**
 * Supplies the identity of this installation.
 *
 * <p>{@link #loadOrCreate()} is idempotent for a stable key: the same installation returns the same
 * identity on every call. {@link #reset()} replaces the key and therefore the identity, which
 * invalidates anything other devices stored about this one — favorites bound to the old
 * fingerprint have to be re-confirmed by their users.</p>
 *
 * <p>Implementations must not fall back to a stored or random value: the identity is always
 * recomputed from the key, and a missing key yields a new identity with
 * {@link DeviceIdentity#isChanged()} set.</p>
 */
public interface IdentityProvider {

    /**
     * Returns the identity of this installation, creating the key on first use.
     *
     * @throws IdentityUnavailableException if the keystore cannot provide or create a key
     */
    @NonNull
    DeviceIdentity loadOrCreate() throws IdentityUnavailableException;

    /**
     * Replaces the key and returns the new identity.
     *
     * <p>Every peer that stored the previous fingerprint now sees an identity change; callers are
     * expected to clear discovery state and to tell the user that favorites must be re-confirmed.</p>
     *
     * @throws IdentityUnavailableException if the keystore cannot provide or create a key
     */
    @NonNull
    DeviceIdentity reset() throws IdentityUnavailableException;
}
