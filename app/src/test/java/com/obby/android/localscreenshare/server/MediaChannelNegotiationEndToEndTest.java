package com.obby.android.localscreenshare.server;

import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.model.ClientState;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.model.StreamParameters;
import com.obby.android.localscreenshare.core.net.MediaChannelListener;
import com.obby.android.localscreenshare.core.net.MediaChannelOffer;
import com.obby.android.localscreenshare.core.net.MediaTransportKind;
import com.obby.android.localscreenshare.core.net.MediaTransportPolicy;
import com.obby.android.localscreenshare.core.stream.StreamHub;
import com.obby.android.localscreenshare.core.stream.StreamRuntime;
import com.obby.android.localscreenshare.core.support.FakeCodec;
import com.obby.android.localscreenshare.core.support.FakeTransport;
import com.obby.android.localscreenshare.core.support.MutableClock;
import com.obby.android.localscreenshare.core.util.TimeSource;
import com.obby.android.localscreenshare.grpc.screenstream.Capabilities;
import com.obby.android.localscreenshare.grpc.screenstream.ClientMessage;
import com.obby.android.localscreenshare.grpc.screenstream.CodecCapability;
import com.obby.android.localscreenshare.grpc.screenstream.Hello;
import com.obby.android.localscreenshare.grpc.screenstream.MediaChannelEvent;
import com.obby.android.localscreenshare.grpc.screenstream.MediaChannelEventType;
import com.obby.android.localscreenshare.identity.tls.TlsMaterial;
import com.obby.android.localscreenshare.transport.MediaChannelServer;
import com.obby.android.localscreenshare.transport.MediaTlsFactory;
import com.obby.android.localscreenshare.transport.TcpMediaReceiver;
import com.obby.android.localscreenshare.trust.TlsPin;
import com.obby.android.localscreenshare.trust.TlsPinLookup;

import org.junit.After;
import org.junit.Test;

import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end negotiation of the dedicated stream channel over real loopback TLS.
 *
 * <p>The server side is the production handler, hub and listener; the client side is the
 * production receiver driven by a minimal test client that answers the offer. The assertions are
 * the ones the plan requires of this batch: the descriptor reaches the channel before its first
 * access unit, the sequence never repeats or goes backwards, killing the channel returns media to
 * the control-plane carrier without ending the call, and a server pinned to the carrier never
 * offers a channel at all.</p>
 */
public class MediaChannelNegotiationEndToEndTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session-1", "stream-1");

    private static final ClientSessionKey CLIENT = new ClientSessionKey(STREAM_KEY, "client-1");

    private static final long TIMEOUT_MS = 5_000L;

    private MediaChannelServer mServer;

    @After
    public void tearDown() {
        if (mServer != null) {
            mServer.stop();
            mServer = null;
        }
    }

    @Test
    public void readySwapsTheSessionOntoTheChannelAndMediaFlows() throws Exception {
        Fixture fixture = fixture(MediaTransportPolicy.Mode.TCP);
        fixture.handler.onClientMessage(hello("tcp"));
        assertEquals(1, fixture.offers.size());

        ClientChannel channel = new ClientChannel(fixture, fixture.offers.get(0));
        channel.start();

        assertTrue("session did not move onto the channel",
                awaitChannelKind(fixture, MediaTransportKind.TCP));

        fixture.codec.emitJoin(new byte[]{0x41});
        drainUntil(fixture, channel::hasAccessUnit);

        assertTrue(channel.hasDescriptor());
        assertEquals("descriptor", channel.order.get(0));
        assertTrue(channel.hasAccessUnit());
        assertEquals(ClientState.STREAMING, fixture.hub.getSession(CLIENT).getState());
        assertEquals("tcp", fixture.hub.getSession(CLIENT).snapshot().getChannel());

        fixture.codec.emitJoin(new byte[]{0x42});
        drainUntil(fixture, () -> channel.accessUnits.size() >= 2L);
        List<Long> sequences = channel.sequences();
        assertTrue(sequences.get(0) < sequences.get(1));
        channel.close();
    }

    @Test
    public void killingTheChannelFallsBackToTheCarrierWithoutEndingTheCall() throws Exception {
        Fixture fixture = fixture(MediaTransportPolicy.Mode.TCP);
        fixture.handler.onClientMessage(hello("tcp"));
        ClientChannel channel = new ClientChannel(fixture, fixture.offers.get(0));
        channel.start();
        assertTrue(awaitChannelKind(fixture, MediaTransportKind.TCP));

        // The listener dies; the client reports the channel closed and the server falls back.
        mServer.stop();
        mServer = null;
        assertTrue("channel stayed active after the listener died",
                awaitChannelKind(fixture, MediaTransportKind.GRPC));

        assertFalse(fixture.handler.isClosed());
        assertFalse(fixture.hub.getSession(CLIENT).isDetached());

        // The fallback happens on the first send that hits the dead channel, so keep offering a
        // join point until the session reports the carrier and a unit actually lands on it.
        drainUntil(fixture, () -> {
            fixture.codec.emitJoin(new byte[]{0x51});
            return hasCarrierAccessUnit(fixture);
        });
        assertTrue(hasCarrierAccessUnit(fixture));
        assertEquals(ClientState.STREAMING, fixture.hub.getSession(CLIENT).getState());
        assertEquals("grpc", fixture.hub.getSession(CLIENT).snapshot().getChannel());
    }

    @Test
    public void pinnedToTheCarrierNoChannelIsOffered() throws Exception {
        Fixture fixture = fixture(MediaTransportPolicy.Mode.GRPC);

        fixture.handler.onClientMessage(hello("tcp", "udp"));

        assertTrue(fixture.offers.isEmpty());
        assertEquals(StreamCallHandler.MediaChannelPhase.GRPC,
                fixture.handler.getMediaChannelPhase());

        fixture.codec.setAutoEmitJoinOnRequest(false);
        fixture.codec.emitJoin(new byte[]{0x61});
        assertTrue(fixture.handler.drainOnce());
        assertTrue(fixture.handler.drainOnce());
        assertEquals(2, fixture.transport.getEvents().size());
        assertTrue(fixture.transport.getEvents().get(0).isDescriptor());
        assertTrue(fixture.transport.getEvents().get(1).isAccessUnit());
    }

    @Test
    public void channelOfferCarriesTheEndpointOfTheListener() throws Exception {
        Fixture fixture = fixture(MediaTransportPolicy.Mode.TCP);

        fixture.handler.onClientMessage(hello("tcp"));

        MediaChannelOffer offer = fixture.offers.get(0);
        assertEquals(fixture.mediaServer.getPort(), offer.getPort());
        assertTrue(offer.isSecure());
        assertEquals(32, offer.getToken().length);
        assertTrue(offer.getChannelIdHex().matches("[0-9a-f]{32}"));
    }

    private boolean awaitChannelKind(Fixture fixture, MediaTransportKind kind) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (fixture.handler.getMediaChannelPhase() == StreamCallHandler.MediaChannelPhase.ACTIVE
                    && fixture.handler.getMediaChannelKind() == kind) {
                return true;
            }
            if (fixture.handler.getMediaChannelPhase() == StreamCallHandler.MediaChannelPhase.GRPC
                    && kind == MediaTransportKind.GRPC) {
                return true;
            }
            fixture.handler.drainOnce();
            Thread.sleep(5L);
        }
        return false;
    }

    private void drainUntil(Fixture fixture, java.util.function.BooleanSupplier condition)
            throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline && !condition.getAsBoolean()) {
            fixture.handler.drainOnce();
            Thread.sleep(5L);
        }
    }

    private static boolean hasCarrierAccessUnit(Fixture fixture) {
        for (FakeTransport.Event event : fixture.transport.getEvents()) {
            if (event.isAccessUnit()) {
                return true;
            }
        }
        return false;
    }

    private Fixture fixture(MediaTransportPolicy.Mode mode) throws Exception {
        Fixture fixture = new Fixture(mode);
        mServer = fixture.mediaServer;
        return fixture;
    }

    private static ClientMessage hello(String... declaredTransports) {
        Hello.Builder hello = Hello.newBuilder()
                .setClientId("client-1")
                .setCapabilities(Capabilities.newBuilder()
                        .addCodecs(CodecCapability.newBuilder()
                                .setCodecId(FakeCodec.DEFAULT_CODEC_ID)
                                .build())
                        .build());
        hello.addAllMediaTransports(Arrays.asList(declaredTransports));
        return ClientMessage.newBuilder().setHello(hello.build()).build();
    }

    private static TlsMaterial material() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return TlsMaterial.fromKeyPair(generator.generateKeyPair());
    }

    /**
     * Server side of one negotiated call.
     */
    private static final class Fixture {

        private final FakeCodec codec;

        private final StreamRuntime runtime;

        private final StreamHub hub;

        private final FakeTransport transport = new FakeTransport();

        private final List<MediaChannelOffer> offers = new ArrayList<>();

        private final MediaChannelRegistry registry = new MediaChannelRegistry();

        private final MediaChannelServer mediaServer;

        private final TlsMaterial clientMaterial;

        private final javax.net.ssl.SSLContext clientContext;

        private final StreamCallHandler handler;

        private Fixture(MediaTransportPolicy.Mode mode) throws Exception {
            codec = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
            codec.setAutoEmitJoinOnRequest(false);
            CodecRegistry codecs = new CodecRegistry();
            codecs.register(codec.registration());
            runtime = new StreamRuntime(codecs, STREAM_KEY,
                    StreamDescriptor.builder(STREAM_KEY, 1, FakeCodec.DEFAULT_CODEC_ID,
                                    FakeCodec.DEFAULT_MIME, BitstreamFormat.ANNEX_B)
                            .codedSize(64, 48)
                            .build(),
                    StreamParameters.DEFAULT, new MutableClock(), 100L);
            runtime.start();
            hub = new StreamHub(codecs);
            hub.register(runtime);
            hub.activate(STREAM_KEY);

            TlsMaterial serverMaterial = material();
            clientMaterial = material();
            mediaServer = new MediaChannelServer(
                    MediaTlsFactory.serverContext(serverMaterial, id -> null), registry,
                    TimeSource.MONOTONIC);
            int port = mediaServer.start(0);
            TlsPinLookup pins = id -> new TlsPin(serverMaterial.getDeviceId(),
                    serverMaterial.getFingerprint(), 0L);
            clientContext = MediaTlsFactory.clientContext(clientMaterial,
                    serverMaterial.getDeviceId(), pins);

            MediaChannelNegotiator negotiator = new MediaChannelNegotiator(mode,
                    MediaTransportPolicy.allowsUdp(mode), offers::add, TimeSource.MONOTONIC,
                    new MediaChannelEndpoint("127.0.0.1", port, true));
            handler = new StreamCallHandler(hub, runtime, transport, StreamCallListener.NO_OP,
                    null, PongTarget.NO_OP, null, null, null, null, null, negotiator, registry);
        }

        private ClientMessage answer(MediaChannelEventType type, byte[] channelId, String reason) {
            return ClientMessage.newBuilder()
                    .setMediaChannelEvent(MediaChannelEvent.newBuilder()
                            .setChannelId(com.google.protobuf.ByteString.copyFrom(channelId))
                            .setType(type)
                            .setReason(reason)
                            .build())
                    .build();
        }
    }

    /**
     * Minimal client that answers one offer and records what the channel delivered.
     */
    private static final class ClientChannel implements MediaChannelListener {

        private final Fixture fixture;

        private final MediaChannelOffer offer;

        private final List<String> order = Collections.synchronizedList(new ArrayList<>());

        private final List<EncodedAccessUnit> accessUnits =
                Collections.synchronizedList(new ArrayList<>());

        private final CountDownLatch ready = new CountDownLatch(1);

        private final AtomicReference<String> failure = new AtomicReference<>();

        private final TcpMediaReceiver receiver;

        private volatile boolean closed;

        private ClientChannel(Fixture fixture, MediaChannelOffer offer) {
            this.fixture = fixture;
            this.offer = offer;
            this.receiver = new TcpMediaReceiver(STREAM_KEY, "127.0.0.1", offer, this,
                    new TcpMediaReceiver.StatusListener() {
                        @Override
                        public void onChannelReady() {
                            ready.countDown();
                            fixture.handler.onClientMessage(fixture.answer(
                                    MediaChannelEventType.READY, offer.getChannelId(), ""));
                        }

                        @Override
                        public void onChannelFailed(String reason) {
                            failure.set(reason);
                            if (!closed) {
                                fixture.handler.onClientMessage(fixture.answer(
                                        MediaChannelEventType.CLOSED, offer.getChannelId(),
                                        reason));
                            }
                        }
                    }, fixture.clientContext);
        }

        private void start() throws InterruptedException {
            Thread thread = new Thread(receiver::start, "negotiation-client");
            thread.setDaemon(true);
            thread.start();
            assertTrue("channel did not come up", ready.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        }

        private void close() {
            closed = true;
            receiver.close();
        }

        private boolean hasDescriptor() {
            return !order.isEmpty() && "descriptor".equals(order.get(0));
        }

        private boolean hasAccessUnit() {
            return !accessUnits.isEmpty();
        }

        private List<Long> sequences() {
            List<Long> result = new ArrayList<>();
            synchronized (accessUnits) {
                for (EncodedAccessUnit unit : accessUnits) {
                    result.add(unit.getSequence());
                }
            }
            return result;
        }

        @Override
        public void onDescriptor(StreamDescriptor descriptor, boolean secure) {
            order.add("descriptor");
        }

        @Override
        public void onAccessUnit(EncodedAccessUnit accessUnit, long arrivalNs) {
            order.add("unit");
            accessUnits.add(accessUnit);
        }

        @Override
        public void onChannelFailed(String reason) {
            order.add("failed:" + reason);
        }
    }
}
