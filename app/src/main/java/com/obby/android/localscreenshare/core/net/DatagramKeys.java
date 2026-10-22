package com.obby.android.localscreenshare.core.net;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Per-direction keys and nonce salt of one datagram channel.
 *
 * <p>One session never reuses key material: every offer generates a fresh server-to-client key, a
 * fresh client-to-server key and a fresh salt, and the offer carries them to the client on the
 * control plane. Two directions are separate keys because the two ends encrypt independently under
 * their own counters, and one nonce collision between them would break both.</p>
 *
 * <p>Instances are immutable; arrays are copied in and out. The material lives as long as the
 * channel and is dropped with it, which is the best this design can do without a key exchange -
 * forward secrecy is explicitly not provided (see {@link DatagramCipher}).</p>
 */
public final class DatagramKeys {

    private final byte[] keyC2s;

    private final byte[] keyS2c;

    private final byte[] nonceSalt;

    /**
     * Creates keys from explicit bytes.
     *
     * @param keyC2s    key the client encrypts with; 32 bytes, required
     * @param keyS2c    key the server encrypts with; 32 bytes, required
     * @param nonceSalt nonce salt of the channel; 4 bytes, required
     * @throws IllegalArgumentException if a length is wrong
     */
    public DatagramKeys(byte[] keyC2s, byte[] keyS2c, byte[] nonceSalt) {
        if (keyC2s == null || keyC2s.length != DatagramCipher.KEY_BYTES) {
            throw new IllegalArgumentException("keyC2s must be " + DatagramCipher.KEY_BYTES + " bytes");
        }
        if (keyS2c == null || keyS2c.length != DatagramCipher.KEY_BYTES) {
            throw new IllegalArgumentException("keyS2c must be " + DatagramCipher.KEY_BYTES + " bytes");
        }
        if (nonceSalt == null || nonceSalt.length != DatagramCipher.NONCE_SALT_BYTES) {
            throw new IllegalArgumentException(
                    "nonceSalt must be " + DatagramCipher.NONCE_SALT_BYTES + " bytes");
        }
        this.keyC2s = Arrays.copyOf(keyC2s, keyC2s.length);
        this.keyS2c = Arrays.copyOf(keyS2c, keyS2c.length);
        this.nonceSalt = Arrays.copyOf(nonceSalt, nonceSalt.length);
    }

    /**
     * Generates fresh keys for one channel.
     *
     * @param random secure random source; required
     * @return keys that were never used before
     * @throws IllegalArgumentException if {@code random} is null
     */
    public static DatagramKeys generate(SecureRandom random) {
        if (random == null) {
            throw new IllegalArgumentException("random must not be null");
        }
        byte[] c2s = new byte[DatagramCipher.KEY_BYTES];
        byte[] s2c = new byte[DatagramCipher.KEY_BYTES];
        byte[] salt = new byte[DatagramCipher.NONCE_SALT_BYTES];
        random.nextBytes(c2s);
        random.nextBytes(s2c);
        random.nextBytes(salt);
        return new DatagramKeys(c2s, s2c, salt);
    }

    /**
     * Returns a copy of the client-to-server key.
     */
    public byte[] getKeyC2s() {
        return Arrays.copyOf(keyC2s, keyC2s.length);
    }

    /**
     * Returns a copy of the server-to-client key.
     */
    public byte[] getKeyS2c() {
        return Arrays.copyOf(keyS2c, keyS2c.length);
    }

    /**
     * Returns a copy of the nonce salt.
     */
    public byte[] getNonceSalt() {
        return Arrays.copyOf(nonceSalt, nonceSalt.length);
    }

    @Override
    public String toString() {
        // Keys are never printed; only the fact that this object exists.
        return "DatagramKeys{redacted}";
    }
}
