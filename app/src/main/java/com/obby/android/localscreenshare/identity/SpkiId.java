package com.obby.android.localscreenshare.identity;

import androidx.annotation.NonNull;

import com.obby.android.localscreenshare.support.Base32Crockford;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Derives the device identifiers from a public key.
 *
 * <p>The input is the X.509 {@code SubjectPublicKeyInfo} DER encoding of the signing key — the
 * bytes {@link java.security.PublicKey#getEncoded()} returns. AndroidKeyStore issues a self-signed
 * certificate to hold that key, and a certificate can be renewed or replaced, so identity is
 * computed from the key itself and never from the certificate.</p>
 *
 * <p>Two identifiers are derived from one SHA-256 digest:</p>
 *
 * <ul>
 *   <li>{@code fingerprint}: the full 32-byte digest as unpadded base64url (43 characters);</li>
 *   <li>{@code id}: the first 16 digest bytes as Crockford base32 (26 characters).</li>
 * </ul>
 *
 * <p>Both are stable for a key pair and change completely for any other key. The class is pure
 * JVM: no Android type, no I/O, no cache.</p>
 */
public final class SpkiId {

    /** Hash algorithm used to derive the identifiers. */
    public static final String HASH_ALGORITHM = "SHA-256";

    /** Length of the fingerprint text in characters: 32 bytes as unpadded base64url. */
    public static final int FINGERPRINT_LENGTH = 43;

    /** Number of digest bytes encoded into the short id. */
    public static final int ID_HASH_BYTES = 16;

    /** Length of the short id in characters: 16 bytes as Crockford base32. */
    public static final int ID_LENGTH = Base32Crockford.encodedLength(ID_HASH_BYTES);

    /** base64url alphabet without padding; the fingerprint never needs {@code =}. */
    private static final String BASE64_URL_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    private SpkiId() {
    }

    /**
     * Returns the SHA-256 digest of an SPKI DER encoding.
     *
     * @throws IllegalArgumentException if {@code spkiDer} is null or empty
     */
    @NonNull
    public static byte[] digest(@NonNull final byte[] spkiDer) {
        if (spkiDer == null || spkiDer.length == 0) {
            throw new IllegalArgumentException("spkiDer must not be empty");
        }

        try {
            return MessageDigest.getInstance(HASH_ALGORITHM).digest(spkiDer);
        } catch (NoSuchAlgorithmException e) {
            // Every Java and Android runtime ships SHA-256; this is a programming error.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * Returns the full digest as unpadded base64url; 43 characters for SHA-256.
     *
     * @throws IllegalArgumentException if {@code spkiDer} is null or empty
     */
    @NonNull
    public static String fingerprint(@NonNull final byte[] spkiDer) {
        return encodeBase64Url(digest(spkiDer));
    }

    /**
     * Returns the first 16 digest bytes as uppercase Crockford base32; 26 characters.
     *
     * @throws IllegalArgumentException if {@code spkiDer} is null or empty
     */
    @NonNull
    public static String deviceId(@NonNull final byte[] spkiDer) {
        final byte[] digest = digest(spkiDer);
        final byte[] idBytes = new byte[ID_HASH_BYTES];
        System.arraycopy(digest, 0, idBytes, 0, ID_HASH_BYTES);
        return Base32Crockford.encode(idBytes);
    }

    /** Encodes bytes as unpadded base64url, the fingerprint textual form. */
    @NonNull
    static String encodeBase64Url(@NonNull final byte[] data) {
        // Hand-rolled because java.util.Base64 is only available from API 26 and the project
        // supports API 25; android.util.Base64 would make this class Android-only.
        final StringBuilder encoded = new StringBuilder((data.length * 4 + 2) / 3);
        int buffer = 0;
        int bits = 0;
        for (final byte value : data) {
            buffer = (buffer << 8) | (value & 0xFF);
            bits += 8;
            while (bits >= 6) {
                bits -= 6;
                encoded.append(BASE64_URL_ALPHABET.charAt((buffer >> bits) & 0x3F));
            }
        }
        if (bits > 0) {
            encoded.append(BASE64_URL_ALPHABET.charAt((buffer << (6 - bits)) & 0x3F));
        }
        return encoded.toString();
    }
}
