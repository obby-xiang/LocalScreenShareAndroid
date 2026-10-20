package com.obby.android.localscreenshare.identity;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;

/**
 * Storage abstraction for the device identity key pair.
 *
 * <p>The real implementation is {@link AndroidKeystoreIdentityProvider}; JVM tests substitute a
 * keystore that holds software EC keys. Only the public key is ever read back, because the
 * identity is derived from its SPKI encoding.</p>
 *
 * <p>Implementations block while the keystore is opened and the key is created; callers cache the
 * resulting identity.</p>
 */
public interface IdentityKeyStore {

    /**
     * Returns the stored key pair, generating one when the entry is missing, unreadable or
     * invalid.
     *
     * @throws GeneralSecurityException if the key cannot be generated
     * @throws IOException if the keystore cannot be opened
     */
    @NonNull
    KeyPair loadOrCreate() throws GeneralSecurityException, IOException;

    /**
     * Deletes the stored entry and returns a freshly generated key pair.
     *
     * @throws GeneralSecurityException if the key cannot be generated
     * @throws IOException if the keystore cannot be opened
     */
    @NonNull
    KeyPair reset() throws GeneralSecurityException, IOException;
}
