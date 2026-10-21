package com.obby.android.localscreenshare.trust;

import com.obby.android.localscreenshare.identity.tls.TlsCertificate;
import com.obby.android.localscreenshare.identity.tls.TlsMaterial;

import org.junit.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class DiscoveryTrustManagerTest {

    @Test
    public void observesTheSingleCertificate() throws Exception {
        final TlsMaterial material = material();
        final AtomicReference<TlsCertificate> observed = new AtomicReference<>();
        final DiscoveryTrustManager manager = new DiscoveryTrustManager(observed::set);

        manager.checkServerTrusted(chain(material), "ECDHE_ECDSA");

        assertNotNull(observed.get());
        assertEquals(material.getDeviceId(), observed.get().getDeviceId());
        assertEquals(material.getFingerprint(), observed.get().getFingerprint());
    }

    @Test
    public void refusesAMultiCertificateChain() throws Exception {
        final DiscoveryTrustManager manager = new DiscoveryTrustManager(certificate -> {
        });
        final X509Certificate first = material().getCertificate().getCertificate();
        final X509Certificate second = material().getCertificate().getCertificate();

        assertFails(manager, new X509Certificate[]{first, second});
        assertFails(manager, new X509Certificate[0]);
        assertFails(manager, null);
    }

    @Test
    public void doesNotAnswerClientChecks() throws Exception {
        final DiscoveryTrustManager manager = new DiscoveryTrustManager(certificate -> {
        });
        try {
            manager.checkClientTrusted(chain(material()), "ECDHE_ECDSA");
            fail();
        } catch (CertificateException expected) {
            assertEquals("client_auth_unsupported", expected.getMessage());
        }
    }

    private static void assertFails(final DiscoveryTrustManager manager,
                                    final X509Certificate[] chain) {
        try {
            manager.checkServerTrusted(chain, "ECDHE_ECDSA");
            fail();
        } catch (CertificateException expected) {
            assertEquals(DiscoveryTrustManager.REASON_INVALID_CHAIN, expected.getMessage());
        }
    }

    private static X509Certificate[] chain(final TlsMaterial material) {
        return new X509Certificate[]{material.getCertificate().getCertificate()};
    }

    private static TlsMaterial material() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        final KeyPair keyPair = generator.generateKeyPair();
        return TlsMaterial.fromKeyPair(keyPair);
    }
}
