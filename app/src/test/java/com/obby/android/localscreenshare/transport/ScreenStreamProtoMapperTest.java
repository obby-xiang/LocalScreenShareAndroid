package com.obby.android.localscreenshare.transport;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.obby.android.localscreenshare.core.model.AccessUnitFlags;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.CodecConfigPart;
import com.obby.android.localscreenshare.core.model.ColorInfo;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.JoinInfo;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.stream.ClientCapabilities;
import com.obby.android.localscreenshare.core.stream.CodecCapability;
import com.obby.android.localscreenshare.grpc.screenstream.Capabilities;
import com.obby.android.localscreenshare.grpc.screenstream.ClientMessage;
import com.obby.android.localscreenshare.grpc.screenstream.Hello;
import com.obby.android.localscreenshare.grpc.screenstream.MediaChannelEvent;
import com.obby.android.localscreenshare.grpc.screenstream.MediaChannelEventType;
import com.obby.android.localscreenshare.grpc.screenstream.Ping;
import com.obby.android.localscreenshare.grpc.screenstream.Pong;
import com.obby.android.localscreenshare.grpc.screenstream.Ready;
import com.obby.android.localscreenshare.grpc.screenstream.RequestKeyframe;
import com.obby.android.localscreenshare.grpc.screenstream.ServerMessage;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link ScreenStreamProtoMapper}.
 */
public class ScreenStreamProtoMapperTest {

    private static final StreamKey KEY = new StreamKey("session", "stream");

    @Test
    public void descriptorRoundTripsAllFieldsAndPreservesConfigHash() {
        CodecConfigPart sps = new CodecConfigPart("sps", new byte[]{0x00, 0x01});
        CodecConfigPart pps = new CodecConfigPart("pps", new byte[]{0x02});
        ColorInfo color = new ColorInfo("bt709", "bt709", true);
        StreamDescriptor source = StreamDescriptor.builder(KEY, 3, "h264", "video/avc",
                        BitstreamFormat.ANNEX_B)
                .profile("baseline")
                .level("3.1")
                .codedSize(1280, 720)
                .visibleSize(1264, 704)
                .rotationDegrees(90)
                .configParts(Arrays.asList(sps, pps))
                .colorInfo(color)
                .build();

        com.obby.android.localscreenshare.grpc.screenstream.Descriptor wire =
                ScreenStreamProtoMapper.toProtoDescriptor(source, true);
        StreamDescriptor rebuilt = ScreenStreamProtoMapper.toStreamDescriptor(KEY, wire);

        assertEquals("baseline", rebuilt.getProfile());
        assertEquals("3.1", rebuilt.getLevel());
        assertEquals(1280, rebuilt.getCodedWidth());
        assertEquals(720, rebuilt.getCodedHeight());
        assertEquals(1264, rebuilt.getVisibleWidth());
        assertEquals(704, rebuilt.getVisibleHeight());
        assertEquals(90, rebuilt.getRotationDegrees());
        assertEquals(BitstreamFormat.ANNEX_B, rebuilt.getBitstreamFormat());
        assertEquals(2, rebuilt.getConfigParts().size());
        assertEquals("sps", rebuilt.getConfigParts().get(0).getId());
        assertArrayEquals(new byte[]{0x00, 0x01}, rebuilt.getConfigParts().get(0).getPayload());
        assertEquals("pps", rebuilt.getConfigParts().get(1).getId());
        assertArrayEquals(new byte[]{0x02}, rebuilt.getConfigParts().get(1).getPayload());
        assertEquals("bt709", rebuilt.getColorInfo().getColorSpace());
        assertEquals("bt709", rebuilt.getColorInfo().getTransferFunction());
        assertTrue(rebuilt.getColorInfo().isFullRange());
        assertEquals(source.getConfigHash(), rebuilt.getConfigHash());
        assertTrue(wire.getSecure());
    }

    @Test
    public void descriptorWithDefaultsRoundTrips() {
        StreamDescriptor source = StreamDescriptor.builder(KEY, 1, "jpeg", "image/jpeg",
                        BitstreamFormat.JPEG_FRAME)
                .codedSize(640, 480)
                .build();

        com.obby.android.localscreenshare.grpc.screenstream.Descriptor wire =
                ScreenStreamProtoMapper.toProtoDescriptor(source, false);
        StreamDescriptor rebuilt = ScreenStreamProtoMapper.toStreamDescriptor(KEY, wire);

        assertNull(rebuilt.getProfile());
        assertNull(rebuilt.getLevel());
        assertEquals(640, rebuilt.getVisibleWidth());
        assertEquals(480, rebuilt.getVisibleHeight());
        assertEquals(0, rebuilt.getRotationDegrees());
        assertTrue(rebuilt.getConfigParts().isEmpty());
        assertFalse(rebuilt.getColorInfo().isFullRange());
        assertNull(rebuilt.getColorInfo().getColorSpace());
        assertEquals(source.getConfigHash(), rebuilt.getConfigHash());
        assertFalse(wire.getSecure());
    }

    @Test
    public void accessUnitWithJoinInfoRoundTrips() {
        CodecConfigPart sps = new CodecConfigPart("sps", new byte[]{0x01});
        EncodedAccessUnit source = new EncodedAccessUnit(KEY, 2, 7L, 1_000L, 900L,
                AccessUnitFlags.of(true, true, false),
                new JoinInfo(2, List.of(sps)), 1, new byte[]{0x10, 0x20});

        com.obby.android.localscreenshare.grpc.screenstream.AccessUnit wire =
                ScreenStreamProtoMapper.toProtoAccessUnit(source);
        EncodedAccessUnit rebuilt = ScreenStreamProtoMapper.toAccessUnit(KEY, wire);

        assertEquals(2, rebuilt.getConfigEpoch());
        assertEquals(7L, rebuilt.getSequence());
        assertEquals(1_000L, rebuilt.getPtsUs());
        assertTrue(rebuilt.hasDtsUs());
        assertEquals(900L, rebuilt.getDtsUs());
        assertTrue(rebuilt.getFlags().isSyncPoint());
        assertTrue(rebuilt.getFlags().isJoinPoint());
        assertFalse(rebuilt.getFlags().isConfig());
        assertEquals(Integer.valueOf(1), rebuilt.getTemporalLayerId());
        assertNotNull(rebuilt.getJoinInfo());
        assertEquals(1, rebuilt.getJoinInfo().getRequiredConfigParts().size());
        assertEquals("sps", rebuilt.getJoinInfo().getRequiredConfigParts().get(0).getId());
        assertArrayEquals(new byte[]{0x10, 0x20}, rebuilt.getPayload());
    }

    @Test
    public void accessUnitWithoutDtsOrTemporalLayerRoundTrips() {
        EncodedAccessUnit source = new EncodedAccessUnit(KEY, 1, 0L, 500L,
                EncodedAccessUnit.DTS_UNSET, AccessUnitFlags.none(), null, null,
                new byte[]{0x01});

        com.obby.android.localscreenshare.grpc.screenstream.AccessUnit wire =
                ScreenStreamProtoMapper.toProtoAccessUnit(source);
        EncodedAccessUnit rebuilt = ScreenStreamProtoMapper.toAccessUnit(KEY, wire);

        assertFalse(rebuilt.hasDtsUs());
        assertEquals(EncodedAccessUnit.DTS_UNSET, rebuilt.getDtsUs());
        assertNull(rebuilt.getTemporalLayerId());
        assertNull(rebuilt.getJoinInfo());
        assertFalse(rebuilt.getFlags().isJoinPoint());
    }

    @Test
    public void capabilitiesRoundTripFullModel() {
        Map<String, CodecCapability> caps = new LinkedHashMap<>();
        caps.put("h264", new CodecCapability("h264", "video/avc", "baseline", "3.1",
                1920, 1080, 60, 8_000_000, true, false));
        caps.put("jpeg", CodecCapability.any("jpeg"));
        ClientCapabilities clientCaps = new ClientCapabilities(caps);

        Capabilities wire = ScreenStreamProtoMapper.toProtoCapabilities(clientCaps);
        ClientCapabilities rebuilt = ScreenStreamProtoMapper.toClientCapabilities(wire);

        assertEquals(clientCaps.getCodecIds(), rebuilt.getCodecIds());
        CodecCapability h264 = rebuilt.getCapability("h264");
        assertEquals("video/avc", h264.getMime());
        assertEquals("baseline", h264.getProfile());
        assertEquals("3.1", h264.getLevel());
        assertEquals(1920, h264.getMaxWidth());
        assertEquals(1080, h264.getMaxHeight());
        assertEquals(60, h264.getMaxFrameRate());
        assertEquals(8_000_000, h264.getMaxBitrateBps());
        assertTrue(h264.isSurfaceOutput());
        assertFalse(h264.isAdaptive());
    }

    @Test
    public void capabilitiesNullMapsToEmptyCapabilities() {
        assertTrue(ScreenStreamProtoMapper.toClientCapabilities(null).getCodecIds().isEmpty());
    }

    @Test
    public void dispatchRoutesEveryPayloadCase() {
        RecordingListener listener = new RecordingListener();

        ScreenStreamProtoMapper.dispatchClientMessage(
                ClientMessage.newBuilder().setHello(Hello.getDefaultInstance()).build(), listener);
        assertTrue(listener.helloSeen);
        assertFalse(listener.readySeen);
        assertFalse(listener.keyframeSeen);

        listener = new RecordingListener();
        ScreenStreamProtoMapper.dispatchClientMessage(
                ClientMessage.newBuilder().setReady(Ready.getDefaultInstance()).build(), listener);
        assertTrue(listener.readySeen);

        listener = new RecordingListener();
        ScreenStreamProtoMapper.dispatchClientMessage(
                ClientMessage.newBuilder()
                        .setRequestKeyframe(RequestKeyframe.getDefaultInstance()).build(), listener);
        assertTrue(listener.keyframeSeen);

        listener = new RecordingListener();
        ScreenStreamProtoMapper.dispatchClientMessage(
                ClientMessage.newBuilder()
                        .setPing(Ping.newBuilder().setSeq(7).setClientUptimeMs(1_000L).build())
                        .build(), listener);
        assertTrue(listener.pingSeen);
        assertEquals(7, listener.pingSeq);
    }

    @Test
    public void feedbackRoundTrip() {
        com.obby.android.localscreenshare.core.model.StreamFeedback source =
                new com.obby.android.localscreenshare.core.model.StreamFeedback(5, 2, 420, 9_000L);

        com.obby.android.localscreenshare.grpc.screenstream.StreamFeedback wire =
                ScreenStreamProtoMapper.toProtoFeedback(source);
        com.obby.android.localscreenshare.core.model.StreamFeedback rebuilt =
                ScreenStreamProtoMapper.toStreamFeedback(wire);

        assertEquals(5, wire.getQueueDepthPeak());
        assertEquals(5, rebuilt.getQueueDepthPeak());
        assertEquals(2, rebuilt.getCatchUpEvents());
        assertEquals(420, rebuilt.getQueueWaitPeakMs());
        assertEquals(9_000L, rebuilt.getClientUptimeMs());
    }

    @Test
    public void feedbackDefaultsAreZero() {
        com.obby.android.localscreenshare.core.model.StreamFeedback rebuilt =
                ScreenStreamProtoMapper.toStreamFeedback(
                        com.obby.android.localscreenshare.grpc.screenstream.StreamFeedback
                                .getDefaultInstance());

        assertEquals(0, rebuilt.getQueueDepthPeak());
        assertEquals(0, rebuilt.getCatchUpEvents());
        assertEquals(0, rebuilt.getQueueWaitPeakMs());
        assertEquals(0L, rebuilt.getClientUptimeMs());
    }

    @Test
    public void feedbackIsDispatchedToListener() {
        RecordingListener listener = new RecordingListener();

        ScreenStreamProtoMapper.dispatchClientMessage(ClientMessage.newBuilder()
                .setFeedback(com.obby.android.localscreenshare.grpc.screenstream.StreamFeedback
                        .newBuilder()
                        .setQueueDepthPeak(6)
                        .setCatchUpEvents(1)
                        .setQueueWaitPeakMs(300)
                        .setClientUptimeMs(42_000L)
                        .build())
                .build(), listener);

        assertTrue(listener.feedbackSeen);
        assertEquals(6, listener.feedbackDepth);
        assertEquals(1, listener.feedbackCatchUps);
    }

    @Test
    public void dispatchRoutesMediaChannelEvent() {
        RecordingListener listener = new RecordingListener();

        ScreenStreamProtoMapper.dispatchClientMessage(ClientMessage.newBuilder()
                .setMediaChannelEvent(MediaChannelEvent.newBuilder()
                        .setChannelId(com.google.protobuf.ByteString.copyFrom(new byte[]{1, 2}))
                        .setType(MediaChannelEventType.FAILED)
                        .setReason("not_implemented")
                        .build())
                .build(), listener);

        assertTrue(listener.mediaEventSeen);
        assertEquals(MediaChannelEventType.FAILED, listener.mediaEventType);
    }

    @Test
    public void pingAndPong_roundTripThroughTheWireFormat() throws Exception {
        ClientMessage ping = ClientMessage.newBuilder()
                .setPing(Ping.newBuilder().setSeq(3).setClientUptimeMs(123_456L).build())
                .build();
        ClientMessage parsedPing = ClientMessage.parseFrom(ping.toByteArray());
        assertEquals(ClientMessage.PayloadCase.PING, parsedPing.getPayloadCase());
        assertEquals(3, parsedPing.getPing().getSeq());
        assertEquals(123_456L, parsedPing.getPing().getClientUptimeMs());

        ServerMessage pong = ServerMessage.newBuilder()
                .setPong(Pong.newBuilder().setSeq(3).setClientUptimeMs(123_456L).build())
                .build();
        ServerMessage parsedPong = ServerMessage.parseFrom(pong.toByteArray());
        assertEquals(ServerMessage.PayloadCase.PONG, parsedPong.getPayloadCase());
        assertEquals(3, parsedPong.getPong().getSeq());
        assertEquals(123_456L, parsedPong.getPong().getClientUptimeMs());

        // An unknown payload is still ignored instead of failing the stream.
        assertEquals(ClientMessage.PayloadCase.PAYLOAD_NOT_SET,
                ClientMessage.getDefaultInstance().getPayloadCase());
        assertEquals(ServerMessage.PayloadCase.PAYLOAD_NOT_SET,
                ServerMessage.getDefaultInstance().getPayloadCase());
    }

    @Test
    public void dispatchRejectsEmptyPayload() {
        assertThrows(IllegalArgumentException.class,
                () -> ScreenStreamProtoMapper.dispatchClientMessage(
                        ClientMessage.getDefaultInstance(), new RecordingListener()));
    }

    @Test
    public void descriptorRoundTrip_keepsFrameRate() {
        StreamDescriptor source = StreamDescriptor.builder(KEY, 1, "h264", "video/avc",
                        BitstreamFormat.ANNEX_B)
                .codedSize(1280, 720)
                .frameRate(24)
                .build();

        com.obby.android.localscreenshare.grpc.screenstream.Descriptor wire =
                ScreenStreamProtoMapper.toProtoDescriptor(source, false);
        StreamDescriptor rebuilt = ScreenStreamProtoMapper.toStreamDescriptor(KEY, wire);

        assertEquals(24, wire.getFrameRate());
        assertEquals(24, rebuilt.getFrameRate());
    }

    @Test
    public void descriptorWithoutFrameRate_readsZero() {
        com.obby.android.localscreenshare.grpc.screenstream.Descriptor wire =
                com.obby.android.localscreenshare.grpc.screenstream.Descriptor.newBuilder()
                        .setProtocolVersion(ScreenStreamProtoMapper.PROTOCOL_VERSION)
                        .setConfigEpoch(1)
                        .setCodecId("jpeg")
                        .setMime("image/jpeg")
                        .setCodedWidth(640)
                        .setCodedHeight(480)
                        .setVisibleWidth(640)
                        .setVisibleHeight(480)
                        .setBitstreamFormat(BitstreamFormat.JPEG_FRAME.name())
                        .build();

        StreamDescriptor rebuilt = ScreenStreamProtoMapper.toStreamDescriptor(KEY, wire);

        assertEquals(0, rebuilt.getFrameRate());
    }

    @Test
    public void toProtoDescriptor_keepsConfigHash() {
        StreamDescriptor source = StreamDescriptor.builder(KEY, 7, "h264", "video/avc",
                        BitstreamFormat.ANNEX_B)
                .codedSize(1280, 720)
                .frameRate(30)
                .build();

        com.obby.android.localscreenshare.grpc.screenstream.Descriptor wire =
                ScreenStreamProtoMapper.toProtoDescriptor(source, false);

        assertEquals(source.getConfigHash(), wire.getConfigHash());
    }

    @Test
    public void descriptorFrameRate_doesNotAffectHashOnRoundTrip() {
        StreamDescriptor withoutHint = StreamDescriptor.builder(KEY, 1, "h264", "video/avc",
                        BitstreamFormat.ANNEX_B)
                .codedSize(1280, 720)
                .build();
        StreamDescriptor withHint = StreamDescriptor.builder(KEY, 1, "h264", "video/avc",
                        BitstreamFormat.ANNEX_B)
                .codedSize(1280, 720)
                .frameRate(60)
                .build();

        StreamDescriptor hintedRebuilt = ScreenStreamProtoMapper.toStreamDescriptor(
                KEY, ScreenStreamProtoMapper.toProtoDescriptor(withHint, false));
        StreamDescriptor plainRebuilt = ScreenStreamProtoMapper.toStreamDescriptor(
                KEY, ScreenStreamProtoMapper.toProtoDescriptor(withoutHint, false));

        assertEquals(hintedRebuilt.getConfigHash(), plainRebuilt.getConfigHash());
        assertEquals(60, hintedRebuilt.getFrameRate());
        assertEquals(0, plainRebuilt.getFrameRate());
    }

    private static final class RecordingListener implements ClientMessageListener {
        boolean helloSeen;
        boolean readySeen;
        boolean keyframeSeen;
        boolean pingSeen;
        int pingSeq;
        boolean mediaEventSeen;
        MediaChannelEventType mediaEventType;
        boolean feedbackSeen;
        int feedbackDepth;
        int feedbackCatchUps;

        @Override
        public void onFeedback(
                com.obby.android.localscreenshare.core.model.StreamFeedback feedback) {
            feedbackSeen = true;
            feedbackDepth = feedback.getQueueDepthPeak();
            feedbackCatchUps = feedback.getCatchUpEvents();
        }

        @Override
        public void onHello(Hello hello) {
            helloSeen = true;
        }

        @Override
        public void onMediaChannelEvent(MediaChannelEvent event) {
            mediaEventSeen = true;
            mediaEventType = event.getType();
        }

        @Override
        public void onReady(Ready ready) {
            readySeen = true;
        }

        @Override
        public void onRequestKeyframe(RequestKeyframe requestKeyframe) {
            keyframeSeen = true;
        }

        @Override
        public void onPing(Ping ping) {
            pingSeen = true;
            pingSeq = ping.getSeq();
        }
    }
}
