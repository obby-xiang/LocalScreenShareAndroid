package com.obby.android.localscreenshare.transport;

import com.obby.android.localscreenshare.grpc.screenstream.DeviceSummary;
import com.obby.android.localscreenshare.grpc.screenstream.ClientMessage;
import com.obby.android.localscreenshare.grpc.screenstream.RegisterRequest;
import com.obby.android.localscreenshare.grpc.screenstream.RegisterResponse;
import com.obby.android.localscreenshare.grpc.screenstream.ScreenStreamServiceGrpc;
import com.obby.android.localscreenshare.grpc.screenstream.ServerMessage;
import com.obby.android.localscreenshare.client.StreamFailure;
import com.obby.android.localscreenshare.identity.SpkiId;
import com.obby.android.localscreenshare.identity.tls.TlsCertificate;
import com.obby.android.localscreenshare.identity.tls.TlsMaterial;
import com.obby.android.localscreenshare.identity.tls.TlsNames;
import com.obby.android.localscreenshare.core.util.TimeSource;
import com.obby.android.localscreenshare.server.RegisterCallHandler;
import com.obby.android.localscreenshare.server.TlsSessionInterceptor;
import com.obby.android.localscreenshare.trust.PeerIdentityMapper;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsServerCredentials;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.TlsChannelCredentials;
import io.grpc.okhttp.OkHttpServerBuilder;
import io.grpc.stub.StreamObserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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

    @Test
    public void mutualTlsWithAClientCertificateCompletesTheHandshake() throws Exception {
        final TlsMaterial server = material();
        final TlsMaterial client = material();
        final int port = startMutualServer(server);

        final RegisterResponse response = register(channel(port, server.getDeviceId(),
            pin(server), true, client.getKeyManager()));

        assertTrue(response.getAccepted());
    }

    @Test
    public void clientWithoutACertificateIsRejected() throws Exception {
        final TlsMaterial server = material();
        final int port = startMutualServer(server);

        try {
            register(channel(port, server.getDeviceId(), pin(server), true));
            fail("the server requires a client certificate");
        } catch (StatusRuntimeException e) {
            // The server aborts the handshake and the client sees the transport reset rather than
            // the alert text; UNAVAILABLE (not the call deadline) is the observable contract.
            assertEquals(io.grpc.Status.Code.UNAVAILABLE, e.getStatus().getCode());
        }
    }

    @Test
    public void claimedIdOfAnotherKeyIsRejectedByTheServer() throws Exception {
        final TlsMaterial server = material();
        final TlsMaterial first = material();
        final TlsMaterial second = material();
        final int port = startBoundMutualServer(server);

        final RegisterResponse firstResponse = register(channel(port, server.getDeviceId(),
            pin(server), true, first.getKeyManager()), first.getDeviceId(), first.getFingerprint());
        final RegisterResponse secondResponse = register(channel(port, server.getDeviceId(),
            pin(server), true, second.getKeyManager()), first.getDeviceId(),
            first.getFingerprint());

        assertTrue(firstResponse.getAccepted());
        assertTrue(!secondResponse.getAccepted());
        assertEquals(RegisterCallHandler.REASON_IDENTITY_MISMATCH,
            secondResponse.getRejectReason());
    }

    @Test
    public void matchingIdsOverMutualTlsAreAccepted() throws Exception {
        final TlsMaterial server = material();
        final TlsMaterial client = material();
        final int port = startBoundMutualServer(server);

        final RegisterResponse response = register(channel(port, server.getDeviceId(),
            pin(server), true, client.getKeyManager()), client.getDeviceId(),
            client.getFingerprint());

        assertTrue(response.getAccepted());
    }

    @Test
    public void aChangedPinOnTheStreamIsAnIdentityRejection() throws Exception {
        final TlsMaterial server = material();
        final TlsMaterial other = material();
        final int port = startServer(server);

        final Throwable error = streamError(channel(port, server.getDeviceId(),
            id -> new TlsPin(server.getDeviceId(), other.getFingerprint(), 0L), true));

        final StreamFailure failure = StreamFailure.from(error);
        assertTrue(failure.toLogText(), failure.isIdentityRejected());
        assertEquals(PinningTrustManager.REASON_IDENTITY_CHANGED, failure.getReason());
    }

    @Test
    public void aServerRejectionKeepsTheReasonItSent() throws Exception {
        final TlsMaterial server = material();
        final int port = startRejectingStreamServer(server, "session taken over by a newer connection");

        final Throwable error = streamError(channel(port, server.getDeviceId(), pin(server), true));

        final StreamFailure failure = StreamFailure.from(error);
        assertFalse(failure.toLogText(), failure.isIdentityRejected());
        assertEquals("session taken over by a newer connection", failure.getReason());
        assertEquals(io.grpc.Status.Code.ABORTED, failure.getStatusCode());
    }

    private static RegisterResponse register(final ManagedChannel channel,
                                             final String deviceId,
                                             final String fingerprint) {
        return ScreenStreamServiceGrpc.newBlockingStub(channel)
            .withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS)
            .register(RegisterRequest.newBuilder()
                .setProtocolVersion(ScreenStreamProtoMapper.PROTOCOL_VERSION)
                .setAnnounces(false)
                .setSelf(DeviceSummary.newBuilder()
                    .setDeviceId(deviceId)
                    .setFingerprint(fingerprint)
                    .build())
                .build());
    }

    private int startServer(final TlsMaterial material) throws IOException {
        return startServer(material, false, new EchoRegisterService(material));
    }

    private int startMutualServer(final TlsMaterial material) throws IOException {
        return startServer(material, true, new EchoRegisterService(material));
    }

    private int startBoundMutualServer(final TlsMaterial material) throws IOException {
        return startServer(material, true, new BoundRegisterService(material));
    }

    private int startRejectingStreamServer(final TlsMaterial material, final String reason)
        throws IOException {
        return startServer(material, false, new RejectingStreamService(reason));
    }

    /**
     * Opens the media stream and returns the error that ends it.
     *
     * <p>The stream is the call the viewer uses, so this is the closest a JVM test gets to the
     * client path: the handshake and the server's rejection arrive exactly as they do on a device,
     * and only the anchors are fed into {@link StreamFailure}.</p>
     */
    private static Throwable streamError(final ManagedChannel channel) throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>();
        ScreenStreamServiceGrpc.newStub(channel)
            .withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS)
            .stream(new StreamObserver<ServerMessage>() {
                @Override
                public void onNext(final ServerMessage value) {
                }

                @Override
                public void onError(final Throwable t) {
                    error.set(t);
                    done.countDown();
                }

                @Override
                public void onCompleted() {
                    done.countDown();
                }
            });

        assertTrue("the stream must end", done.await(DEADLINE_SECONDS, TimeUnit.SECONDS));
        assertNotNull("the stream must fail, not complete", error.get());
        return error.get();
    }

    private int startServer(final TlsMaterial material, final boolean mutual,
                            final ScreenStreamServiceGrpc.ScreenStreamServiceImplBase service)
        throws IOException {
        final TlsServerCredentials.Builder credentials = TlsServerCredentials.newBuilder()
            .keyManager(material.getKeyManager());
        if (mutual) {
            credentials
                .trustManager(new PinningTrustManager(null, id -> null, true,
                    PinningTrustManager.Role.SERVER))
                .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE);
        }
        mServer = OkHttpServerBuilder
            .forPort(0, credentials.build())
            .intercept(new TlsSessionInterceptor())
            .addService(service)
            .build()
            .start();
        return mServer.getPort();
    }

    private ManagedChannel channel(final int port, final String expectedDeviceId,
                                   final TlsPinLookup pins, final boolean overrideAuthority) {
        return channel(port, expectedDeviceId, pins, overrideAuthority, null);
    }

    private ManagedChannel channel(final int port, final String expectedDeviceId,
                                   final TlsPinLookup pins, final boolean overrideAuthority,
                                   final com.obby.android.localscreenshare.identity.tls
                                       .IdentityKeyAdapter keyManager) {
        final TlsChannelCredentials.Builder credentials = TlsChannelCredentials.newBuilder()
            .trustManager(new PinningTrustManager(expectedDeviceId, pins, true,
                PinningTrustManager.Role.CLIENT));
        if (keyManager != null) {
            credentials.keyManager(keyManager);
        }
        final OkHttpChannelBuilder builder =
            OkHttpChannelBuilder.forAddress(HOST, port, credentials.build());
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

    private static final class BoundRegisterService
        extends ScreenStreamServiceGrpc.ScreenStreamServiceImplBase {
        private final RegisterCallHandler mHandler;

        private BoundRegisterService(final TlsMaterial self) {
            mHandler = new RegisterCallHandler(
                () -> DeviceSummary.newBuilder()
                    .setDeviceId(self.getDeviceId())
                    .setFingerprint(self.getFingerprint())
                    .build(),
                null, Runnable::run, TimeSource.MONOTONIC);
        }

        @Override
        public void register(final RegisterRequest request,
                             final StreamObserver<RegisterResponse> responseObserver) {
            final com.obby.android.localscreenshare.identity.tls.TlsCertificate peer =
                PeerIdentityMapper.fromSession(TlsSessionInterceptor.currentSession());
            mHandler.handle(request, HOST, peer, response -> {
                responseObserver.onNext(response);
                responseObserver.onCompleted();
            });
        }
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

    /**
     * Ends every stream with the given reason, the way the server reports a takeover or a refused
     * join.
     */
    private static final class RejectingStreamService
        extends ScreenStreamServiceGrpc.ScreenStreamServiceImplBase {
        private final String mReason;

        private RejectingStreamService(final String reason) {
            mReason = reason;
        }

        @Override
        public StreamObserver<ClientMessage> stream(final StreamObserver<ServerMessage> responseObserver) {
            responseObserver.onError(io.grpc.Status.ABORTED
                .withDescription(mReason)
                .asRuntimeException());
            return new StreamObserver<ClientMessage>() {
                @Override
                public void onNext(final ClientMessage value) {
                }

                @Override
                public void onError(final Throwable t) {
                }

                @Override
                public void onCompleted() {
                }
            };
        }
    }
}
