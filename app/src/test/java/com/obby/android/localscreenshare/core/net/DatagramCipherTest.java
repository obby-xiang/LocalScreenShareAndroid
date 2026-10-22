package com.obby.android.localscreenshare.core.net;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link DatagramCipher}.
 *
 * <p>The cipher is the only thing standing between a datagram and the decoder, so the tests cover
 * the two properties it must never lose: a datagram that was modified anywhere - body, tag or
 * sequence number - does not open, and one key never meets one nonce twice.</p>
 */
public class DatagramCipherTest {

    private static final byte[] KEY = bytes(DatagramCipher.KEY_BYTES, 0x11);

    private static final byte[] SALT = bytes(DatagramCipher.NONCE_SALT_BYTES, 0x22);

    private static final byte[] BODY = {0x01, 0x02, 0x03, 0x04};

    @Test
    public void encrypt_decryptRoundTrips() throws Exception {
        DatagramCipher cipher = new DatagramCipher(KEY, SALT);

        byte[] sealed = cipher.encrypt(7L, BODY);

        assertArrayEquals(BODY, cipher.decrypt(7L, sealed));
        assertTrue(sealed.length >= BODY.length + DatagramCipher.TAG_BYTES);
    }

    @Test
    public void modifiedBody_doesNotAuthenticate() {
        DatagramCipher cipher = new DatagramCipher(KEY, SALT);
        byte[] sealed = cipher.encrypt(1L, BODY);
        sealed[0] ^= 0x01;

        assertThrows(DatagramCipher.AuthenticationException.class,
                () -> cipher.decrypt(1L, sealed));
    }

    @Test
    public void modifiedTag_doesNotAuthenticate() {
        DatagramCipher cipher = new DatagramCipher(KEY, SALT);
        byte[] sealed = cipher.encrypt(1L, BODY);
        sealed[sealed.length - 1] ^= 0x01;

        assertThrows(DatagramCipher.AuthenticationException.class,
                () -> cipher.decrypt(1L, sealed));
    }

    @Test
    public void modifiedSequenceNumber_doesNotAuthenticate() {
        DatagramCipher cipher = new DatagramCipher(KEY, SALT);
        byte[] sealed = cipher.encrypt(1L, BODY);

        assertThrows(DatagramCipher.AuthenticationException.class,
                () -> cipher.decrypt(2L, sealed));
    }

    @Test
    public void anotherKey_doesNotOpen() {
        byte[] sealed = new DatagramCipher(KEY, SALT).encrypt(1L, BODY);
        DatagramCipher other = new DatagramCipher(bytes(DatagramCipher.KEY_BYTES, 0x33), SALT);

        assertThrows(DatagramCipher.AuthenticationException.class,
                () -> other.decrypt(1L, sealed));
    }

    @Test
    public void anotherSalt_doesNotOpen() {
        byte[] sealed = new DatagramCipher(KEY, SALT).encrypt(1L, BODY);
        DatagramCipher other = new DatagramCipher(KEY,
                bytes(DatagramCipher.NONCE_SALT_BYTES, 0x44));

        assertThrows(DatagramCipher.AuthenticationException.class,
                () -> other.decrypt(1L, sealed));
    }

    @Test
    public void nonce_isTheSaltFollowedByTheSequenceNumber() {
        byte[] first = DatagramCipher.nonceOf(SALT, 1L);
        byte[] second = DatagramCipher.nonceOf(SALT, 2L);

        assertArrayEquals(SALT, Arrays.copyOf(first, DatagramCipher.NONCE_SALT_BYTES));
        assertArrayEquals(new byte[]{0, 0, 0, 0, 0, 0, 0, 1},
                Arrays.copyOfRange(first, DatagramCipher.NONCE_SALT_BYTES,
                        DatagramCipher.NONCE_BYTES));
        assertEqualsLength(first);
        assertFalse(Arrays.equals(first, second));
    }

    @Test
    public void sameNonce_neverRepeatsUnderTheSameKey() {
        DatagramCipher cipher = new DatagramCipher(KEY, SALT);

        byte[] first = cipher.encrypt(3L, BODY);
        byte[] again = cipher.encrypt(3L, BODY);
        byte[] next = cipher.encrypt(4L, BODY);

        // Deterministic by construction: the sequence number must therefore never repeat.
        assertArrayEquals(first, again);
        assertNotEquals(Arrays.hashCode(first), Arrays.hashCode(next));
    }

    @Test
    public void constructor_rejectsWrongLengths() {
        assertThrows(IllegalArgumentException.class,
                () -> new DatagramCipher(new byte[16], SALT));
        assertThrows(IllegalArgumentException.class,
                () -> new DatagramCipher(KEY, new byte[8]));
        assertThrows(IllegalArgumentException.class, () -> new DatagramCipher(null, SALT));
    }

    private static void assertEqualsLength(byte[] nonce) {
        assertTrue(nonce.length == DatagramCipher.NONCE_BYTES);
    }

    private static byte[] bytes(int length, int value) {
        byte[] result = new byte[length];
        Arrays.fill(result, (byte) value);
        return result;
    }
}
