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
import com.obby.android.localscreenshare.grpc.screenstream.Capabilities;
import com.obby.android.localscreenshare.grpc.screenstream.ClientMessage;
import com.obby.android.localscreenshare.grpc.screenstream.Hello;
import com.obby.android.localscreenshare.grpc.screenstream.Ready;
import com.obby.android.localscreenshare.grpc.screenstream.RequestKeyframe;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Unit tests for {@link ScreenStreamProtoMapper}. */
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
    public void capabilitiesRoundTripCodecIds() {
        Set<String> ids = new LinkedHashSet<>(Arrays.asList("h264", "jpeg"));
        Capabilities wire = ScreenStreamProtoMapper.toProtoCapabilities(ids);
        assertEquals(ids, ScreenStreamProtoMapper.toCodecIds(wire));
    }

    @Test
    public void capabilitiesNullMapsToEmptySet() {
        assertTrue(ScreenStreamProtoMapper.toCodecIds(null).isEmpty());
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
    }

    @Test
    public void dispatchRejectsEmptyPayload() {
        assertThrows(IllegalArgumentException.class,
                () -> ScreenStreamProtoMapper.dispatchClientMessage(
                        ClientMessage.getDefaultInstance(), new RecordingListener()));
    }

    private static final class RecordingListener implements ClientMessageListener {
        boolean helloSeen;
        boolean readySeen;
        boolean keyframeSeen;

        @Override
        public void onHello(Hello hello) {
            helloSeen = true;
        }

        @Override
        public void onReady(Ready ready) {
            readySeen = true;
        }

        @Override
        public void onRequestKeyframe(RequestKeyframe requestKeyframe) {
            keyframeSeen = true;
        }
    }
}
