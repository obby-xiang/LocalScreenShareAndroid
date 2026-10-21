package com.obby.android.localscreenshare.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import com.obby.android.localscreenshare.authorization.ApprovalOutcome;
import com.obby.android.localscreenshare.authorization.ApprovalRequest;
import com.obby.android.localscreenshare.authorization.AuthorizationGate;
import com.obby.android.localscreenshare.authorization.AuthorizationPolicy;
import com.obby.android.localscreenshare.authorization.store.AuthorizationStore;
import com.obby.android.localscreenshare.authorization.store.support.FakeAuthorizationStorage;
import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.model.StreamParameters;
import com.obby.android.localscreenshare.core.stream.StreamHub;
import com.obby.android.localscreenshare.core.stream.StreamRuntime;
import com.obby.android.localscreenshare.core.support.FakeCodec;
import com.obby.android.localscreenshare.core.support.FakeTransport;
import com.obby.android.localscreenshare.core.support.MutableClock;
import com.obby.android.localscreenshare.grpc.screenstream.Capabilities;
import com.obby.android.localscreenshare.grpc.screenstream.ClientMessage;
import com.obby.android.localscreenshare.grpc.screenstream.CodecCapability;
import com.obby.android.localscreenshare.grpc.screenstream.Hello;
import com.obby.android.localscreenshare.identity.tls.TlsCertificate;
import com.obby.android.localscreenshare.identity.tls.TlsMaterial;

import org.junit.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for the viewing-authorization gate inside {@link StreamCallHandler}.
 *
 * <p>They cover the whole local contract: a peer that is not authorized is parked without a
 * session, each of the four answers has its own ending, a revocation ends an attached call, a
 * one-time grant survives one, and a disabled feature keeps the old behaviour.</p>
 */
public class StreamCallHandlerAuthorizationTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session-1", "stream-1");

    @Test
    public void anUnauthorizedPeerIsParkedWithoutASession() throws Exception {
        final Fixture fixture = new Fixture();
        final FakeTransport transport = new FakeTransport();
        final Node node = newNode(fixture, transport);

        node.handler.onClientMessage(hello(node.certificate, "Laptop"));

        assertEquals(1, node.target.count);
        assertEquals(1, fixture.gate.getPendingCount());
        assertEquals(0, fixture.hub.getSessionCount());
        assertNull(node.handler.getAttachedKey());
        assertFalse(node.handler.drainOnce());
        assertTrue(transport.getEvents().isEmpty());
        assertEquals("Laptop", fixture.gate.pendingRequests().get(0).getDeviceName());
    }

    @Test
    public void allowOnceJoinsWithoutWritingTheDurableSet() throws Exception {
        final Fixture fixture = new Fixture();
        final Node node = newNode(fixture, new FakeTransport());
        node.handler.onClientMessage(hello(node.certificate, null));

        fixture.gate.resolve(requestId(fixture), ApprovalOutcome.ALLOW_ONCE);

        assertTrue(node.handler.drainOnce());
        assertNotNull(node.handler.getAttachedKey());
        assertTrue(fixture.store.entries().isEmpty());
    }

    @Test
    public void alwaysAllowJoinsAndWritesTheDurableSet() throws Exception {
        final Fixture fixture = new Fixture();
        final Node node = newNode(fixture, new FakeTransport());
        node.handler.onClientMessage(hello(node.certificate, null));

        fixture.gate.resolve(requestId(fixture), ApprovalOutcome.ALLOW_ALWAYS);

        assertTrue(node.handler.drainOnce());
        assertEquals(node.certificate.getFingerprint(),
            fixture.store.find(node.certificate.getDeviceId()).getFingerprint());
    }

    @Test
    public void denyClosesTheCallWithTheReason() throws Exception {
        final Fixture fixture = new Fixture();
        final Node node = newNode(fixture, new FakeTransport());
        node.handler.onClientMessage(hello(node.certificate, null));

        fixture.gate.resolve(requestId(fixture), ApprovalOutcome.DENY);

        assertFalse(node.handler.drainOnce());
        assertTrue(node.handler.isClosed());
        assertEquals(AuthorizationPolicy.REASON_DENIED, node.handler.getJoinFailure());
        assertEquals(AuthorizationPolicy.REASON_DENIED, node.listener.rejectionReason);
        assertEquals(0, fixture.hub.getSessionCount());
    }

    @Test
    public void theApprovalWindowEndsTheCall() throws Exception {
        final Fixture fixture = new Fixture();
        final Node node = newNode(fixture, new FakeTransport());
        node.handler.onClientMessage(hello(node.certificate, null));

        fixture.clock.advance(AuthorizationPolicy.APPROVAL_TIMEOUT_MS);
        assertFalse(node.handler.drainOnce());

        assertTrue(node.handler.isClosed());
        assertEquals(AuthorizationPolicy.REASON_TIMEOUT, node.handler.getJoinFailure());
        assertTrue(fixture.store.entries().isEmpty());
    }

    @Test
    public void closingTheCallWithdrawsThePrompt() throws Exception {
        final Fixture fixture = new Fixture();
        final Node node = newNode(fixture, new FakeTransport());
        node.handler.onClientMessage(hello(node.certificate, null));

        node.handler.close();

        assertEquals(0, fixture.gate.getPendingCount());
        assertEquals(1, fixture.gateListener.cancelledCount);
    }

    @Test
    public void revokingTheGrantEndsTheAttachedCall() throws Exception {
        final Fixture fixture = new Fixture();
        final Node node = newNode(fixture, new FakeTransport());
        fixture.store.authorize(node.certificate.getDeviceId(), node.certificate.getFingerprint(),
            "Laptop", 1L);

        node.handler.onClientMessage(hello(node.certificate, null));
        assertTrue(node.handler.drainOnce());
        assertEquals(1, fixture.hub.getSessionCount());

        fixture.store.revoke(node.certificate.getDeviceId());
        assertFalse(node.handler.drainOnce());

        assertTrue(node.handler.isClosed());
        assertEquals(AuthorizationPolicy.REASON_REVOKED, node.handler.getJoinFailure());
        assertEquals(0, fixture.hub.getSessionCount());
    }

    @Test
    public void aOneTimeGrantSurvivesARevocation() throws Exception {
        final Fixture fixture = new Fixture();
        final Node node = newNode(fixture, new FakeTransport());
        node.handler.onClientMessage(hello(node.certificate, null));
        fixture.gate.resolve(requestId(fixture), ApprovalOutcome.ALLOW_ONCE);
        assertTrue(node.handler.drainOnce());

        // There is nothing to revoke, and the grant was never durable: the session must stay up.
        fixture.store.revoke(node.certificate.getDeviceId());
        node.handler.drainOnce();

        assertFalse(node.handler.isClosed());
        assertEquals(1, fixture.hub.getSessionCount());
    }

    @Test
    public void aDisabledFeatureLetsThePeerWatchWithoutAPrompt() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.isEnabled = false;
        final Node node = newNode(fixture, new FakeTransport());

        node.handler.onClientMessage(hello(node.certificate, null));

        assertEquals(0, node.target.count);
        assertEquals(0, fixture.gate.getPendingCount());
        assertTrue(node.handler.drainOnce());
        assertNotNull(node.handler.getAttachedKey());
    }

    @Test
    public void aNoticeFailureClosesTheParkedCall() throws Exception {
        final Fixture fixture = new Fixture();
        final FakeTransport transport = new FakeTransport();
        final Node node = newNode(fixture, transport, false);

        node.handler.onClientMessage(hello(node.certificate, null));

        assertTrue(node.handler.isClosed());
        assertEquals(0, fixture.gate.getPendingCount());
    }

    private static long requestId(@NonNull final Fixture fixture) {
        final List<ApprovalRequest> pending = fixture.gate.pendingRequests();
        assertEquals(1, pending.size());
        return pending.get(0).getRequestId();
    }

    private static Node newNode(@NonNull final Fixture fixture,
                                @NonNull final FakeTransport transport) throws Exception {
        return newNode(fixture, transport, true);
    }

    private static Node newNode(@NonNull final Fixture fixture,
                                @NonNull final FakeTransport transport,
                                final boolean isNoticeWorking) throws Exception {
        final TlsCertificate certificate = certificate();
        final RecordingCallListener listener = new RecordingCallListener();
        final CountingApprovalTarget target = new CountingApprovalTarget(isNoticeWorking);
        final StreamCallHandler handler = new StreamCallHandler(fixture.hub, fixture.runtime,
            transport, listener, null, PongTarget.NO_OP, certificate, null, null,
            fixture.gate, target);
        return new Node(handler, certificate, listener, target);
    }

    private static TlsCertificate certificate() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        final KeyPair keyPair = generator.generateKeyPair();
        return TlsMaterial.fromKeyPair(keyPair).getCertificate();
    }

    private static ClientMessage hello(@NonNull final TlsCertificate certificate,
                                       final String deviceName) {
        final Hello.Builder hello = Hello.newBuilder()
            .setClientId("client-1")
            .setDeviceId(certificate.getDeviceId())
            .setFingerprint(certificate.getFingerprint())
            .setCapabilities(Capabilities.newBuilder()
                .addCodecs(CodecCapability.newBuilder().setCodecId(FakeCodec.DEFAULT_CODEC_ID).build())
                .build());
        if (deviceName != null) {
            hello.setDeviceName(deviceName);
        }
        return ClientMessage.newBuilder().setHello(hello.build()).build();
    }

    private static final class Node {
        final StreamCallHandler handler;
        final TlsCertificate certificate;
        final RecordingCallListener listener;
        final CountingApprovalTarget target;

        Node(@NonNull final StreamCallHandler handler, @NonNull final TlsCertificate certificate,
             @NonNull final RecordingCallListener listener,
             @NonNull final CountingApprovalTarget target) {
            this.handler = handler;
            this.certificate = certificate;
            this.listener = listener;
            this.target = target;
        }
    }

    private static final class Fixture {
        final MutableClock clock = new MutableClock(1_000L);
        final AuthorizationStore store = new AuthorizationStore(new FakeAuthorizationStorage());
        final RecordingGateListener gateListener = new RecordingGateListener();
        boolean isEnabled = true;
        final AuthorizationGate gate;
        final StreamHub hub;
        final StreamRuntime runtime;
        final FakeCodec codec;

        Fixture() {
            codec = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
            final CodecRegistry registry = new CodecRegistry();
            registry.register(codec.registration());
            runtime = new StreamRuntime(registry, STREAM_KEY,
                StreamDescriptor.builder(STREAM_KEY, 1, FakeCodec.DEFAULT_CODEC_ID,
                        FakeCodec.DEFAULT_MIME, BitstreamFormat.ANNEX_B)
                    .codedSize(64, 48)
                    .build(),
                StreamParameters.DEFAULT, new MutableClock(), 100L);
            runtime.start();
            hub = new StreamHub(registry);
            hub.register(runtime);
            hub.activate(STREAM_KEY);
            gate = new AuthorizationGate(store, () -> isEnabled, clock::nowMs, gateListener);
        }
    }

    private static final class CountingApprovalTarget implements ApprovalTarget {
        final boolean isWorking;
        int count;

        CountingApprovalTarget(final boolean isWorking) {
            this.isWorking = isWorking;
        }

        @Override
        public void sendApprovalPending() {
            count++;
            if (!isWorking) {
                throw new IllegalStateException("transport closed");
            }
        }
    }

    private static final class RecordingCallListener implements StreamCallListener {
        String rejectionReason;

        @Override
        public void onSessionTakenOver(final ClientSessionKey key, final String transportId) {
        }

        @Override
        public void onJoinRejected(final ClientSessionKey key, final String transportId,
                                   final String reason) {
            rejectionReason = reason;
        }
    }

    private static final class RecordingGateListener implements AuthorizationGate.Listener {
        int cancelledCount;
        final List<ApprovalOutcome> outcomes = new ArrayList<>();

        @Override
        public void onApprovalRequested(@NonNull final ApprovalRequest request) {
        }

        @Override
        public void onApprovalResolved(@NonNull final ApprovalRequest request,
                                       @NonNull final ApprovalOutcome outcome) {
            outcomes.add(outcome);
        }

        @Override
        public void onApprovalCancelled(@NonNull final ApprovalRequest request) {
            cancelledCount++;
        }
    }
}
