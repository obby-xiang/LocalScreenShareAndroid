package com.obby.android.localscreenshare.support;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class Base32CrockfordTest {

    // Expected values come from an independent base32 bit-packing oracle with the Crockford
    // alphabet substituted, so the implementation is pinned rather than compared with itself.
    private static final byte[] BYTES_16 = {
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
        0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
    };

    @Test
    public void alphabetExcludesAmbiguousLetters() {
        assertEquals(32, Base32Crockford.ALPHABET.length());
        assertEquals(32, Base32Crockford.RADIX);
        assertTrue(Base32Crockford.ALPHABET.startsWith("0123456789ABCDEFGHJKMNPQRSTVWXYZ"));
        assertTrue(Base32Crockford.ALPHABET.indexOf('I') < 0);
        assertTrue(Base32Crockford.ALPHABET.indexOf('L') < 0);
        assertTrue(Base32Crockford.ALPHABET.indexOf('O') < 0);
        assertTrue(Base32Crockford.ALPHABET.indexOf('U') < 0);
    }

    @Test
    public void encodedLengthRoundsUp() {
        assertEquals(0, Base32Crockford.encodedLength(0));
        assertEquals(2, Base32Crockford.encodedLength(1));
        assertEquals(4, Base32Crockford.encodedLength(2));
        assertEquals(26, Base32Crockford.encodedLength(16));
        assertEquals(52, Base32Crockford.encodedLength(32));
    }

    @Test
    public void encodesKnownVectors() {
        assertEquals("", Base32Crockford.encode(new byte[0]));
        assertEquals("00", Base32Crockford.encode(new byte[] { 0x00 }));
        assertEquals("04", Base32Crockford.encode(new byte[] { 0x01 }));
        assertEquals("CR", Base32Crockford.encode(new byte[] { 0x66 }));
        assertEquals("CSQG", Base32Crockford.encode(new byte[] { 0x66, 0x6F }));
        assertEquals("CSQPY", Base32Crockford.encode(new byte[] { 0x66, 0x6F, 0x6F }));
        assertEquals("000G40R40M30E209185GR38E1W", Base32Crockford.encode(BYTES_16));
    }

    @Test
    public void sixteenBytesEncodeToTwentySixCharacters() {
        final String encoded = Base32Crockford.encode(BYTES_16);

        assertEquals(26, encoded.length());
        for (int i = 0; i < encoded.length(); i++) {
            assertTrue(Base32Crockford.ALPHABET.indexOf(encoded.charAt(i)) >= 0);
        }
    }

    @Test
    public void decodesBackToTheSameBytes() {
        assertArrayEquals(BYTES_16, Base32Crockford.decode("000G40R40M30E209185GR38E1W"));
        assertArrayEquals(new byte[] { 0x66 }, Base32Crockford.decode("CR"));
        assertArrayEquals(new byte[] { 0x66, 0x6F }, Base32Crockford.decode("CSQG"));
    }

    @Test
    public void decodeAcceptsLowercaseHyphensAndAmbiguousLetters() {
        assertArrayEquals(new byte[] { 0x66, 0x6F }, Base32Crockford.decode("csqg"));
        assertArrayEquals(new byte[] { 0x66, 0x6F }, Base32Crockford.decode("CS-QG"));
        // Crockford reads I, L and O as the digits they look like, not as distinct symbols.
        assertArrayEquals(Base32Crockford.decode("04"), Base32Crockford.decode("O4"));
        assertArrayEquals(Base32Crockford.decode("14"), Base32Crockford.decode("I4"));
        assertArrayEquals(Base32Crockford.decode("14"), Base32Crockford.decode("L4"));
    }

    @Test
    public void decodeRejectsCharactersOutsideTheAlphabet() {
        try {
            Base32Crockford.decode("U");
            fail("U is not part of the alphabet");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("U"));
        }

        try {
            Base32Crockford.decode("CR!");
            fail("punctuation is not part of the alphabet");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("!"));
        }
    }

    @Test
    public void nullArgumentsAreRejected() {
        try {
            Base32Crockford.encode(null);
            fail("null bytes must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }

        try {
            Base32Crockford.decode(null);
            fail("null text must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
