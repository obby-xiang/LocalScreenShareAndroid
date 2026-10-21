package com.obby.android.localscreenshare.identity;

import androidx.annotation.NonNull;

import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Decides how the identity key may be used for TLS and what an existing key has to prove.
 *
 * <p><b>Raw-digest authorization.</b> Conscrypt signs the TLS {@code CertificateVerify} message
 * through {@link Signature} with {@code NONEwithECDSA}: the TLS stack has already hashed the
 * handshake, so the key must sign a raw digest and apply no hash of its own. AndroidKeyStore
 * enforces the authorization set of a key at {@code begin()}, so a key generated for SHA-256 only
 * is rejected with {@code INCOMPATIBLE_DIGEST} and no handshake can complete. That is what the
 * 2026-10-21 device test observed on API 29 and API 36 in both roles, so {@code NONE} is part of
 * the authorization set the identity key is generated with — next to SHA-256 for the certificate
 * self-signature and for any other JCA caller.</p>
 *
 * <p><b>Keys from an earlier build.</b> An authorization set cannot be extended after generation,
 * so a key created before {@code NONE} was authorized keeps failing for its whole lifetime.
 * {@link #probeRawDigestSupport(PrivateKey)} asks the key itself whether it can sign a raw digest,
 * and a key that cannot is replaced once — the only migration Android offers. The identity changes
 * with the key, which is why the replacement happens on the first load rather than at an
 * arbitrary later handshake.</p>
 *
 * <p>The decision is pure: no Android types, no logging, no state. The keystore adapter logs the
 * verdict it acts on.</p>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class IdentityKeyPolicy {

    /**
     * SHA-256, the digest of the certificate self-signature ({@code KeyProperties.DIGEST_SHA256}).
     */
    public static final String DIGEST_SHA256 = "SHA-256";

    /**
     * Raw digest, that is no digest at all ({@code KeyProperties.DIGEST_NONE}).
     */
    public static final String DIGEST_NONE = "NONE";

    /**
     * Digests every identity key is generated with.
     */
    public static final List<String> DIGESTS =
        Collections.unmodifiableList(Arrays.asList(DIGEST_SHA256, DIGEST_NONE));

    /**
     * Signature algorithm used for a raw-digest ECDSA signature, as named by JCA.
     */
    public static final String RAW_DIGEST_ALGORITHM = "NONEwithECDSA";

    /**
     * Length of the raw digest signed by the probe, in bytes: one SHA-256 digest, which is the
     * digest size of the P-256 identity curve.
     */
    public static final int RAW_DIGEST_LENGTH = 32;

    /**
     * Answer of {@link #probeRawDigestSupport(PrivateKey)}.
     */
    public enum RawDigestSupport {
        /**
         * The key signed a raw digest.
         */
        SUPPORTED,

        /**
         * The key refused the operation, so it is not authorized for {@code NONE}.
         */
        UNSUPPORTED,

        /**
         * The probe could not run, so nothing is known about the key.
         */
        UNKNOWN
    }

    /**
     * Signs one raw digest with {@code key} and reports what that attempt showed.
     *
     * <p>The probe mirrors the call Conscrypt makes during a handshake, which is why a
     * {@code SUPPORTED} answer is also evidence that the key can take part in TLS. It signs a
     * fixed all-zero digest of {@link #RAW_DIGEST_LENGTH} bytes; the result is discarded and the
     * key material is never read.</p>
     *
     * @param key private key of the identity, or of a candidate entry
     */
    @NonNull
    public static RawDigestSupport probeRawDigestSupport(@NonNull final PrivateKey key) {
        try {
            final Signature signature = Signature.getInstance(RAW_DIGEST_ALGORITHM);
            signature.initSign(key);
            signature.update(new byte[RAW_DIGEST_LENGTH]);
            signature.sign();
            return RawDigestSupport.SUPPORTED;
        } catch (NoSuchAlgorithmException e) {
            // Without the algorithm a negative answer would say nothing about the key.
            return RawDigestSupport.UNKNOWN;
        } catch (GeneralSecurityException | RuntimeException e) {
            return RawDigestSupport.UNSUPPORTED;
        }
    }

    /**
     * Returns whether a key with the given probe answer must be replaced.
     *
     * <p>Only a refusal is conclusive. An {@code UNKNOWN} answer keeps the key: replacing on every
     * load would change the identity on every start, while a key that cannot be probed also cannot
     * be told apart from a good one.</p>
     */
    public static boolean mustReplace(@NonNull final RawDigestSupport support) {
        return support == RawDigestSupport.UNSUPPORTED;
    }
}
