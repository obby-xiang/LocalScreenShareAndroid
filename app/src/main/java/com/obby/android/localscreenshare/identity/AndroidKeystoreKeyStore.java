package com.obby.android.localscreenshare.identity;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import android.util.Log;

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
 * purposes and the digests of {@link IdentityKeyPolicy#DIGESTS}: SHA-256 for the certificate
 * self-signature and {@code NONE} for the raw-digest signature a TLS handshake asks for. The key
 * does not require user authentication (discovery runs without an unlocked screen) and is never
 * exported: only the public key is read back. AndroidKeyStore attaches a self-signed certificate to
 * the entry, but the identity is derived from the public key itself, so a certificate replacement
 * would not change it.</p>
 *
 * <p><b>Replacement.</b> An entry created before {@code NONE} was authorized cannot sign a
 * handshake, and an authorization set cannot be extended afterwards; such an entry is replaced once
 * and the caller learns about the new identity through {@link DeviceIdentity#isChanged()}. Every
 * other failure keeps the key: {@link IdentityKeyEntryLoader} decides that, so an interrupted or
 * overlapping keystore read cannot silently delete the identity key (N3). A replacement is logged
 * at error level with its reason and both fingerprints, because it is a security event: every peer
 * that stored the old fingerprint has to confirm the device again.</p>
 *
 * <p><b>Threading.</b> All keystore work runs on {@link KeystoreExecutor}, which serializes it with
 * the certificate signature of the TLS material. A verified key is remembered for the process,
 * because the probe costs one keystore signature and the answer cannot change for an existing
 * entry.</p>
 */
public final class AndroidKeystoreKeyStore implements IdentityKeyStore {

    private static final String TAG = "AndroidKeystoreKeyStore";

    /**
     * Keystore alias of the device identity key.
     */
    public static final String KEY_ALIAS = "lss-device-identity";

    /**
     * AndroidKeyStore provider name.
     */
    public static final String KEYSTORE_PROVIDER = "AndroidKeyStore";

    /**
     * NIST P-256 curve name understood by {@link ECGenParameterSpec}.
     */
    public static final String EC_CURVE = "secp256r1";

    // Attempts of the raw-digest probe; the second one covers a keystore operation that overlapped
    // with a handshake signature on the same key, which the keystore refuses without the key being
    // unusable.
    private static final int PROBE_ATTEMPTS = 2;

    // Pause between two probe attempts, in milliseconds.
    private static final long PROBE_RETRY_DELAY_MS = 100L;

    // Fingerprint of the key this process verified. An authorization set never changes for an
    // existing entry, so a verified key is not probed again: the probe costs one keystore signature
    // and would otherwise run on every handshake attempt. Only written on the keystore thread.
    @Nullable
    private static String sVerifiedFingerprint;

    @NonNull
    private static final IdentityKeyEntryLoader LOADER = new IdentityKeyEntryLoader(
        new IdentityKeyEntryLoader.EntryStore() {
            @Nullable
            @Override
            public KeyPair read() throws GeneralSecurityException, IOException {
                return readStoredKeyPair();
            }

            @Override
            public void delete() throws GeneralSecurityException, IOException {
                deleteStoredEntry();
            }

            @NonNull
            @Override
            public KeyPair create() throws GeneralSecurityException, IOException {
                return generateStoredKeyPair();
            }
        }, AndroidKeystoreKeyStore::isUsableForTls);

    @NonNull
    @Override
    public KeyPair loadOrCreate() throws GeneralSecurityException, IOException {
        final IdentityKeyEntryLoader.LoadResult result;
        try {
            result = KeystoreExecutor.get().call(LOADER::loadOrCreate);
        } catch (InterruptedException e) {
            // The key was not touched: the caller was interrupted before or while waiting.
            Thread.currentThread().interrupt();
            throw new GeneralSecurityException("identity key load interrupted", e);
        }

        if (result.isReplaced()) {
            Log.e(TAG, String.format("loadOrCreate: identity key replaced, reason = %s, "
                    + "previousFingerprint = %s, fingerprint = %s", result.getReplacementReason(),
                result.getPreviousFingerprint(), fingerprintOf(result.getKeyPair())));
        }
        return result.getKeyPair();
    }

    @NonNull
    @Override
    public KeyPair reset() throws GeneralSecurityException, IOException {
        try {
            return KeystoreExecutor.get().call(() -> {
                final KeyPair generated = generateStoredKeyPair();
                sVerifiedFingerprint = fingerprintOf(generated);
                Log.i(TAG, "reset: identity key replaced on request, fingerprint = "
                    + fingerprintOf(generated));
                return generated;
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GeneralSecurityException("identity reset interrupted", e);
        }
    }

    /**
     * Returns the stored key pair, or null when the entry is missing or incomplete.
     */
    @Nullable
    private static KeyPair readStoredKeyPair() throws GeneralSecurityException, IOException {
        final KeyStore keyStore = openKeyStore();
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            return null;
        }
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
    }

    private static void deleteStoredEntry() throws GeneralSecurityException, IOException {
        final KeyStore keyStore = openKeyStore();
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS);
        }
        sVerifiedFingerprint = null;
    }

    // Generates the identity key under the identity alias; an incomplete entry that occupies the
    // alias is released first, so creation never has to reason about what was there.
    @NonNull
    private static KeyPair generateStoredKeyPair() throws GeneralSecurityException, IOException {
        final KeyStore keyStore = openKeyStore();
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS);
        }
        final KeyPair generated = generateKeyPair();
        sVerifiedFingerprint = fingerprintOf(generated);
        return generated;
    }

    @NonNull
    private static KeyStore openKeyStore() throws GeneralSecurityException, IOException {
        final KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
        keyStore.load(null);
        return keyStore;
    }

    /**
     * Returns whether the stored key can take part in a TLS handshake.
     *
     * <p>Keys generated before {@code NONE} was part of the authorization set refuse the
     * raw-digest signature Conscrypt asks for, and their authorization set cannot be extended. Such
     * an entry is replaced once, which changes the identity — acceptable because the old key never
     * completed a handshake, so no peer ever pinned it. An inconclusive probe keeps the key, because
     * replacing on every load would change the identity on every start.</p>
     *
     * <p>A key that passed once in this process is answered from the memo without contacting the
     * keystore again. The probe is retried once, because the keystore refuses an operation that
     * overlaps another signature on the same key, and such a refusal says nothing about the
     * authorization set.</p>
     */
    private static boolean isUsableForTls(@NonNull final KeyPair keyPair) {
        final String fingerprint = fingerprintOf(keyPair);
        if (!fingerprint.isEmpty() && fingerprint.equals(sVerifiedFingerprint)) {
            return true;
        }

        IdentityKeyPolicy.RawDigestSupport support = IdentityKeyPolicy.RawDigestSupport.UNKNOWN;
        for (int attempt = 1; attempt <= PROBE_ATTEMPTS; attempt++) {
            support = IdentityKeyPolicy.probeRawDigestSupport(keyPair.getPrivate());
            if (support != IdentityKeyPolicy.RawDigestSupport.UNSUPPORTED) {
                break;
            }
            sleepBeforeProbeRetry(attempt);
        }

        if (IdentityKeyPolicy.mustReplace(support)) {
            Log.w(TAG, "isUsableForTls: the stored key cannot sign a raw digest, replacing it");
            return false;
        }
        if (support == IdentityKeyPolicy.RawDigestSupport.UNKNOWN) {
            Log.w(TAG, "isUsableForTls: raw digest signing cannot be probed, keeping the key");
        }
        sVerifiedFingerprint = fingerprint;
        return true;
    }

    // Waits between two probe attempts; interrupted waits only shorten the pause. The caller checks
    // the interrupt flag at the entry points, so a probe never starts on an interrupted thread.
    private static void sleepBeforeProbeRetry(final int attempt) {
        if (attempt >= PROBE_ATTEMPTS) {
            return;
        }
        try {
            Thread.sleep(PROBE_RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Returns the SPKI fingerprint of the pair's public key, or an empty string when it cannot be
     * encoded.
     */
    @NonNull
    private static String fingerprintOf(@NonNull final KeyPair keyPair) {
        final byte[] spki = keyPair.getPublic() == null ? null : keyPair.getPublic().getEncoded();
        return spki == null ? "" : SpkiId.fingerprint(spki);
    }

    @NonNull
    private static KeyPair generateKeyPair() throws GeneralSecurityException {
        final KeyPairGenerator generator =
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER);
        generator.initialize(new KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(new ECGenParameterSpec(EC_CURVE))
            .setDigests(IdentityKeyPolicy.DIGESTS.toArray(new String[0]))
            .setUserAuthenticationRequired(false)
            .build());
        return generator.generateKeyPair();
    }
}
