package com.obby.android.localscreenshare.identity;

import org.junit.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DeviceIdentityTest {

    @Test
    public void derivesTheKnownVectorFromSpki() {
        final DeviceIdentity identity = DeviceIdentity.fromSpki(SpkiIdTest.spki(), null);

        assertEquals(SpkiIdTest.DEVICE_ID, identity.getId());
        assertEquals(SpkiIdTest.FINGERPRINT, identity.getFingerprint());
    }

    @Test
    public void firstRunIsNotAChange() {
        final DeviceIdentity identity = DeviceIdentity.fromSpki(SpkiIdTest.spki(), null);

        assertFalse(identity.isChanged());
    }

    @Test
    public void matchingRecordIsNotAChange() {
        final DeviceIdentity identity =
            DeviceIdentity.fromSpki(SpkiIdTest.spki(), SpkiIdTest.FINGERPRINT);

        assertFalse(identity.isChanged());
    }

    @Test
    public void differentRecordIsAChange() {
        final DeviceIdentity identity = DeviceIdentity.fromSpki(SpkiIdTest.spki(),
            SpkiId.fingerprint(SpkiIdTest.tamperedSpki()));

        assertTrue(identity.isChanged());
    }

    @Test
    public void blankRecordIsTreatedAsFirstRun() {
        assertFalse(DeviceIdentity.fromSpki(SpkiIdTest.spki(), "").isChanged());
        assertFalse(DeviceIdentity.fromSpki(SpkiIdTest.spki(), "   ").isChanged());
    }

    @Test
    public void shortIdAndInstanceNameComeFromTheDeviceId() {
        final DeviceIdentity identity = DeviceIdentity.fromSpki(SpkiIdTest.spki(), null);

        assertEquals(SpkiIdTest.DEVICE_ID.substring(0, 8), identity.shortId());
        assertEquals("LSS-" + SpkiIdTest.DEVICE_ID.substring(0, 8), identity.instanceName());
    }

    @Test
    public void keyPairDerivationUsesTheEncodedPublicKey() throws Exception {
        final KeyPair keyPair = generateKeyPair();

        final DeviceIdentity fromPair = DeviceIdentity.fromKeyPair(keyPair, null);
        final DeviceIdentity fromSpki =
            DeviceIdentity.fromSpki(keyPair.getPublic().getEncoded(), null);

        assertEquals(fromSpki, fromPair);
        assertEquals(26, fromPair.getId().length());
        assertEquals(43, fromPair.getFingerprint().length());
    }

    @Test
    public void distinctKeysProduceDistinctIdentities() throws Exception {
        final DeviceIdentity first = DeviceIdentity.fromKeyPair(generateKeyPair(), null);
        final DeviceIdentity second = DeviceIdentity.fromKeyPair(generateKeyPair(), null);

        assertNotEquals(first.getId(), second.getId());
        assertNotEquals(first.getFingerprint(), second.getFingerprint());
    }

    @Test
    public void keyPairWithoutPublicEncodingIsRejected() {
        final KeyPair keyPair = new KeyPair(new PublicKeyWithoutEncoding(), null);

        try {
            DeviceIdentity.fromKeyPair(keyPair, null);
            fail("a public key without X.509 encoding cannot carry an identity");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    static KeyPair generateKeyPair() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static final class PublicKeyWithoutEncoding implements java.security.PublicKey {

        private static final long serialVersionUID = 1L;

        @Override
        public String getAlgorithm() {
            return "EC";
        }

        @Override
        public String getFormat() {
            return null;
        }

        @Override
        public byte[] getEncoded() {
            return null;
        }
    }
}
