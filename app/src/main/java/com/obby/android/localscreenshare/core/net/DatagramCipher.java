package com.obby.android.localscreenshare.core.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Per-datagram authenticated encryption of a media channel.
 *
 * <p>Every datagram is sealed with AES-256-GCM under a key that belongs to one direction of one
 * session. The nonce is the session salt followed by the datagram sequence number, and the sequence
 * number itself is authenticated as additional data, so a datagram cannot be replayed under a
 * different number, reordered without detection, or forged without the key. The tag is appended to
 * the ciphertext by the JCA provider, which is why the receiver can treat "authentication failed"
 * and "truncated" as one condition.</p>
 *
 * <p><b>Nonce discipline.</b> GCM loses its guarantees entirely when one key meets one nonce
 * twice, so the sequence number must be unique per key; that is the sender's obligation and the
 * reason the caller allocates it from a monotonic counter. The salt exists so two sessions never
 * derive the same nonce space even if a counter were to restart, and the keys are per direction so
 * the two ends never encrypt under the same key.</p>
 *
 * <p><b>What this does not provide.</b> The key arrives on the control plane, so there is no
 * forward secrecy: compromising the session key exposes the datagrams of that session. That trade
 * is deliberate - the alternative is DTLS, which was rejected for this path - and it is recorded in
 * the plan.</p>
 *
 * <p>Instances are immutable and safe to share; each operation creates its own {@link Cipher},
 * because a {@code Cipher} is stateful and not thread safe.</p>
 */
public final class DatagramCipher {

    /**
     * Length of the key, in bytes (AES-256).
     */
    public static final int KEY_BYTES = 32;

    /**
     * Length of the per-session nonce salt, in bytes.
     */
    public static final int NONCE_SALT_BYTES = 4;

    /**
     * Length of the GCM nonce, in bytes: salt plus sequence number.
     */
    public static final int NONCE_BYTES = 12;

    /**
     * Length of the authentication tag, in bytes.
     */
    public static final int TAG_BYTES = 16;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final int TAG_BITS = TAG_BYTES * 8;

    private final byte[] key;

    private final byte[] nonceSalt;

    /**
     * Creates a cipher for one direction of one session.
     *
     * @param key       AES-256 key; 32 bytes, required
     * @param nonceSalt per-session salt; 4 bytes, required
     * @throws IllegalArgumentException if a length is wrong
     */
    public DatagramCipher(byte[] key, byte[] nonceSalt) {
        if (key == null || key.length != KEY_BYTES) {
            throw new IllegalArgumentException("key must be " + KEY_BYTES + " bytes");
        }
        if (nonceSalt == null || nonceSalt.length != NONCE_SALT_BYTES) {
            throw new IllegalArgumentException("nonceSalt must be " + NONCE_SALT_BYTES + " bytes");
        }
        this.key = Arrays.copyOf(key, key.length);
        this.nonceSalt = Arrays.copyOf(nonceSalt, nonceSalt.length);
    }

    /**
     * Seals one datagram.
     *
     * @param wireSeq   sequence number of this datagram, unique per key; used as nonce suffix and
     *                  as additional authenticated data
     * @param plaintext datagram body to seal; may be empty
     * @return ciphertext with the authentication tag appended
     */
    public byte[] encrypt(long wireSeq, byte[] plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext must not be null");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonceOf(nonceSalt, wireSeq)));
            cipher.updateAAD(sequenceBytes(wireSeq));
            return cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is unavailable", e);
        }
    }

    /**
     * Opens one datagram.
     *
     * @param wireSeq    sequence number the datagram claims; authenticated as additional data
     * @param ciphertext sealed datagram, tag included
     * @return the plaintext body
     * @throws AuthenticationException if the tag does not verify, which covers a wrong key, a
     *                                 modified body and a modified sequence number
     * @throws IllegalArgumentException if {@code ciphertext} is null
     */
    public byte[] decrypt(long wireSeq, byte[] ciphertext) throws AuthenticationException {
        if (ciphertext == null) {
            throw new IllegalArgumentException("ciphertext must not be null");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonceOf(nonceSalt, wireSeq)));
            cipher.updateAAD(sequenceBytes(wireSeq));
            return cipher.doFinal(ciphertext);
        } catch (AEADBadTagException e) {
            throw new AuthenticationException("datagram authentication failed");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is unavailable", e);
        }
    }

    /**
     * Returns the nonce of one datagram: the salt followed by the sequence number.
     *
     * @param nonceSalt salt of the session; 4 bytes
     * @param wireSeq   sequence number of the datagram
     * @return the 12-byte nonce
     */
    static byte[] nonceOf(byte[] nonceSalt, long wireSeq) {
        ByteBuffer nonce = ByteBuffer.allocate(NONCE_BYTES);
        nonce.put(nonceSalt);
        nonce.putLong(wireSeq);
        return nonce.array();
    }

    private static byte[] sequenceBytes(long wireSeq) {
        return ByteBuffer.allocate(8).putLong(wireSeq).array();
    }

    /**
     * The datagram did not authenticate: wrong key, modified body or modified sequence number.
     */
    public static final class AuthenticationException extends IOException {

        private static final long serialVersionUID = 1L;

        /**
         * Creates the exception with a short English description.
         */
        public AuthenticationException(String message) {
            super(message);
        }
    }
}
