package com.obby.android.localscreenshare.server;

import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.model.StreamParameters;
import com.obby.android.localscreenshare.core.net.MediaChannelOffer;
import com.obby.android.localscreenshare.core.net.MediaTransportKind;
import com.obby.android.localscreenshare.core.net.MediaTransportPolicy;
import com.obby.android.localscreenshare.core.stream.StreamHub;
import com.obby.android.localscreenshare.core.stream.StreamRuntime;
import com.obby.android.localscreenshare.core.support.FakeCodec;
import com.obby.android.localscreenshare.core.support.FakeTransport;
import com.obby.android.localscreenshare.core.support.MutableClock;
import com.obby.android.localscreenshare.grpc.screenstream.Capabilities;
import com.obby.android.localscreenshare.grpc.screenstream.ClientMessage;
import com.obby.android.localscreenshare.grpc.screenstream.CodecCapability;
import com.obby.android.localscreenshare.grpc.screenstream.Hello;
import com.obby.android.localscreenshare.grpc.screenstream.MediaChannelEvent;
import com.obby.android.localscreenshare.grpc.screenstream.MediaChannelEventType;
import com.obby.android.localscreenshare.transport.TcpMediaConstants;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the media channel negotiation of {@link StreamCallHandler}.
 *
 * <p>This batch has no media channel, so every test ends on the control-plane carrier: what is
 * asserted is that the offer goes out, that a refusal, a timeout, an unsupported READY and a stale
 * answer all settle the call without stopping the media that is already flowing on the gRPC
 * transport.</p>
 */
public class StreamCallHandlerMediaChannelTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session-1", "stream-1");

    @Test
    public void offerIsSentAfterJoinWhenTheClientDeclaresAChannel() {
        Fixture fixture = fixture();
        List<MediaChannelOffer> offers = new ArrayList<>();
        MutableClock clock = new MutableClock();
        StreamCallHandler handler = handler(fixture, MediaTransportPolicy.Mode.TCP, true, clock,
                offers);

        handler.onClientMessage(hello("tcp"));

        assertEquals(1, offers.size());
        assertEquals(MediaTransportKind.TCP, offers.get(0).getTransport());
        assertEquals(StreamCallHandler.MediaChannelPhase.OFFERED, handler.getMediaChannelPhase());
        assertEquals(MediaTransportKind.TCP, handler.getMediaChannelKind());
    }

    @Test
    public void refusedOfferFallsBackToTheCarrierAndMediaKeepsFlowing() throws Exception {
        Fixture fixture = fixture();
        List<MediaChannelOffer> offers = new ArrayList<>();
        FakeTransport transport = new FakeTransport();
        StreamCallHandler handler = handler(fixture, transport, MediaTransportPolicy.Mode.TCP, true,
                new MutableClock(), offers);
        handler.onClientMessage(hello("tcp"));

        handler.onClientMessage(answer(offers.get(0).getChannelId(),
                MediaChannelEventType.FAILED, "not_implemented"));

        assertEquals(StreamCallHandler.MediaChannelPhase.GRPC, handler.getMediaChannelPhase());
        assertNull(handler.getMediaChannelKind());
        assertEquals("not_implemented", handler.getMediaChannelFailureReason());

        fixture.codec.emitJoin(new byte[]{0x31});
        assertTrue(handler.drainOnce());
        assertTrue(handler.drainOnce());

        List<FakeTransport.Event> events = transport.getEvents();
        assertTrue(events.size() >= 2);
        assertTrue(events.get(0).isDescriptor());
        assertTrue(events.get(1).isAccessUnit());
        assertFalse(handler.isClosed());
    }

    @Test
    public void clientWithoutADeclarationStaysOnTheCarrier() {
        Fixture fixture = fixture();
        List<MediaChannelOffer> offers = new ArrayList<>();
        StreamCallHandler handler = handler(fixture, MediaTransportPolicy.Mode.AUTO, true,
                new MutableClock(), offers);

        handler.onClientMessage(hello());

        assertTrue(offers.isEmpty());
        assertEquals(StreamCallHandler.MediaChannelPhase.GRPC, handler.getMediaChannelPhase());
    }

    @Test
    public void pinnedToTheCarrier_sendsNoOffer() {
        Fixture fixture = fixture();
        List<MediaChannelOffer> offers = new ArrayList<>();
        StreamCallHandler handler = handler(fixture, MediaTransportPolicy.Mode.GRPC, true,
                new MutableClock(), offers);

        handler.onClientMessage(hello("tcp", "udp"));

        assertTrue(offers.isEmpty());
        assertEquals(StreamCallHandler.MediaChannelPhase.GRPC, handler.getMediaChannelPhase());
    }

    @Test
    public void unansweredOfferExpiresOnTheDrainPass() throws Exception {
        Fixture fixture = fixture();
        List<MediaChannelOffer> offers = new ArrayList<>();
        MutableClock clock = new MutableClock();
        StreamCallHandler handler = handler(fixture, MediaTransportPolicy.Mode.TCP, true, clock,
                offers);
        handler.onClientMessage(hello("tcp"));
        assertEquals(StreamCallHandler.MediaChannelPhase.OFFERED, handler.getMediaChannelPhase());

        clock.advance(TcpMediaConstants.OFFER_TIMEOUT_MS + 1L);
        handler.drainOnce();

        assertEquals(StreamCallHandler.MediaChannelPhase.GRPC, handler.getMediaChannelPhase());
        assertEquals("offer_timeout", handler.getMediaChannelFailureReason());
        assertNull(handler.getMediaChannelKind());
    }

    @Test
    public void readyIsTreatedAsUnsupportedInThisBuild() {
        Fixture fixture = fixture();
        List<MediaChannelOffer> offers = new ArrayList<>();
        StreamCallHandler handler = handler(fixture, MediaTransportPolicy.Mode.TCP, true,
                new MutableClock(), offers);
        handler.onClientMessage(hello("tcp"));

        handler.onClientMessage(answer(offers.get(0).getChannelId(),
                MediaChannelEventType.READY, ""));

        assertEquals(StreamCallHandler.MediaChannelPhase.GRPC, handler.getMediaChannelPhase());
        assertEquals("channel_unsupported", handler.getMediaChannelFailureReason());
    }

    @Test
    public void staleAnswerIsIgnored() {
        Fixture fixture = fixture();
        List<MediaChannelOffer> offers = new ArrayList<>();
        StreamCallHandler handler = handler(fixture, MediaTransportPolicy.Mode.TCP, true,
                new MutableClock(), offers);
        handler.onClientMessage(hello("tcp"));

        handler.onClientMessage(answer(new byte[16], MediaChannelEventType.FAILED, "late"));

        assertEquals(StreamCallHandler.MediaChannelPhase.OFFERED, handler.getMediaChannelPhase());
        assertNull(handler.getMediaChannelFailureReason());
    }

    @Test
    public void closeRevokesTheOutstandingTicket() {
        Fixture fixture = fixture();
        List<MediaChannelOffer> offers = new ArrayList<>();
        MutableClock clock = new MutableClock();
        MediaChannelNegotiator negotiator = new MediaChannelNegotiator(
                MediaTransportPolicy.Mode.TCP, true, offers::add, clock);
        StreamCallHandler handler = handler(fixture, negotiator);

        handler.onClientMessage(hello("tcp"));
        assertNotNull(negotiator.getTicket());

        handler.close();

        assertNull(negotiator.getTicket());
        assertNull(negotiator.getChannelIdHex());
    }

    private static StreamCallHandler handler(Fixture fixture, MediaTransportPolicy.Mode mode,
                                             boolean udpEnabled, MutableClock clock,
                                             List<MediaChannelOffer> offers) {
        return handler(fixture, new FakeTransport(),
                new MediaChannelNegotiator(mode, udpEnabled, offers::add, clock));
    }

    private static StreamCallHandler handler(Fixture fixture, FakeTransport transport,
                                             MediaTransportPolicy.Mode mode, boolean udpEnabled,
                                             MutableClock clock, List<MediaChannelOffer> offers) {
        return handler(fixture, transport,
                new MediaChannelNegotiator(mode, udpEnabled, offers::add, clock));
    }

    private static StreamCallHandler handler(Fixture fixture,
                                             MediaChannelNegotiator negotiator) {
        return handler(fixture, new FakeTransport(), negotiator);
    }

    private static StreamCallHandler handler(Fixture fixture, FakeTransport transport,
                                             MediaChannelNegotiator negotiator) {
        return new StreamCallHandler(fixture.hub, fixture.runtime, transport,
                StreamCallListener.NO_OP, null, PongTarget.NO_OP, null, null, null, null, null,
                negotiator);
    }

    private static Fixture fixture() {
        FakeCodec codec = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        CodecRegistry registry = new CodecRegistry();
        registry.register(codec.registration());
        StreamRuntime runtime = new StreamRuntime(registry, STREAM_KEY,
                StreamDescriptor.builder(STREAM_KEY, 1, FakeCodec.DEFAULT_CODEC_ID,
                                FakeCodec.DEFAULT_MIME, BitstreamFormat.ANNEX_B)
                        .codedSize(64, 48)
                        .build(),
                StreamParameters.DEFAULT, new MutableClock(), 100L);
        runtime.start();
        StreamHub hub = new StreamHub(registry);
        hub.register(runtime);
        hub.activate(STREAM_KEY);
        return new Fixture(codec, runtime, hub);
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

    private static ClientMessage answer(byte[] channelId, MediaChannelEventType type,
                                        String reason) {
        return ClientMessage.newBuilder()
                .setMediaChannelEvent(MediaChannelEvent.newBuilder()
                        .setChannelId(com.google.protobuf.ByteString.copyFrom(channelId))
                        .setType(type)
                        .setReason(reason)
                        .build())
                .build();
    }

    /**
     * Hub under test with the codec that feeds it.
     */
    private static final class Fixture {

        private final FakeCodec codec;

        private final StreamRuntime runtime;

        private final StreamHub hub;

        private Fixture(FakeCodec codec, StreamRuntime runtime, StreamHub hub) {
            this.codec = codec;
            this.runtime = runtime;
            this.hub = hub;
        }
    }
}
