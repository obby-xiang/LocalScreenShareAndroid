package com.obby.android.localscreenshare.trust;

import com.obby.android.localscreenshare.identity.SpkiId;
import com.obby.android.localscreenshare.identity.tls.SelfSignedCertificateEncoder;
import com.obby.android.localscreenshare.identity.tls.TlsCertificate;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class PinningTrustManagerTest {

    @Test
    public void matchingPinPasses() throws Exception {
        final Identity identity = identity();

        manager(identity.id, lookup(identity.pin), true)
            .checkServerTrusted(chain(identity.certificate), "ECDHE_ECDSA");
    }

    @Test
    public void unknownPinFailsWhenPinningIsRequired() throws Exception {
        final Identity identity = identity();

        assertFails(PinningTrustManager.REASON_PIN_UNKNOWN,
            () -> manager(identity.id, lookup(null), true).checkServerTrusted(
                chain(identity.certificate), "ECDHE_ECDSA"));
    }

    @Test
    public void unknownPinPassesWhenPinningIsOptional() throws Exception {
        final Identity identity = identity();

        manager(identity.id, lookup(null), false)
            .checkServerTrusted(chain(identity.certificate), "ECDHE_ECDSA");
    }

    @Test
    public void changedFingerprintFails() throws Exception {
        final Identity identity = identity();
        final Identity other = identity();

        assertFails(PinningTrustManager.REASON_IDENTITY_CHANGED,
            () -> manager(identity.id, lookup(other.pin), true).checkServerTrusted(
                chain(identity.certificate), "ECDHE_ECDSA"));
    }

    @Test
    public void expectedIdMismatchFailsEvenWithAMatchingPin() throws Exception {
        final Identity identity = identity();
        final Identity other = identity();

        assertFails(PinningTrustManager.REASON_IDENTITY_MISMATCH,
            () -> manager(other.id, lookup(identity.pin), true).checkServerTrusted(
                chain(identity.certificate), "ECDHE_ECDSA"));
    }

    @Test
    public void chainLongerThanOneCertificateFails() throws Exception {
        final Identity identity = identity();
        final Identity other = identity();
        final X509Certificate[] chain =
            new X509Certificate[]{identity.certificate, other.certificate};

        assertFails(PinningTrustManager.REASON_INVALID_CHAIN,
            () -> manager(identity.id, lookup(identity.pin), true)
                .checkServerTrusted(chain, "ECDHE_ECDSA"));
    }

    @Test
    public void emptyChainFails() throws Exception {
        final Identity identity = identity();

        assertFails(PinningTrustManager.REASON_INVALID_CHAIN,
            () -> manager(identity.id, lookup(identity.pin), true)
                .checkServerTrusted(new X509Certificate[0], "ECDHE_ECDSA"));
        assertFails(PinningTrustManager.REASON_INVALID_CHAIN,
            () -> manager(identity.id, lookup(identity.pin), true)
                .checkServerTrusted(null, "ECDHE_ECDSA"));
    }

    @Test
    public void expiredCertificateStillMatchesBecauseValidityIsNotChecked() throws Exception {
        final Identity identity = identity("200101000000Z", "210101000000Z");

        manager(identity.id, lookup(identity.pin), true)
            .checkServerTrusted(chain(identity.certificate), "ECDHE_ECDSA");
    }

    @Test
    public void socketAndEngineOverloadsBehaveLikeThePlainOne() throws Exception {
        final Identity identity = identity();
        final PinningTrustManager manager = manager(identity.id, lookup(identity.pin), true);

        manager.checkServerTrusted(chain(identity.certificate), "ECDHE_ECDSA",
            (java.net.Socket) null);
        manager.checkServerTrusted(chain(identity.certificate), "ECDHE_ECDSA",
            (javax.net.ssl.SSLEngine) null);
    }

    @Test
    public void clientRoleRefusesToAnswerClientChecks() throws Exception {
        final Identity identity = identity();

        assertFails("client_auth_unsupported",
            () -> manager(identity.id, lookup(identity.pin), true)
                .checkClientTrusted(chain(identity.certificate), "ECDHE_ECDSA"));
    }

    @Test
    public void serverRoleAcceptsASingleSelfSignedClientCertificate() throws Exception {
        final Identity identity = identity();
        final PinningTrustManager manager = new PinningTrustManager(null,
            lookup(null), true, PinningTrustManager.Role.SERVER);

        manager.checkClientTrusted(chain(identity.certificate), "ECDHE_ECDSA");
        manager.checkClientTrusted(chain(identity.certificate), "ECDHE_ECDSA",
            (java.net.Socket) null);
    }

    @Test
    public void serverRoleRefusesAChainAndServerChecks() throws Exception {
        final Identity identity = identity();
        final Identity other = identity();
        final PinningTrustManager manager = new PinningTrustManager(null,
            lookup(null), true, PinningTrustManager.Role.SERVER);

        assertFails(PinningTrustManager.REASON_INVALID_CHAIN,
            () -> manager.checkClientTrusted(
                new X509Certificate[]{identity.certificate, other.certificate}, "ECDHE_ECDSA"));
        assertFails("server_auth_unsupported",
            () -> manager.checkServerTrusted(chain(identity.certificate), "ECDHE_ECDSA"));
    }

    @Test
    public void acceptedIssuersAreEmptyForASelfSignedModel() {
        assertEquals(0, manager(null, lookup(null), true).getAcceptedIssuers().length);
    }

    private static PinningTrustManager manager(final String expectedId,
                                               final TlsPinLookup lookup,
                                               final boolean requirePinned) {
        return new PinningTrustManager(expectedId, lookup, requirePinned,
            PinningTrustManager.Role.CLIENT);
    }

    private static TlsPinLookup lookup(final TlsPin pin) {
        return id -> pin;
    }

    private static X509Certificate[] chain(final X509Certificate certificate) {
        return new X509Certificate[]{certificate};
    }

    private static void assertFails(final String reason, final ThrowingRunnable action) {
        try {
            action.run();
            fail("expected failure " + reason);
        } catch (CertificateException e) {
            assertEquals(reason, e.getMessage());
        } catch (Exception e) {
            fail("unexpected exception: " + e);
        }
    }

    private static Identity identity() throws Exception {
        return identity("200101000000Z", "491231235959Z");
    }

    private static Identity identity(final String notBefore, final String notAfter)
        throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        final KeyPair keyPair = generator.generateKeyPair();
        final byte[] der = SelfSignedCertificateEncoder.encode(
            keyPair.getPublic(), keyPair.getPrivate(), notBefore, notAfter);
        final X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
            .generateCertificate(new ByteArrayInputStream(der));
        final byte[] spki = TlsCertificate.fromCertificate(certificate).getSpki();
        final String id = SpkiId.deviceId(spki);
        return new Identity(certificate, id,
            new TlsPin(id, SpkiId.fingerprint(spki), 0L));
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class Identity {
        private final X509Certificate certificate;
        private final String id;
        private final TlsPin pin;

        private Identity(final X509Certificate certificate, final String id, final TlsPin pin) {
            this.certificate = certificate;
            this.id = id;
            this.pin = pin;
        }
    }
}
