package com.obby.android.localscreenshare.client;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.obby.android.localscreenshare.core.client.ClientMediaSession;
import com.obby.android.localscreenshare.core.codec.CodecRegistry;
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
import com.obby.android.localscreenshare.grpc.screenstream.ServerMessage;
import com.obby.android.localscreenshare.transport.ScreenStreamProtoMapper;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit tests for {@link ClientMediaReceiver}.
 */
public class ClientMediaReceiverTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session-1", "stream-1");

    private static final ClientSessionKey CLIENT_KEY = new ClientSessionKey(STREAM_KEY, "client-1");

    private FakeCodec codec;

    private CodecRegistry registry;

    private FakeRenderTarget renderTarget;

    private ClientMediaSession session;

    private List<Boolean> secureChanges;

    @Before
    public void setUp() {
        codec = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        registry = new CodecRegistry();
        registry.register(codec.registration());
        renderTarget = new FakeRenderTarget();
        session = new ClientMediaSession(CLIENT_KEY, registry,
                new ClientCapabilities(Collections.singleton(FakeCodec.DEFAULT_CODEC_ID)),
                renderTarget, null);
        secureChanges = new ArrayList<>();
    }

    @Test
    public void descriptorConfiguresDecoderAndReportsSecure() {
        ClientMediaReceiver receiver = new ClientMediaReceiver(STREAM_KEY, session,
                secureChanges::add);

        receiver.onServerMessage(descriptorMessage(1, true));

        assertEquals(ClientState.WAIT_RANDOM_ACCESS, session.getState());
        assertEquals(Collections.singletonList(true), secureChanges);
        assertEquals(1, codec.getDecoderCreateCount());
    }

    @Test
    public void accessUnitDecodesToRenderTarget() {
        ClientMediaReceiver receiver = new ClientMediaReceiver(STREAM_KEY, session, null);
        receiver.onServerMessage(descriptorMessage(1, false));

        receiver.onServerMessage(accessUnitMessage(1, 0L, true));

        assertEquals(ClientState.STREAMING, session.getState());
        assertEquals(1, renderTarget.getImages().size());
    }

    @Test
    public void staleDescriptorDoesNotChangeSecureTwice() {
        ClientMediaReceiver receiver = new ClientMediaReceiver(STREAM_KEY, session,
                secureChanges::add);
        receiver.onServerMessage(descriptorMessage(1, true));

        // A duplicate descriptor with the same secure flag must not re-report it.
        receiver.onServerMessage(descriptorMessage(1, true));

        assertEquals(Collections.singletonList(true), secureChanges);
    }

    @Test
    public void closeClosesSession() {
        ClientMediaReceiver receiver = new ClientMediaReceiver(STREAM_KEY, session, null);
        receiver.close();
        assertTrue(session.isClosed());

        // Late messages are ignored.
        receiver.onServerMessage(descriptorMessage(1, true));
        assertEquals(ClientState.CLOSED, session.getState());
    }

    @Test
    public void descriptorReportsStreamGeometryBeforeApplyingIt() {
        List<int[]> geometries = new ArrayList<>();
        AtomicReference<ClientState> stateAtCallback = new AtomicReference<>();
        ClientMediaReceiver receiver = new ClientMediaReceiver(STREAM_KEY, session, null,
                (width, height, rotation) -> {
                    geometries.add(new int[]{width, height, rotation});
                    stateAtCallback.set(session.getState());
                });

        receiver.onServerMessage(descriptorMessage(1, false, 1080, 2340, 0));

        assertEquals(1, geometries.size());
        assertArrayEquals(new int[]{1080, 2340, 0}, geometries.get(0));
        assertEquals(ClientState.NEGOTIATING, stateAtCallback.get());
    }

    @Test
    public void descriptorReportsRotation() {
        List<int[]> geometries = new ArrayList<>();
        ClientMediaReceiver receiver = new ClientMediaReceiver(STREAM_KEY, session, null,
                (width, height, rotation) -> geometries.add(new int[]{width, height, rotation}));

        receiver.onServerMessage(descriptorMessage(1, false, 2340, 1080, 90));

        assertEquals(1, geometries.size());
        assertArrayEquals(new int[]{2340, 1080, 90}, geometries.get(0));
    }

    @Test
    public void newerEpochReportsGeometryAgain() {
        List<int[]> geometries = new ArrayList<>();
        ClientMediaReceiver receiver = new ClientMediaReceiver(STREAM_KEY, session, null,
                (width, height, rotation) -> geometries.add(new int[]{width, height, rotation}));

        receiver.onServerMessage(descriptorMessage(1, false, 1080, 2340, 0));
        receiver.onServerMessage(descriptorMessage(2, false, 1080, 2340, 0));

        assertEquals(2, geometries.size());
        assertArrayEquals(new int[]{1080, 2340, 0}, geometries.get(1));
        assertEquals(ClientState.WAIT_RANDOM_ACCESS, session.getState());
    }

    @Test
    public void accessUnitDoesNotReportGeometry() {
        List<int[]> geometries = new ArrayList<>();
        ClientMediaReceiver receiver = new ClientMediaReceiver(STREAM_KEY, session, null,
                (width, height, rotation) -> geometries.add(new int[]{width, height, rotation}));

        receiver.onServerMessage(descriptorMessage(1, false, 1080, 2340, 0));
        receiver.onServerMessage(accessUnitMessage(1, 0L, true));

        assertEquals(1, geometries.size());
    }

    @Test
    public void closedSessionDoesNotReportGeometry() {
        List<int[]> geometries = new ArrayList<>();
        ClientMediaReceiver receiver = new ClientMediaReceiver(STREAM_KEY, session, null,
                (width, height, rotation) -> geometries.add(new int[]{width, height, rotation}));
        receiver.close();

        receiver.onServerMessage(descriptorMessage(1, false, 1080, 2340, 0));

        assertTrue(geometries.isEmpty());
    }

    private ServerMessage descriptorMessage(int epoch, boolean secure) {
        StreamDescriptor descriptor = StreamDescriptor.builder(STREAM_KEY, epoch,
                        FakeCodec.DEFAULT_CODEC_ID, FakeCodec.DEFAULT_MIME, BitstreamFormat.ANNEX_B)
                .codedSize(64, 48)
                .build();
        return ServerMessage.newBuilder()
                .setDescriptor(ScreenStreamProtoMapper.toProtoDescriptor(descriptor, secure))
                .build();
    }

    private ServerMessage descriptorMessage(int epoch, boolean secure, int visibleWidth,
                                            int visibleHeight, int rotationDegrees) {
        StreamDescriptor descriptor = StreamDescriptor.builder(STREAM_KEY, epoch,
                        FakeCodec.DEFAULT_CODEC_ID, FakeCodec.DEFAULT_MIME, BitstreamFormat.ANNEX_B)
                .codedSize(visibleWidth, visibleHeight)
                .visibleSize(visibleWidth, visibleHeight)
                .rotationDegrees(rotationDegrees)
                .build();
        return ServerMessage.newBuilder()
                .setDescriptor(ScreenStreamProtoMapper.toProtoDescriptor(descriptor, secure))
                .build();
    }

    private ServerMessage accessUnitMessage(int epoch, long sequence, boolean joinPoint) {
        EncodedAccessUnit accessUnit = new EncodedAccessUnit(STREAM_KEY, epoch, sequence,
                sequence * 1000L, EncodedAccessUnit.DTS_UNSET,
                AccessUnitFlags.of(joinPoint, joinPoint, false),
                joinPoint ? new JoinInfo(epoch, Collections.emptyList()) : null, null,
                new byte[]{0x01});
        return ServerMessage.newBuilder()
                .setAccessUnit(ScreenStreamProtoMapper.toProtoAccessUnit(accessUnit))
                .build();
    }
}
