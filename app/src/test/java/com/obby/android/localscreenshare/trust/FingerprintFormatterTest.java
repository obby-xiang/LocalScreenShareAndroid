package com.obby.android.localscreenshare.trust;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FingerprintFormatterTest {

    private static final String ASCENDING = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";

    private static final String ASCENDING_DISPLAY =
        "00010203 04050607 08090A0B 0C0D0E0F 10111213 14151617 18191A1B 1C1D1E1F";

    private static final String ZEROES = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    private static final String ZEROES_DISPLAY =
        "00000000 00000000 00000000 00000000 00000000 00000000 00000000 00000000";

    @Test
    public void displaysEightGroupsOfUppercaseHex() {
        assertEquals(ASCENDING_DISPLAY, FingerprintFormatter.toDisplay(ASCENDING));
        assertEquals(ZEROES_DISPLAY, FingerprintFormatter.toDisplay(ZEROES));
    }

    @Test
    public void displayLengthIsTheFullSixtyFourCharacters() {
        final String display = FingerprintFormatter.toDisplay(ASCENDING);

        assertEquals(64, display.replace(" ", "").length());
        assertEquals(7, display.length() - display.replace(" ", "").length());
    }

    @Test
    public void roundTripsCanonicalToDisplayAndBack() {
        assertEquals(ASCENDING, FingerprintFormatter.toCanonical(ASCENDING_DISPLAY));
        assertEquals(ASCENDING_DISPLAY,
            FingerprintFormatter.toDisplay(FingerprintFormatter.toCanonical(ASCENDING_DISPLAY)));
    }

    @Test
    public void acceptsUngroupedAndLowercaseHex() {
        assertEquals(ASCENDING,
            FingerprintFormatter.toCanonical(ASCENDING_DISPLAY.replace(" ", "").toLowerCase(
                java.util.Locale.ROOT)));
    }

    @Test
    public void rejectsMalformedInput() {
        assertEquals("", FingerprintFormatter.toDisplay(null));
        assertEquals("", FingerprintFormatter.toDisplay(""));
        assertEquals("", FingerprintFormatter.toDisplay("not-a-fingerprint"));
        assertNull(FingerprintFormatter.toCanonical(null));
        assertNull(FingerprintFormatter.toCanonical("00"));
        assertNull(FingerprintFormatter.toCanonical(ASCENDING_DISPLAY.replace('0', 'G')));
    }

    @Test
    public void recognisesCanonicalValues() {
        assertTrue(FingerprintFormatter.isCanonical(ASCENDING));
        assertFalse(FingerprintFormatter.isCanonical(ASCENDING_DISPLAY));
        assertFalse(FingerprintFormatter.isCanonical(null));
        assertFalse(FingerprintFormatter.isCanonical("A"));
    }

    @Test
    public void rejectsNonZeroPaddingBits() {
        // 43 base64url characters carry 258 bits; the trailing two bits of this value are set.
        final String malformed = ASCENDING.substring(0, 42) + "B";

        assertFalse(FingerprintFormatter.isCanonical(malformed));
    }
}
