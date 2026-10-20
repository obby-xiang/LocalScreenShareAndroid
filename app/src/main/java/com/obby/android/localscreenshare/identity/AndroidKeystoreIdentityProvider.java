package com.obby.android.localscreenshare.identity;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.spec.ECGenParameterSpec;

/**
 * Keystore-backed identity key: one EC P-256 key pair in the AndroidKeyStore, created on first use
 * and kept for the lifetime of the installation.
 *
 * <p>The alias is {@value #KEY_ALIAS}. The key is generated with the {@code SIGN}/{@code VERIFY}
 * purposes and SHA-256 digests, does not require user authentication (discovery runs without an
 * unlocked screen), and is never exported: only the public key is read back. AndroidKeyStore
 * attaches a self-signed certificate to the entry, but the identity is derived from the public key
 * itself, so a certificate replacement would not change it.</p>
 *
 * <p>A missing, unreadable or invalidated entry is regenerated and the caller learns about the new
 * identity through {@link DeviceIdentity#isChanged()}; only a keystore that cannot create a key at
 * all raises an exception.</p>
 */
public final class AndroidKeystoreIdentityProvider implements IdentityKeyStore {

    /** Keystore alias of the device identity key. */
    public static final String KEY_ALIAS = "lss-device-identity";

    /** AndroidKeyStore provider name. */
    public static final String KEYSTORE_PROVIDER = "AndroidKeyStore";

    /** NIST P-256 curve name understood by {@link ECGenParameterSpec}. */
    public static final String EC_CURVE = "secp256r1";

    @NonNull
    @Override
    public KeyPair loadOrCreate() throws GeneralSecurityException, IOException {
        final KeyStore keyStore = openKeyStore();
        final KeyPair existing = readKeyPair(keyStore);
        if (existing != null) {
            return existing;
        }

        if (keyStore.containsAlias(KEY_ALIAS)) {
            // The entry is unusable (unreadable key, missing certificate or permanently
            // invalidated): drop it so a fresh key can take its place.
            keyStore.deleteEntry(KEY_ALIAS);
        }
        return generateKeyPair();
    }

    @NonNull
    @Override
    public KeyPair reset() throws GeneralSecurityException, IOException {
        final KeyStore keyStore = openKeyStore();
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS);
        }
        return generateKeyPair();
    }

    @NonNull
    private static KeyStore openKeyStore() throws GeneralSecurityException, IOException {
        final KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
        keyStore.load(null);
        return keyStore;
    }

    /** Returns the stored key pair, or null when the entry is missing or cannot be read. */
    @Nullable
    private static KeyPair readKeyPair(@NonNull final KeyStore keyStore) throws GeneralSecurityException {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            return null;
        }

        try {
            final Certificate certificate = keyStore.getCertificate(KEY_ALIAS);
            final Key key = keyStore.getKey(KEY_ALIAS, null);
            if (certificate == null || !(key instanceof PrivateKey)) {
                return null;
            }
            final PublicKey publicKey = certificate.getPublicKey();
            if (publicKey == null || publicKey.getEncoded() == null) {
                return null;
            }
            return new KeyPair(publicKey, (PrivateKey) key);
        } catch (GeneralSecurityException e) {
            // Unrecoverable or permanently invalidated entries are replaced by loadOrCreate().
            return null;
        }
    }

    @NonNull
    private static KeyPair generateKeyPair() throws GeneralSecurityException {
        final KeyPairGenerator generator =
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER);
        generator.initialize(new KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(new ECGenParameterSpec(EC_CURVE))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .build());
        return generator.generateKeyPair();
    }
}
