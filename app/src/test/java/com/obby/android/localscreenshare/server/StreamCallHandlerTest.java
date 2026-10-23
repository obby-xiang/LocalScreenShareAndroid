package com.obby.android.localscreenshare.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.codec.EncoderConfig;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.model.ClientState;
import com.obby.android.localscreenshare.core.model.CodecConfigPart;
import com.obby.android.localscreenshare.core.model.ColorInfo;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.model.StreamParameters;
import com.obby.android.localscreenshare.core.stream.ClientSendSession;
import com.obby.android.localscreenshare.core.stream.StreamHub;
import com.obby.android.localscreenshare.core.stream.StreamRuntime;
import com.obby.android.localscreenshare.core.support.FakeCodec;
import com.obby.android.localscreenshare.core.support.FakeDeferredCodec;
import com.obby.android.localscreenshare.core.support.FakeTransport;
import com.obby.android.localscreenshare.core.support.MutableClock;
import com.obby.android.localscreenshare.grpc.screenstream.Capabilities;
import com.obby.android.localscreenshare.grpc.screenstream.ClientMessage;
import com.obby.android.localscreenshare.grpc.screenstream.CodecCapability;
import com.obby.android.localscreenshare.grpc.screenstream.Hello;
import com.obby.android.localscreenshare.grpc.screenstream.Ping;
import com.obby.android.localscreenshare.grpc.screenstream.RequestKeyframe;
import com.obby.android.localscreenshare.identity.tls.TlsCertificate;
import com.obby.android.localscreenshare.identity.tls.TlsMaterial;

import org.junit.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Collections;

/**
 * Unit tests for {@link StreamCallHandler}.
 */
public class StreamCallHandlerTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session-1", "stream-1");

    @Test
    public void helloThenDrainSendsDescriptorBeforeAccessUnit() throws Exception {
        Fixture fixture = fixture(FakeCodec.DEFAULT_CODEC_ID);
        FakeTransport transport = new FakeTransport();
        StreamCallHandler handler = new StreamCallHandler(fixture.hub, fixture.runtime, transport);

        handler.onClientMessage(hello(FakeCodec.DEFAULT_CODEC_ID));
        assertTrue(handler.drainOnce());

        fixture.codec.emitJoin(new byte[]{0x01});
        assertTrue(handler.drainOnce());

        assertEquals(2, transport.getEvents().size());
        assertTrue(transport.getEvents().get(0).isDescriptor());
        assertTrue(transport.getEvents().get(1).isAccessUnit());
        assertEquals(ClientState.STREAMING, fixture.hub.getSession(
                new com.obby.android.localscreenshare.core.model.ClientSessionKey(
                        STREAM_KEY, "client-1")).getState());
    }

    @Test
    public void keyframeRequestForwardsToRuntime() {
        Fixture fixture = fixture(FakeCodec.DEFAULT_CODEC_ID);
        fixture.codec.setAutoEmitJoinOnRequest(false);
        StreamCallHandler handler = new StreamCallHandler(fixture.hub, fixture.runtime,
                new FakeTransport());

        handler.onClientMessage(ClientMessage.newBuilder()
                .setRequestKeyframe(RequestKeyframe.getDefaultInstance()).build());

        assertEquals(1, fixture.codec.getRequestCount());
    }

    @Test
    public void closeDetachesSessionAndClosesTransport() {
        Fixture fixture = fixture(FakeCodec.DEFAULT_CODEC_ID);
        FakeTransport transport = new FakeTransport();
        StreamCallHandler handler = new StreamCallHandler(fixture.hub, fixture.runtime, transport);

        handler.onClientMessage(hello(FakeCodec.DEFAULT_CODEC_ID));
        handler.close();
        handler.close();

        assertTrue(handler.isClosed());
        assertEquals(0, fixture.hub.getSessionCount());
        assertEquals(1, transport.getCloseCount());
    }

    @Test
    public void emptyCapabilitiesDefaultsToH264Baseline() throws Exception {
        Fixture fixture = fixture("h264");
        FakeTransport transport = new FakeTransport();
        StreamCallHandler handler = new StreamCallHandler(fixture.hub, fixture.runtime, transport);

        handler.onClientMessage(ClientMessage.newBuilder()
                .setHello(Hello.newBuilder().setClientId("legacy").build())
                .build());
        assertTrue(handler.drainOnce());
        assertTrue(transport.getEvents().get(0).isDescriptor());
    }

    @Test
    public void joinRetriesUntilDescriptorPublished() throws Exception {
        FakeDeferredCodec deferred = new FakeDeferredCodec();
        CodecRegistry registry = new CodecRegistry();
        registry.register(deferred.registration());
        StreamRuntime runtime = new StreamRuntime(registry, STREAM_KEY,
                StreamDescriptor.builder(STREAM_KEY, 1, FakeDeferredCodec.DEFAULT_CODEC_ID,
                                FakeDeferredCodec.DEFAULT_MIME, BitstreamFormat.ANNEX_B)
                        .codedSize(64, 48)
                        .build(),
                StreamParameters.DEFAULT, new MutableClock(), 100L);
        runtime.start();
        StreamHub hub = new StreamHub(registry);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        FakeTransport transport = new FakeTransport();
        StreamCallHandler handler = new StreamCallHandler(hub, runtime, transport);
        handler.onClientMessage(hello(FakeDeferredCodec.DEFAULT_CODEC_ID));

        // The deferred runtime has not published its descriptor yet.
        assertFalse(handler.drainOnce());
        assertEquals(0, hub.getSessionCount());

        deferred.getLastEncoder().emitConfig(new EncoderConfig(64, 48, ColorInfo.UNSPECIFIED,
                Collections.singletonList(new CodecConfigPart("sps", new byte[]{0x01}))));

        assertTrue(handler.drainOnce());
        assertEquals(1, hub.getSessionCount());
        assertTrue(transport.getEvents().get(0).isDescriptor());
    }

    @Test
    public void duplicateClientId_takesOverTheStaleSession() throws Exception {
        Fixture fixture = fixture(FakeCodec.DEFAULT_CODEC_ID);
        FakeTransport firstTransport = new FakeTransport();
        StreamCallHandler first = new StreamCallHandler(fixture.hub, fixture.runtime, firstTransport);
        first.onClientMessage(hello(FakeCodec.DEFAULT_CODEC_ID));
        assertTrue(first.drainOnce());

        FakeTransport secondTransport = new FakeTransport();
        StreamCallHandler second = new StreamCallHandler(fixture.hub, fixture.runtime, secondTransport);
        second.onClientMessage(hello(FakeCodec.DEFAULT_CODEC_ID));

        // A second connection with the same client id takes over instead of being rejected.
        assertTrue(second.drainOnce());
        assertFalse(second.isClosed());
        assertNull(second.getJoinFailure());
        assertEquals(1, fixture.hub.getSessionCount());
        // The replaced call is terminated with an explicit reason instead of being left open.
        assertEquals(1, firstTransport.getAbortCount());
        assertEquals(0, firstTransport.getCloseCount());
        assertEquals(StreamHub.SESSION_TAKEOVER_REASON, firstTransport.getAbortReason());
    }

    @Test
    public void staleHandlerStopsAfterTakeoverWithoutDetachingTheReplacement() throws Exception {
        Fixture fixture = fixture(FakeCodec.DEFAULT_CODEC_ID);
        ClientSessionKey sessionKey = new ClientSessionKey(STREAM_KEY, "client-1");

        RecordingStreamCallListener firstListener = new RecordingStreamCallListener();
        FakeTransport firstTransport = new FakeTransport();
        StreamCallHandler first =
                new StreamCallHandler(fixture.hub, fixture.runtime, firstTransport, firstListener);
        first.onClientMessage(hello(FakeCodec.DEFAULT_CODEC_ID));
        assertTrue(first.drainOnce());

        FakeTransport secondTransport = new FakeTransport();
        StreamCallHandler second =
                new StreamCallHandler(fixture.hub, fixture.runtime, secondTransport);
        second.onClientMessage(hello(FakeCodec.DEFAULT_CODEC_ID));
        assertTrue(second.drainOnce());
        ClientSendSession replacement = fixture.hub.getSession(sessionKey);

        // The stale call notices that its key now maps to another session and stops draining.
        assertFalse(first.drainOnce());
        assertTrue(first.isClosed());
        assertEquals(1, firstListener.takeoverCount);
        assertEquals(sessionKey, firstListener.takeoverKey);
        assertEquals("FakeTransport#"
                        + Integer.toHexString(System.identityHashCode(firstTransport)),
                firstListener.takeoverTransportId);

        // The replacement is untouched and keeps streaming.
        assertSame(replacement, fixture.hub.getSession(sessionKey));
        assertFalse(replacement.isDetached());
        assertFalse(second.isClosed());
        assertEquals(0, secondTransport.getAbortCount());
        assertEquals(0, secondTransport.getCloseCount());
        fixture.codec.emitJoin(new byte[]{0x02});
        assertTrue(second.drainOnce());
        assertEquals(2, secondTransport.getEvents().size());
        assertTrue(secondTransport.getEvents().get(1).isAccessUnit());
    }

    @Test
    public void joinRejectionIsReportedToTheListener() {
        Fixture fixture = fixture(FakeCodec.DEFAULT_CODEC_ID);
        RecordingStreamCallListener listener = new RecordingStreamCallListener();
        FakeTransport transport = new FakeTransport();
        StreamCallHandler handler =
                new StreamCallHandler(fixture.hub, fixture.runtime, transport, listener);

        handler.onClientMessage(hello("unsupported-codec"));

        assertTrue(handler.isClosed());
        assertNotNull(handler.getJoinFailure());
        assertEquals(1, listener.rejectionCount);
        assertEquals(new ClientSessionKey(STREAM_KEY, "client-1"), listener.rejectionKey);
        assertEquals("FakeTransport#"
                        + Integer.toHexString(System.identityHashCode(transport)),
                listener.rejectionTransportId);
        assertEquals(handler.getJoinFailure(), listener.rejectionReason);
        assertEquals(0, fixture.hub.getSessionCount());
        assertEquals(1, transport.getCloseCount());
        assertEquals(0, transport.getAbortCount());
    }

    @Test
    public void ping_isEchoedWithoutTouchingTheSessionState() throws Exception {
        Fixture fixture = fixture(FakeCodec.DEFAULT_CODEC_ID);
        FakeTransport transport = new FakeTransport();
        RecordingPongTarget pongTarget = new RecordingPongTarget();
        StreamCallHandler handler = new StreamCallHandler(fixture.hub, fixture.runtime, transport,
                StreamCallListener.NO_OP, null, pongTarget);

        handler.onClientMessage(hello(FakeCodec.DEFAULT_CODEC_ID));
        ClientSessionKey key = new ClientSessionKey(STREAM_KEY, "client-1");
        ClientState stateAfterHello = fixture.hub.getSession(key).getState();
        int eventsAfterHello = transport.getEvents().size();

        handler.onClientMessage(ClientMessage.newBuilder()
                .setPing(Ping.newBuilder().setSeq(5).setClientUptimeMs(1_234L).build())
                .build());

        assertEquals(1, pongTarget.count);
        assertEquals(5, pongTarget.lastSeq);
        assertEquals(1_234L, pongTarget.lastClientUptimeMs);
        assertEquals("a probe must not queue media", eventsAfterHello,
                transport.getEvents().size());
        assertEquals(stateAfterHello, fixture.hub.getSession(key).getState());
        assertFalse(handler.isClosed());
    }

    @Test
    public void ping_isIgnoredAfterClose() {
        Fixture fixture = fixture(FakeCodec.DEFAULT_CODEC_ID);
        RecordingPongTarget pongTarget = new RecordingPongTarget();
        StreamCallHandler handler = new StreamCallHandler(fixture.hub, fixture.runtime,
                new FakeTransport(), StreamCallListener.NO_OP, null, pongTarget);
        handler.close();

        handler.onClientMessage(ClientMessage.newBuilder()
                .setPing(Ping.newBuilder().setSeq(1).build())
                .build());

        assertEquals(0, pongTarget.count);
    }

    private static Fixture fixture(String codecId) {
        FakeCodec codec = new FakeCodec(codecId, 0, false, false);
        CodecRegistry registry = new CodecRegistry();
        registry.register(codec.registration());
        StreamRuntime runtime = new StreamRuntime(registry, STREAM_KEY,
                StreamDescriptor.builder(STREAM_KEY, 1, codecId, FakeCodec.DEFAULT_MIME,
                                BitstreamFormat.ANNEX_B)
                        .codedSize(64, 48)
                        .build(),
                StreamParameters.DEFAULT, new MutableClock(), 100L);
        runtime.start();
        StreamHub hub = new StreamHub(registry);
        hub.register(runtime);
        hub.activate(STREAM_KEY);
        return new Fixture(codec, runtime, hub);
    }

    @Test
    public void helloIdentityIsCapturedForSessionBinding() {
        Fixture fixture = fixture(FakeCodec.DEFAULT_CODEC_ID);
        StreamCallHandler handler = new StreamCallHandler(fixture.hub, fixture.runtime,
                new FakeTransport());

        handler.onClientMessage(ClientMessage.newBuilder()
                .setHello(Hello.newBuilder()
                        .setClientId("client-1")
                        .setDeviceId("DEVICE-ID")
                        .setFingerprint("DEVICE-FP")
                        .setCapabilities(capabilities(FakeCodec.DEFAULT_CODEC_ID))
                        .build())
                .build());

        assertEquals("DEVICE-ID", handler.getDeviceId());
        assertEquals("DEVICE-FP", handler.getFingerprint());
    }

    @Test
    public void certificateBindsTheHelloIdentity() throws Exception {
        Fixture fixture = fixture(FakeCodec.DEFAULT_CODEC_ID);
        TlsCertificate certificate = certificate();
        StreamCallHandler handler = new StreamCallHandler(fixture.hub, fixture.runtime,
                new FakeTransport(), StreamCallListener.NO_OP, null, PongTarget.NO_OP,
                certificate);

        handler.onClientMessage(ClientMessage.newBuilder()
                .setHello(Hello.newBuilder()
                        .setClientId("client-1")
                        .setDeviceId(certificate.getDeviceId())
                        .setFingerprint(certificate.getFingerprint())
                        .setCapabilities(capabilities(FakeCodec.DEFAULT_CODEC_ID))
                        .build())
                .build());

        assertEquals(certificate.getDeviceId(), handler.getDeviceId());
        assertEquals(certificate.getFingerprint(), handler.getFingerprint());
        assertFalse(handler.isClosed());
    }

    @Test
    public void helloWithoutIdentityJoinsUnderTheCertificateIdentity() throws Exception {
        Fixture fixture = fixture(FakeCodec.DEFAULT_CODEC_ID);
        TlsCertificate certificate = certificate();
        StreamCallHandler handler = new StreamCallHandler(fixture.hub, fixture.runtime,
                new FakeTransport(), StreamCallListener.NO_OP, null, PongTarget.NO_OP,
                certificate);

        handler.onClientMessage(hello(FakeCodec.DEFAULT_CODEC_ID));

        assertEquals(certificate.getDeviceId(), handler.getDeviceId());
        assertEquals(certificate.getFingerprint(), handler.getFingerprint());
    }

    @Test
    public void helloWithAnotherDeviceIdIsRejected() throws Exception {
        Fixture fixture = fixture(FakeCodec.DEFAULT_CODEC_ID);
        TlsCertificate certificate = certificate();
        StreamCallHandler handler = new StreamCallHandler(fixture.hub, fixture.runtime,
                new FakeTransport(), StreamCallListener.NO_OP, null, PongTarget.NO_OP,
                certificate);

        handler.onClientMessage(ClientMessage.newBuilder()
                .setHello(Hello.newBuilder()
                        .setClientId("client-1")
                        .setDeviceId(certificate().getDeviceId())
                        .setCapabilities(capabilities(FakeCodec.DEFAULT_CODEC_ID))
                        .build())
                .build());

        assertTrue(handler.isClosed());
        assertEquals("identity_mismatch", handler.getJoinFailure());
        assertNull(handler.getAttachedKey());
        assertFalse(handler.drainOnce());
    }

    @Test
    public void helloWithAnotherFingerprintIsRejected() throws Exception {
        Fixture fixture = fixture(FakeCodec.DEFAULT_CODEC_ID);
        TlsCertificate certificate = certificate();
        StreamCallHandler handler = new StreamCallHandler(fixture.hub, fixture.runtime,
                new FakeTransport(), StreamCallListener.NO_OP, null, PongTarget.NO_OP,
                certificate);

        handler.onClientMessage(ClientMessage.newBuilder()
                .setHello(Hello.newBuilder()
                        .setClientId("client-1")
                        .setDeviceId(certificate.getDeviceId())
                        .setFingerprint(certificate().getFingerprint())
                        .setCapabilities(capabilities(FakeCodec.DEFAULT_CODEC_ID))
                        .build())
                .build());

        assertTrue(handler.isClosed());
        assertEquals("identity_mismatch", handler.getJoinFailure());
    }

    @Test
    public void helloWithoutIdentityLeavesTheBindingEmpty() {
        Fixture fixture = fixture(FakeCodec.DEFAULT_CODEC_ID);
        StreamCallHandler handler = new StreamCallHandler(fixture.hub, fixture.runtime,
                new FakeTransport());

        handler.onClientMessage(hello(FakeCodec.DEFAULT_CODEC_ID));

        assertNull(handler.getDeviceId());
        assertNull(handler.getFingerprint());
    }

    @Test
    public void onClientMessage_feedbackBeforeAttachIsIgnored() {
        Fixture fixture = fixture(FakeCodec.DEFAULT_CODEC_ID);
        RecordingFeedbackSink sink = new RecordingFeedbackSink();
        StreamCallHandler handler = handlerWithFeedback(fixture, new FakeTransport(), sink);

        handler.onClientMessage(feedbackMessage());

        assertEquals(0, sink.offerCount);
        assertNull(handler.getAttachedKey());
    }

    @Test
    public void onClientMessage_feedbackAfterAttachReachesTheSink() {
        Fixture fixture = fixture(FakeCodec.DEFAULT_CODEC_ID);
        RecordingFeedbackSink sink = new RecordingFeedbackSink();
        StreamCallHandler handler = handlerWithFeedback(fixture, new FakeTransport(), sink);

        handler.onClientMessage(hello(FakeCodec.DEFAULT_CODEC_ID));
        assertNotNull(handler.getAttachedKey());
        handler.onClientMessage(feedbackMessage());

        assertEquals(1, sink.offerCount);
        assertNotNull(sink.lastTarget);
        assertEquals(6, sink.lastFeedback.getQueueDepthPeak());
        assertFalse(handler.isClosed());
        assertNotNull(handler.getAttachedKey());
    }

    @Test
    public void onClientMessage_feedbackAfterCloseIsDropped() {
        Fixture fixture = fixture(FakeCodec.DEFAULT_CODEC_ID);
        RecordingFeedbackSink sink = new RecordingFeedbackSink();
        StreamCallHandler handler = handlerWithFeedback(fixture, new FakeTransport(), sink);

        handler.onClientMessage(hello(FakeCodec.DEFAULT_CODEC_ID));
        handler.close();
        handler.onClientMessage(feedbackMessage());

        assertEquals(0, sink.offerCount);
    }

    private static StreamCallHandler handlerWithFeedback(Fixture fixture, FakeTransport transport,
                                                         FeedbackSink sink) {
        return new StreamCallHandler(fixture.hub, fixture.runtime, transport,
                StreamCallListener.NO_OP, null, PongTarget.NO_OP, null, null, null, null, null,
                null, null, sink);
    }

    private static ClientMessage feedbackMessage() {
        return ClientMessage.newBuilder()
                .setFeedback(com.obby.android.localscreenshare.grpc.screenstream.StreamFeedback
                        .newBuilder()
                        .setQueueDepthPeak(6)
                        .setCatchUpEvents(1)
                        .setQueueWaitPeakMs(250)
                        .setClientUptimeMs(10_000L)
                        .build())
                .build();
    }

    private static TlsCertificate certificate() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keyPair = generator.generateKeyPair();
        return TlsMaterial.fromKeyPair(keyPair).getCertificate();
    }

    private static Capabilities capabilities(String codecId) {
        return Capabilities.newBuilder()
                .addCodecs(CodecCapability.newBuilder().setCodecId(codecId).build())
                .build();
    }

    private static ClientMessage hello(String codecId) {
        return ClientMessage.newBuilder()
                .setHello(Hello.newBuilder()
                        .setClientId("client-1")
                        .setCapabilities(capabilities(codecId))
                        .build())
                .build();
    }

    private static final class Fixture {
        final FakeCodec codec;
        final StreamRuntime runtime;
        final StreamHub hub;

        Fixture(FakeCodec codec, StreamRuntime runtime, StreamHub hub) {
            this.codec = codec;
            this.runtime = runtime;
            this.hub = hub;
        }
    }

    /**
     * Records the lifecycle events reported by a handler.
     */
    private static final class RecordingStreamCallListener implements StreamCallListener {
        int takeoverCount;
        ClientSessionKey takeoverKey;
        String takeoverTransportId;
        int rejectionCount;
        ClientSessionKey rejectionKey;
        String rejectionTransportId;
        String rejectionReason;

        @Override
        public void onSessionTakenOver(ClientSessionKey key, String transportId) {
            takeoverCount++;
            takeoverKey = key;
            takeoverTransportId = transportId;
        }

        @Override
        public void onJoinRejected(ClientSessionKey key, String transportId, String reason) {
            rejectionCount++;
            rejectionKey = key;
            rejectionTransportId = transportId;
            rejectionReason = reason;
        }
    }

    /**
     * Records the feedback reports a handler offers.
     */
    private static final class RecordingFeedbackSink implements FeedbackSink {
        int offerCount;
        com.obby.android.localscreenshare.core.model.StreamFeedback lastFeedback;
        BitrateTarget lastTarget;

        @Override
        public void offer(com.obby.android.localscreenshare.core.model.StreamFeedback feedback,
                          BitrateTarget target) {
            offerCount++;
            lastFeedback = feedback;
            lastTarget = target;
        }

        @Override
        public void close() {
        }
    }

    /**
     * Records the echoes a handler sends.
     */
    private static final class RecordingPongTarget implements PongTarget {
        int count;
        int lastSeq;
        long lastClientUptimeMs;

        @Override
        public void sendPong(int seq, long clientUptimeMs) {
            count++;
            lastSeq = seq;
            lastClientUptimeMs = clientUptimeMs;
        }
    }
}
