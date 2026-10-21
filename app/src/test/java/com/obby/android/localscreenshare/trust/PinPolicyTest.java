package com.obby.android.localscreenshare.trust;

import com.obby.android.localscreenshare.identity.SpkiId;

import org.junit.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class PinPolicyTest {

    @Test
    public void noPinIsAnUnknownDevice() throws Exception {
        final byte[] spki = spki();

        assertEquals(PinPolicy.Outcome.UNKNOWN, PinPolicy.decide(null, spki, null));
        assertEquals(PinPolicy.Outcome.UNKNOWN,
            PinPolicy.decide(SpkiId.deviceId(spki), spki, null));
    }

    @Test
    public void matchingPinPasses() throws Exception {
        final byte[] spki = spki();

        assertEquals(PinPolicy.Outcome.MATCH,
            PinPolicy.decide(SpkiId.deviceId(spki), spki, pinFor(spki, spki)));
    }

    @Test
    public void differentFingerprintIsAChange() throws Exception {
        final byte[] spki = spki();
        final byte[] other = spki();

        assertEquals(PinPolicy.Outcome.CHANGED,
            PinPolicy.decide(SpkiId.deviceId(spki), spki, pinFor(spki, other)));
    }

    @Test
    public void expectedIdMismatchCannotBeConfirmedAway() throws Exception {
        final byte[] spki = spki();
        final byte[] other = spki();
        final TlsPin matchingPin = pinFor(spki, spki);

        assertEquals(PinPolicy.Outcome.IDENTITY_MISMATCH,
            PinPolicy.decide(SpkiId.deviceId(other), spki, matchingPin));
        assertEquals(PinPolicy.Outcome.IDENTITY_MISMATCH,
            PinPolicy.decide(SpkiId.deviceId(other), spki, null));
    }

    @Test
    public void absentOrBlankExpectedIdSkipsSelfConsistency() throws Exception {
        final byte[] spki = spki();
        final TlsPin pin = pinFor(spki, spki);

        assertEquals(PinPolicy.Outcome.MATCH, PinPolicy.decide(null, spki, pin));
        assertEquals(PinPolicy.Outcome.MATCH, PinPolicy.decide("   ", spki, pin));
    }

    @Test
    public void aPinOfAnotherIdIsAChange() throws Exception {
        final byte[] spki = spki();
        final byte[] other = spki();

        assertEquals(PinPolicy.Outcome.CHANGED, PinPolicy.decide(null, spki,
            new TlsPin(SpkiId.deviceId(other), SpkiId.fingerprint(spki), 0L)));
    }

    @Test
    public void surroundingWhitespaceIsTolerated() throws Exception {
        final byte[] spki = spki();
        final TlsPin pin = new TlsPin("  " + SpkiId.deviceId(spki) + "  ",
            "  " + SpkiId.fingerprint(spki) + "  ", 0L);

        assertEquals(PinPolicy.Outcome.MATCH,
            PinPolicy.decide(SpkiId.deviceId(spki), spki, pin));
    }

    @Test
    public void fingerprintComparisonIsExact() throws Exception {
        final byte[] spki = spki();
        final String fingerprint = SpkiId.fingerprint(spki);
        final char first = fingerprint.charAt(0);
        final String changed = (first == 'A' ? 'B' : 'A') + fingerprint.substring(1);
        final TlsPin pin = new TlsPin(SpkiId.deviceId(spki), changed, 0L);

        assertEquals(PinPolicy.Outcome.CHANGED,
            PinPolicy.decide(SpkiId.deviceId(spki), spki, pin));
    }

    @Test
    public void derivedValuesFollowTheSameRules() {
        final TlsPin pin = new TlsPin("ID-A", "FP-A", 0L);

        assertEquals(PinPolicy.Outcome.MATCH,
            PinPolicy.decide("ID-A", "ID-A", "FP-A", pin));
        assertEquals(PinPolicy.Outcome.IDENTITY_MISMATCH,
            PinPolicy.decide("ID-A", "ID-B", "FP-A", pin));
        assertEquals(PinPolicy.Outcome.UNKNOWN,
            PinPolicy.decide("ID-A", "ID-A", "FP-A", null));
        assertEquals(PinPolicy.Outcome.CHANGED,
            PinPolicy.decide("ID-A", "ID-A", null, pin));
        assertEquals(PinPolicy.Outcome.IDENTITY_MISMATCH,
            PinPolicy.decide("ID-A", null, "FP-A", pin));
    }

    @Test
    public void rejectsAnEmptySpki() {
        try {
            PinPolicy.decide(null, new byte[0], null);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static TlsPin pinFor(final byte[] idSpki, final byte[] fingerprintSpki) {
        return new TlsPin(SpkiId.deviceId(idSpki), SpkiId.fingerprint(fingerprintSpki), 0L);
    }

    private static byte[] spki() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        final KeyPair keyPair = generator.generateKeyPair();
        return keyPair.getPublic().getEncoded();
    }
}
