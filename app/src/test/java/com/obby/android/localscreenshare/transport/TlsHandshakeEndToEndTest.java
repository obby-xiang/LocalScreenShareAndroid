package com.obby.android.localscreenshare.transport;

import com.obby.android.localscreenshare.grpc.screenstream.DeviceSummary;
import com.obby.android.localscreenshare.grpc.screenstream.RegisterRequest;
import com.obby.android.localscreenshare.grpc.screenstream.RegisterResponse;
import com.obby.android.localscreenshare.grpc.screenstream.ScreenStreamServiceGrpc;
import com.obby.android.localscreenshare.identity.SpkiId;
import com.obby.android.localscreenshare.identity.tls.TlsCertificate;
import com.obby.android.localscreenshare.identity.tls.TlsMaterial;
import com.obby.android.localscreenshare.identity.tls.TlsNames;
import com.obby.android.localscreenshare.trust.PinningTrustManager;
import com.obby.android.localscreenshare.trust.TlsPin;
import com.obby.android.localscreenshare.trust.TlsPinLookup;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsServerCredentials;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.okhttp.OkHttpServerBuilder;
import io.grpc.stub.StreamObserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Real TLS handshakes between an OkHttp gRPC server and the pinned OkHttp channel.
 *
 * <p>The test runs in the JVM over loopback: both endpoints use the production certificate
 * encoder, key adapter and trust manager, with software EC keys instead of the AndroidKeyStore.
 * ALPN negotiation needs a JDK that exposes {@code SSLParameters.setApplicationProtocols}, which
 * every supported build JDK does; a failure here is a real configuration failure, not a reason to
 * skip the handshake.</p>
 */
public class TlsHandshakeEndToEndTest {

    private static final String HOST = "127.0.0.1";

    private static final long DEADLINE_SECONDS = 8L;

    private final List<ManagedChannel> mChannels = new ArrayList<>();

    private Server mServer;

    @After
    public void tearDown() {
        for (final ManagedChannel channel : mChannels) {
            channel.shutdownNow();
        }
        mChannels.clear();
        if (mServer != null) {
            mServer.shutdownNow();
            mServer = null;
        }
    }

    @Test
    public void matchingPinCompletesTheHandshakeAndTheCall() throws Exception {
        final TlsMaterial server = material();
        final int port = startServer(server);

        final RegisterResponse response = register(channel(port, server.getDeviceId(),
            pin(server), true));

        assertTrue(response.getAccepted());
        assertEquals(server.getDeviceId(), response.getSelf().getDeviceId());
        assertEquals(server.getFingerprint(), response.getSelf().getFingerprint());
    }

    @Test
    public void missingPinFailsWithPinUnknown() throws Exception {
        final TlsMaterial server = material();
        final int port = startServer(server);

        assertFailsWith("pin_unknown", () -> register(channel(port, server.getDeviceId(),
            id -> null, true)));
    }

    @Test
    public void changedPinFailsWithIdentityChanged() throws Exception {
        final TlsMaterial server = material();
        final TlsMaterial other = material();
        final int port = startServer(server);

        assertFailsWith("identity_changed", () -> register(channel(port, server.getDeviceId(),
            id -> new TlsPin(server.getDeviceId(), other.getFingerprint(), 0L), true)));
    }

    @Test
    public void expectedIdMismatchFailsEvenWithAMatchingPin() throws Exception {
        final TlsMaterial server = material();
        final TlsMaterial other = material();
        final int port = startServer(server);

        assertFailsWith("identity_mismatch", () -> register(channel(port, other.getDeviceId(),
            pin(server), true)));
    }

    @Test
    public void withoutOverrideAuthorityHostnameVerificationFails() throws Exception {
        final TlsMaterial server = material();
        final int port = startServer(server);

        try {
            register(channel(port, server.getDeviceId(), pin(server), false));
            fail("hostname verification must fail without the authority override");
        } catch (StatusRuntimeException e) {
            final String text = failureText(e);
            assertTrue("unexpected failure: " + text, text.contains("SSLPeerUnverifiedException")
                || text.toLowerCase(java.util.Locale.ROOT).contains("hostname"));
        }
    }

    @Test
    public void certificateCarriesTheIdentityNotACertificateHash() throws Exception {
        final TlsMaterial server = material();

        final X509Certificate certificate = server.getCertificate().getCertificate();
        final byte[] spki = certificate.getPublicKey().getEncoded();

        assertEquals(server.getDeviceId(), SpkiId.deviceId(spki));
        assertEquals(server.getFingerprint(), SpkiId.fingerprint(spki));
        assertEquals(TlsNames.AUTHORITY, certificate.getSubjectAlternativeNames()
            .iterator().next().get(1));
    }

    private int startServer(final TlsMaterial material) throws IOException {
        mServer = OkHttpServerBuilder
            .forPort(0, TlsServerCredentials.newBuilder()
                .keyManager(material.getKeyManager())
                .build())
            .addService(new EchoRegisterService(material))
            .build()
            .start();
        return mServer.getPort();
    }

    private ManagedChannel channel(final int port, final String expectedDeviceId,
                                   final TlsPinLookup pins, final boolean overrideAuthority) {
        final OkHttpChannelBuilder builder = OkHttpChannelBuilder.forAddress(HOST, port,
            io.grpc.TlsChannelCredentials.newBuilder()
                .trustManager(new PinningTrustManager(expectedDeviceId, pins, true,
                    PinningTrustManager.Role.CLIENT))
                .build());
        if (overrideAuthority) {
            builder.overrideAuthority(TlsNames.AUTHORITY);
        }
        final ManagedChannel channel = builder.build();
        mChannels.add(channel);
        return channel;
    }

    private static RegisterResponse register(final ManagedChannel channel) {
        return ScreenStreamServiceGrpc.newBlockingStub(channel)
            .withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS)
            .register(RegisterRequest.newBuilder()
                .setProtocolVersion(ScreenStreamProtoMapper.PROTOCOL_VERSION)
                .setAnnounces(false)
                .build());
    }

    private static TlsPinLookup pin(final TlsMaterial material) {
        return id -> new TlsPin(material.getDeviceId(), material.getFingerprint(), 0L);
    }

    private static void assertFailsWith(final String reason, final ThrowingRunnable action)
        throws Exception {
        try {
            action.run();
            fail("expected a handshake failure containing " + reason);
        } catch (StatusRuntimeException e) {
            final String text = failureText(e);
            assertTrue("expected " + reason + " in: " + text, text.contains(reason));
        }
    }

    private static String failureText(final Throwable throwable) {
        final StringBuilder text = new StringBuilder();
        Throwable current = throwable;
        while (current != null) {
            text.append(current.getClass().getSimpleName()).append(": ")
                .append(current.getMessage()).append('\n');
            current = current.getCause();
        }
        return text.toString();
    }

    private static TlsMaterial material() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        final KeyPair keyPair = generator.generateKeyPair();
        return TlsMaterial.fromKeyPair(keyPair);
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class EchoRegisterService
        extends ScreenStreamServiceGrpc.ScreenStreamServiceImplBase {
        private final TlsCertificate mCertificate;

        private EchoRegisterService(final TlsMaterial material) {
            mCertificate = material.getCertificate();
        }

        @Override
        public void register(final RegisterRequest request,
                             final StreamObserver<RegisterResponse> responseObserver) {
            responseObserver.onNext(RegisterResponse.newBuilder()
                .setProtocolVersion(ScreenStreamProtoMapper.PROTOCOL_VERSION)
                .setAccepted(true)
                .setSelf(DeviceSummary.newBuilder()
                    .setDeviceId(mCertificate.getDeviceId())
                    .setFingerprint(mCertificate.getFingerprint())
                    .build())
                .build());
            responseObserver.onCompleted();
        }
    }
}
