package com.obby.android.localscreenshare.client;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.obby.android.localscreenshare.core.client.ClientMediaSession;
import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.metrics.PipelineMetrics;
import com.obby.android.localscreenshare.core.model.AccessUnitFlags;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.model.ClientState;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.JoinInfo;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.stream.ClientCapabilities;
import com.obby.android.localscreenshare.core.support.FakeCodec;
import com.obby.android.localscreenshare.core.support.FakeRenderTarget;
import com.obby.android.localscreenshare.core.support.MutableNanoClock;
import com.obby.android.localscreenshare.grpc.screenstream.ServerMessage;
import com.obby.android.localscreenshare.transport.ScreenStreamProtoMapper;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Unit tests for the model entry points of {@link ClientMediaReceiver}.
 *
 * <p>A media channel delivers model objects and the gRPC stream delivers protobuf messages; both
 * have to produce the same session state, the same side effects and the same counters, because the
 * receive metrics and the secure and geometry callbacks live in exactly one place.</p>
 */
public class ClientMediaReceiverRawEntryTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session-1", "stream-1");

    private static final ClientSessionKey CLIENT_KEY = new ClientSessionKey(STREAM_KEY, "client-1");

    @Test
    public void descriptorThroughEitherEntryPoint_hasTheSameEffect() {
        Fixture proto = fixture();
        proto.receiver.onServerMessage(descriptorMessage(1, true, 1080, 2340, 0));

        Fixture raw = fixture();
        raw.receiver.onDescriptor(descriptor(1, 1080, 2340, 0), true);

        assertEquals(ClientState.WAIT_RANDOM_ACCESS, raw.session.getState());
        assertEquals(proto.session.getState(), raw.session.getState());
        assertEquals(proto.codec.getDecoderCreateCount(), raw.codec.getDecoderCreateCount());
        assertEquals(Collections.singletonList(true), proto.secureChanges);
        assertEquals(proto.secureChanges, raw.secureChanges);
        assertEquals(1, proto.geometries.size());
        assertEquals(proto.geometries.size(), raw.geometries.size());
        assertArrayEquals(proto.geometries.get(0), raw.geometries.get(0));
    }

    @Test
    public void accessUnitThroughEitherEntryPoint_isCountedOnce() {
        Fixture proto = fixture();
        proto.receiver.onServerMessage(descriptorMessage(1, false, 64, 48, 0));
        proto.receiver.onServerMessage(accessUnitMessage(1, 0L, true), 1_000_000L);

        Fixture raw = fixture();
        raw.receiver.onDescriptor(descriptor(1, 64, 48, 0), false);
        raw.receiver.onAccessUnit(accessUnit(1, 0L, true), 1_000_000L);

        assertEquals(ClientState.STREAMING, raw.session.getState());
        assertEquals(proto.session.getState(), raw.session.getState());
        assertEquals(1L, proto.metrics.count(PipelineMetrics.Counter.ACCESS_UNITS));
        assertEquals(proto.metrics.count(PipelineMetrics.Counter.ACCESS_UNITS),
                raw.metrics.count(PipelineMetrics.Counter.ACCESS_UNITS));
        assertEquals(proto.metrics.count(PipelineMetrics.Counter.ACCESS_UNIT_BYTES),
                raw.metrics.count(PipelineMetrics.Counter.ACCESS_UNIT_BYTES));
        assertEquals(1, proto.renderTarget.getImages().size());
        assertEquals(proto.renderTarget.getImages().size(), raw.renderTarget.getImages().size());
    }

    @Test
    public void rawEntryPoints_areIgnoredAfterClose() {
        Fixture fixture = fixture();
        fixture.receiver.close();

        fixture.receiver.onDescriptor(descriptor(1, 64, 48, 0), false);
        fixture.receiver.onAccessUnit(accessUnit(1, 0L, true), 1_000_000L);

        assertEquals(ClientState.CLOSED, fixture.session.getState());
        assertEquals(0L, fixture.metrics.count(PipelineMetrics.Counter.ACCESS_UNITS));
        assertTrue(fixture.geometries.isEmpty());
        assertTrue(fixture.secureChanges.isEmpty());
        assertTrue(fixture.renderTarget.getImages().isEmpty());
    }

    @Test
    public void rawEntryPoints_ignoreNullPayloads() {
        Fixture fixture = fixture();

        fixture.receiver.onDescriptor(null, false);
        fixture.receiver.onAccessUnit(null, 1_000_000L);

        assertEquals(ClientState.NEGOTIATING, fixture.session.getState());
        assertEquals(0L, fixture.metrics.count(PipelineMetrics.Counter.ACCESS_UNITS));
        assertTrue(fixture.secureChanges.isEmpty());
        assertFalse(fixture.session.isClosed());
    }

    private static Fixture fixture() {
        FakeCodec codec = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        CodecRegistry registry = new CodecRegistry();
        registry.register(codec.registration());
        FakeRenderTarget renderTarget = new FakeRenderTarget();
        ClientMediaSession session = new ClientMediaSession(CLIENT_KEY, registry,
                new ClientCapabilities(Collections.singleton(FakeCodec.DEFAULT_CODEC_ID)),
                renderTarget, null);
        PipelineMetrics metrics = PipelineMetrics.create(new MutableNanoClock(), 8);
        Fixture fixture = new Fixture(codec, session, renderTarget, metrics);
        fixture.receiver = new ClientMediaReceiver(STREAM_KEY, session,
                fixture.secureChanges::add,
                (width, height, rotation) ->
                        fixture.geometries.add(new int[]{width, height, rotation}),
                null, metrics);
        return fixture;
    }

    private static StreamDescriptor descriptor(int epoch, int visibleWidth, int visibleHeight,
                                               int rotationDegrees) {
        return StreamDescriptor.builder(STREAM_KEY, epoch, FakeCodec.DEFAULT_CODEC_ID,
                        FakeCodec.DEFAULT_MIME, BitstreamFormat.ANNEX_B)
                .codedSize(visibleWidth, visibleHeight)
                .visibleSize(visibleWidth, visibleHeight)
                .rotationDegrees(rotationDegrees)
                .build();
    }

    private static EncodedAccessUnit accessUnit(int epoch, long sequence, boolean joinPoint) {
        AccessUnitFlags flags = joinPoint
                ? AccessUnitFlags.of(true, true, false)
                : AccessUnitFlags.none();
        JoinInfo joinInfo = joinPoint
                ? new JoinInfo(epoch, Collections.emptyList())
                : null;
        return new EncodedAccessUnit(STREAM_KEY, epoch, sequence, sequence * 1_000L,
                EncodedAccessUnit.DTS_UNSET, flags, joinInfo, null, new byte[]{0x01});
    }

    private static ServerMessage descriptorMessage(int epoch, boolean secure, int visibleWidth,
                                                   int visibleHeight, int rotationDegrees) {
        return ServerMessage.newBuilder()
                .setDescriptor(ScreenStreamProtoMapper.toProtoDescriptor(
                        descriptor(epoch, visibleWidth, visibleHeight, rotationDegrees), secure))
                .build();
    }

    private static ServerMessage accessUnitMessage(int epoch, long sequence, boolean joinPoint) {
        return ServerMessage.newBuilder()
                .setAccessUnit(ScreenStreamProtoMapper.toProtoAccessUnit(
                        accessUnit(epoch, sequence, joinPoint)))
                .build();
    }

    /**
     * One receiver together with the collaborators its effects are observed through.
     */
    private static final class Fixture {

        private final FakeCodec codec;

        private final ClientMediaSession session;

        private final FakeRenderTarget renderTarget;

        private final PipelineMetrics metrics;

        private final List<Boolean> secureChanges = new ArrayList<>();

        private final List<int[]> geometries = new ArrayList<>();

        private ClientMediaReceiver receiver;

        private Fixture(FakeCodec codec, ClientMediaSession session, FakeRenderTarget renderTarget,
                        PipelineMetrics metrics) {
            this.codec = codec;
            this.session = session;
            this.renderTarget = renderTarget;
            this.metrics = metrics;
        }
    }
}
