package com.obby.android.localscreenshare.identity;

import com.obby.android.localscreenshare.support.Base32Crockford;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SpkiIdTest {

    /**
     * A fixed EC P-256 SubjectPublicKeyInfo: the {@code id-ecPublicKey} algorithm identifier plus
     * an uncompressed point. Real keys have the same shape, so the derivation is pinned to values
     * computed outside this implementation.
     */
    static final String SPKI_HEX = "3059301306072a8648ce3d020106082a8648ce3d030107034200"
        + "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296"
        + "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5";

    static final String FINGERPRINT = "XNJS-wzokyQ2-vjM0QQJgbie5K1rn-niorfnGqyyfNM";

    static final String DEVICE_ID = "BK955YRCX29J8DQTZ36D2109G4";

    /**
     * The same SPKI with the last point byte flipped.
     */
    static final String TAMPERED_SPKI_HEX = "3059301306072a8648ce3d020106082a8648ce3d030107034200"
        + "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296"
        + "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f4";

    static byte[] spki() {
        return decodeHex(SPKI_HEX);
    }

    static byte[] tamperedSpki() {
        return decodeHex(TAMPERED_SPKI_HEX);
    }

    @Test
    public void fingerprintMatchesTheKnownVector() {
        assertEquals(FINGERPRINT, SpkiId.fingerprint(spki()));
    }

    @Test
    public void deviceIdMatchesTheKnownVector() {
        assertEquals(DEVICE_ID, SpkiId.deviceId(spki()));
    }

    @Test
    public void derivationIsDeterministic() {
        assertEquals(SpkiId.fingerprint(spki()), SpkiId.fingerprint(spki()));
        assertEquals(SpkiId.deviceId(spki()), SpkiId.deviceId(spki()));
    }

    @Test
    public void deviceIdIsTwentySixCrockfordCharacters() {
        final String deviceId = SpkiId.deviceId(spki());

        assertEquals(26, deviceId.length());
        assertEquals(SpkiId.ID_LENGTH, deviceId.length());
        for (int i = 0; i < deviceId.length(); i++) {
            assertTrue(Base32Crockford.ALPHABET.indexOf(deviceId.charAt(i)) >= 0);
        }
    }

    @Test
    public void fingerprintIsUnpaddedBase64Url() {
        final String fingerprint = SpkiId.fingerprint(spki());

        assertEquals(SpkiId.FINGERPRINT_LENGTH, fingerprint.length());
        assertFalse(fingerprint.contains("="));
        assertFalse(fingerprint.contains("+"));
        assertFalse(fingerprint.contains("/"));
        for (int i = 0; i < fingerprint.length(); i++) {
            final char c = fingerprint.charAt(i);
            assertTrue(Character.isLetterOrDigit(c) || c == '-' || c == '_');
        }
    }

    @Test
    public void tamperedSpkiChangesBothIdentifiers() {
        assertNotEquals(SpkiId.fingerprint(spki()), SpkiId.fingerprint(tamperedSpki()));
        assertNotEquals(SpkiId.deviceId(spki()), SpkiId.deviceId(tamperedSpki()));
    }

    @Test
    public void emptyOrMissingInputIsRejected() {
        try {
            SpkiId.fingerprint(new byte[0]);
            fail("empty SPKI must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }

        try {
            SpkiId.deviceId(null);
            fail("null SPKI must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    static byte[] decodeHex(final String hex) {
        final byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
