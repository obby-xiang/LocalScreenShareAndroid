package com.obby.android.localscreenshare.identity.tls;

import org.junit.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Collection;
import java.util.List;

import javax.security.auth.x500.X500Principal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SelfSignedCertificateEncoderTest {

    private static final long NOT_BEFORE_MS = 1_577_836_800_000L;

    private static final long NOT_AFTER_MS = 2_524_607_999_000L;

    @Test
    public void parsesBackAndVerifiesWithItsOwnKey() throws Exception {
        final KeyPair keyPair = generateKeyPair();

        final X509Certificate certificate = decode(encode(keyPair));

        certificate.verify(keyPair.getPublic());
        assertEquals(SelfSignedCertificateEncoder.SIGNATURE_ALGORITHM,
            certificate.getSigAlgName());
    }

    @Test
    public void carriesTheFixedSubjectAndIssuer() throws Exception {
        final X509Certificate certificate = decode(encode(generateKeyPair()));

        final X500Principal expected = new X500Principal("CN=" + TlsNames.AUTHORITY);
        assertEquals(expected, certificate.getSubjectX500Principal());
        assertEquals(expected, certificate.getIssuerX500Principal());
    }

    @Test
    public void validityIsTheWideWindow() throws Exception {
        final X509Certificate certificate = decode(encode(generateKeyPair()));

        assertEquals(NOT_BEFORE_MS, certificate.getNotBefore().getTime());
        assertEquals(NOT_AFTER_MS, certificate.getNotAfter().getTime());
    }

    @Test
    public void embedsTheSpkiVerbatim() throws Exception {
        final KeyPair keyPair = generateKeyPair();

        final X509Certificate certificate = decode(encode(keyPair));

        assertTrue(java.util.Arrays.equals(keyPair.getPublic().getEncoded(),
            certificate.getPublicKey().getEncoded()));
    }

    @Test
    public void advertisesTheAuthorityAsDnsName() throws Exception {
        final X509Certificate certificate = decode(encode(generateKeyPair()));

        final Collection<List<?>> names = certificate.getSubjectAlternativeNames();
        assertNotNull(names);
        boolean found = false;
        for (final List<?> name : names) {
            if (Integer.valueOf(2).equals(name.get(0)) && TlsNames.AUTHORITY.equals(name.get(1))) {
                found = true;
            }
        }
        assertTrue("dNSName SAN missing", found);
    }

    @Test
    public void allowsBothServerAndClientAuthentication() throws Exception {
        final X509Certificate certificate = decode(encode(generateKeyPair()));

        final List<String> usages = certificate.getExtendedKeyUsage();
        assertNotNull(usages);
        assertTrue(usages.contains("1.3.6.1.5.5.7.3.1"));
        assertTrue(usages.contains("1.3.6.1.5.5.7.3.2"));
    }

    @Test
    public void isNotACaAndOnlySigns() throws Exception {
        final X509Certificate certificate = decode(encode(generateKeyPair()));

        assertEquals(-1, certificate.getBasicConstraints());
        final boolean[] keyUsage = certificate.getKeyUsage();
        assertNotNull(keyUsage);
        assertTrue(keyUsage[0]);
        assertFalse(keyUsage[1]);
    }

    @Test
    public void serialIsPositiveAndDerivedFromTheKey() throws Exception {
        final KeyPair keyPair = generateKeyPair();

        final X509Certificate first = decode(encode(keyPair));
        final X509Certificate second = decode(encode(keyPair));

        final BigInteger serial = first.getSerialNumber();
        assertTrue(serial.signum() > 0);
        assertTrue(serial.bitLength() <= 128);
        assertEquals(serial, second.getSerialNumber());
        assertNotEquals(serial, decode(encode(generateKeyPair())).getSerialNumber());
    }

    @Test
    public void signatureIsAValidEcdsaOverTheTbs() throws Exception {
        final KeyPair keyPair = generateKeyPair();

        final X509Certificate certificate = decode(encode(keyPair));
        final Signature verifier = Signature.getInstance(
            SelfSignedCertificateEncoder.SIGNATURE_ALGORITHM);
        verifier.initVerify(keyPair.getPublic());
        verifier.update(certificate.getTBSCertificate());

        assertTrue(verifier.verify(certificate.getSignature()));
    }

    @Test
    public void explicitBoundsAreHonoured() throws Exception {
        final KeyPair keyPair = generateKeyPair();

        final X509Certificate certificate = decode(SelfSignedCertificateEncoder.encode(
            keyPair.getPublic(), keyPair.getPrivate(), "200101000000Z", "210101000000Z"));

        assertEquals(NOT_BEFORE_MS, certificate.getNotBefore().getTime());
        assertEquals(1_609_459_200_000L, certificate.getNotAfter().getTime());
    }

    @Test
    public void rejectsMissingArguments() throws Exception {
        final KeyPair keyPair = generateKeyPair();

        try {
            SelfSignedCertificateEncoder.encode(null, keyPair.getPrivate());
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            SelfSignedCertificateEncoder.encode(keyPair.getPublic(), null);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void rejectsAKeyWithoutX509Encoding() throws Exception {
        final KeyPair keyPair = generateKeyPair();
        final PublicKey withoutEncoding = new PublicKey() {
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
        };

        try {
            SelfSignedCertificateEncoder.encode(withoutEncoding, (PrivateKey) keyPair.getPrivate());
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static byte[] encode(final KeyPair keyPair) throws Exception {
        return SelfSignedCertificateEncoder.encode(keyPair.getPublic(), keyPair.getPrivate());
    }

    private static X509Certificate decode(final byte[] der) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
            .generateCertificate(new java.io.ByteArrayInputStream(der));
    }

    private static KeyPair generateKeyPair() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }
}
