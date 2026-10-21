package com.obby.android.localscreenshare.trust;

import com.obby.android.localscreenshare.identity.tls.TlsCertificate;
import com.obby.android.localscreenshare.identity.tls.TlsMaterial;

import org.junit.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.spec.ECGenParameterSpec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class PeerIdentityMapperTest {

    @Test
    public void mapsASingleCertificateToItsIdentity() throws Exception {
        final TlsCertificate certificate = certificate();

        final TlsCertificate mapped =
            PeerIdentityMapper.fromChain(new Certificate[]{certificate.getCertificate()});

        assertEquals(certificate.getDeviceId(), mapped.getDeviceId());
        assertEquals(certificate.getFingerprint(), mapped.getFingerprint());
    }

    @Test
    public void refusesAnEmptyOrMissingChain() throws Exception {
        assertFails(null);
        assertFails(new Certificate[0]);
    }

    @Test
    public void refusesAChainLongerThanOne() throws Exception {
        final Certificate first = certificate().getCertificate();
        final Certificate second = certificate().getCertificate();

        assertFails(new Certificate[]{first, second});
    }

    @Test
    public void refusesANonX509Certificate() throws Exception {
        final Certificate stub = new Certificate("stub") {
            @Override
            public byte[] getEncoded() {
                return new byte[]{0x01};
            }

            @Override
            public void verify(final java.security.PublicKey key) {
            }

            @Override
            public void verify(final java.security.PublicKey key, final String provider) {
            }

            @Override
            public String toString() {
                return "stub";
            }

            @Override
            public java.security.PublicKey getPublicKey() {
                return null;
            }
        };

        assertFails(new Certificate[]{stub});
    }

    @Test
    public void absentSessionHasNoIdentity() {
        assertNull(PeerIdentityMapper.fromSession(null));
    }

    private static void assertFails(final Certificate[] chain) {
        try {
            PeerIdentityMapper.fromChain(chain);
            fail();
        } catch (CertificateException expected) {
            // expected
        }
    }

    private static TlsCertificate certificate() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        final KeyPair keyPair = generator.generateKeyPair();
        return TlsMaterial.fromKeyPair(keyPair).getCertificate();
    }
}
