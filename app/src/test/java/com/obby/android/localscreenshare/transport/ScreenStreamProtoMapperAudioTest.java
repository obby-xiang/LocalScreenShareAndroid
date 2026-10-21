package com.obby.android.localscreenshare.transport;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.obby.android.localscreenshare.core.model.AudioAccessUnit;
import com.obby.android.localscreenshare.core.model.AudioCodecCapability;
import com.obby.android.localscreenshare.core.model.AudioDescriptor;
import com.obby.android.localscreenshare.core.model.CodecConfigPart;
import com.obby.android.localscreenshare.core.stream.ClientCapabilities;
import com.obby.android.localscreenshare.core.stream.CodecCapability;
import com.obby.android.localscreenshare.grpc.screenstream.Capabilities;
import com.obby.android.localscreenshare.grpc.screenstream.Descriptor;
import com.obby.android.localscreenshare.grpc.screenstream.ServerMessage;

import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unit tests for the audio mappings of {@link ScreenStreamProtoMapper}.
 */
public class ScreenStreamProtoMapperAudioTest {

    private static final byte[] ASC = new byte[]{0x12, 0x10};

    @Test
    public void audioDescriptorRoundTripsEveryField() {
        AudioDescriptor source = AudioDescriptor.builder(4, "aac", "audio/mp4a-latm",
                        48_000, 2, 128_000)
                .profile("aac-lc")
                .configParts(Collections.singletonList(new CodecConfigPart("csd-0", ASC)))
                .build();

        com.obby.android.localscreenshare.grpc.screenstream.AudioDescriptor wire =
                ScreenStreamProtoMapper.toProtoAudioDescriptor(source);
        AudioDescriptor rebuilt = ScreenStreamProtoMapper.toAudioDescriptor(wire);

        assertEquals(ScreenStreamProtoMapper.PROTOCOL_VERSION, wire.getProtocolVersion());
        assertEquals(4, rebuilt.getConfigEpoch());
        assertEquals("aac", rebuilt.getCodecId());
        assertEquals("audio/mp4a-latm", rebuilt.getMime());
        assertEquals("aac-lc", rebuilt.getProfile());
        assertEquals(48_000, rebuilt.getSampleRate());
        assertEquals(2, rebuilt.getChannels());
        assertEquals(128_000, rebuilt.getBitrateBps());
        assertEquals(1024, rebuilt.getFrameSamples());
        assertEquals(source.getConfigHash(), rebuilt.getConfigHash());
        assertEquals("csd-0", rebuilt.getConfigParts().get(0).getId());
        assertArrayEquals(ASC, rebuilt.getConfigParts().get(0).getPayload());
    }

    @Test
    public void absentProfileStaysUnspecified() {
        AudioDescriptor source = AudioDescriptor.builder(1, "aac", "audio/mp4a-latm",
                        44_100, 1, 64_000)
                .configParts(Collections.singletonList(new CodecConfigPart("csd-0", ASC)))
                .build();

        AudioDescriptor rebuilt = ScreenStreamProtoMapper.toAudioDescriptor(
                ScreenStreamProtoMapper.toProtoAudioDescriptor(source));

        assertNull(rebuilt.getProfile());
    }

    @Test
    public void audioAccessUnitRoundTripsTimingAndPayload() {
        byte[] payload = new byte[]{0x21, 0x00, 0x7f};
        AudioAccessUnit source = new AudioAccessUnit(2, 17, 1_234_567L, payload);

        com.obby.android.localscreenshare.grpc.screenstream.AudioAccessUnit wire =
                ScreenStreamProtoMapper.toProtoAudioAccessUnit(source);
        AudioAccessUnit rebuilt = ScreenStreamProtoMapper.toAudioAccessUnit(wire);

        assertEquals(2, rebuilt.getConfigEpoch());
        assertEquals(17, rebuilt.getSequence());
        assertEquals(1_234_567L, rebuilt.getPtsUs());
        assertArrayEquals(payload, rebuilt.getPayload());
    }

    @Test
    public void capabilitiesRoundTripBothTracks() {
        Map<String, CodecCapability> video = new LinkedHashMap<>();
        video.put("h264", new CodecCapability("h264", "video/avc", null, null,
                1920, 1080, 60, 8_000_000, true, false));
        Map<String, AudioCodecCapability> audio = new LinkedHashMap<>();
        audio.put("aac", new AudioCodecCapability("aac", "audio/mp4a-latm", 48_000, 2, 256_000));

        Capabilities wire = ScreenStreamProtoMapper.toProtoCapabilities(
                new ClientCapabilities(video, audio));
        ClientCapabilities rebuilt = ScreenStreamProtoMapper.toClientCapabilities(wire);

        assertEquals(1, wire.getCodecsCount());
        assertEquals(1, wire.getAudioCodecsCount());
        assertTrue(rebuilt.supports("h264"));
        assertTrue(rebuilt.supportsAudio("aac"));
        assertEquals(48_000, rebuilt.getAudioCapability("aac").getMaxSampleRate());
        assertEquals(2, rebuilt.getAudioCapability("aac").getMaxChannels());
        assertEquals(256_000, rebuilt.getAudioCapability("aac").getMaxBitrateBps());
        assertEquals("audio/mp4a-latm", rebuilt.getAudioCapability("aac").getMime());
    }

    @Test
    public void videoOnlyCapabilitiesDecodeToEmptyAudioMap() {
        Capabilities wire = Capabilities.newBuilder()
                .addCodecs(com.obby.android.localscreenshare.grpc.screenstream.CodecCapability
                        .newBuilder().setCodecId("h264").setMime("video/avc").build())
                .build();

        ClientCapabilities rebuilt = ScreenStreamProtoMapper.toClientCapabilities(wire);

        assertTrue(rebuilt.getAudioCodecIds().isEmpty());
        assertFalse(rebuilt.supportsAudio("aac"));
    }

    @Test
    public void audioMessagesDoNotDisturbTheVideoPayloadCase() {
        AudioDescriptor audio = AudioDescriptor.builder(1, "aac", "audio/mp4a-latm", 48_000, 2,
                        128_000)
                .configParts(Collections.singletonList(new CodecConfigPart("csd-0", ASC)))
                .build();
        ServerMessage audioDescriptor = ServerMessage.newBuilder()
                .setAudioDescriptor(ScreenStreamProtoMapper.toProtoAudioDescriptor(audio))
                .build();
        ServerMessage videoOnly = ServerMessage.newBuilder()
                .setDescriptor(Descriptor.newBuilder()
                        .setCodecId("h264")
                        .setMime("video/avc")
                        .setBitstreamFormat("ANNEX_B")
                        .setCodedWidth(16)
                        .setCodedHeight(16)
                        .setVisibleWidth(16)
                        .setVisibleHeight(16)
                        .build())
                .build();

        assertEquals(ServerMessage.PayloadCase.AUDIO_DESCRIPTOR,
                audioDescriptor.getPayloadCase());
        assertEquals(ServerMessage.PayloadCase.DESCRIPTOR, videoOnly.getPayloadCase());
    }

    @Test
    public void malformedAudioDescriptorIsRejected() {
        com.obby.android.localscreenshare.grpc.screenstream.AudioDescriptor zeroRate =
                ScreenStreamProtoMapper.toProtoAudioDescriptor(defaultDescriptor())
                        .toBuilder().setSampleRate(0).build();
        assertThrows(IllegalArgumentException.class,
                () -> ScreenStreamProtoMapper.toAudioDescriptor(zeroRate));

        com.obby.android.localscreenshare.grpc.screenstream.AudioDescriptor threeChannels =
                ScreenStreamProtoMapper.toProtoAudioDescriptor(defaultDescriptor())
                        .toBuilder().setChannels(3).build();
        assertThrows(IllegalArgumentException.class,
                () -> ScreenStreamProtoMapper.toAudioDescriptor(threeChannels));

        com.obby.android.localscreenshare.grpc.screenstream.AudioDescriptor noConfig =
                ScreenStreamProtoMapper.toProtoAudioDescriptor(defaultDescriptor())
                        .toBuilder().clearConfigParts().build();
        assertThrows(IllegalArgumentException.class,
                () -> ScreenStreamProtoMapper.toAudioDescriptor(noConfig));
    }

    @Test
    public void emptyAudioPayloadAndEpochZeroAreRejected() {
        com.obby.android.localscreenshare.grpc.screenstream.AudioAccessUnit emptyPayload =
                com.obby.android.localscreenshare.grpc.screenstream.AudioAccessUnit.newBuilder()
                        .setConfigEpoch(1)
                        .build();
        assertThrows(IllegalArgumentException.class,
                () -> ScreenStreamProtoMapper.toAudioAccessUnit(emptyPayload));

        com.obby.android.localscreenshare.grpc.screenstream.AudioAccessUnit epochZero =
                com.obby.android.localscreenshare.grpc.screenstream.AudioAccessUnit.newBuilder()
                        .setConfigEpoch(0)
                        .setPayload(com.google.protobuf.ByteString.copyFrom(new byte[]{1}))
                        .build();
        assertThrows(IllegalArgumentException.class,
                () -> ScreenStreamProtoMapper.toAudioAccessUnit(epochZero));
    }

    @Test
    public void nullArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ScreenStreamProtoMapper.toProtoAudioDescriptor(null));
        assertThrows(IllegalArgumentException.class,
                () -> ScreenStreamProtoMapper.toAudioDescriptor(null));
        assertThrows(IllegalArgumentException.class,
                () -> ScreenStreamProtoMapper.toProtoAudioAccessUnit(null));
        assertThrows(IllegalArgumentException.class,
                () -> ScreenStreamProtoMapper.toAudioAccessUnit(null));
    }

    @Test
    public void nullCapabilitiesDecodeToEmptyBothMaps() {
        ClientCapabilities rebuilt = ScreenStreamProtoMapper.toClientCapabilities(null);

        assertTrue(rebuilt.getCodecIds().isEmpty());
        assertTrue(rebuilt.getAudioCodecIds().isEmpty());
        assertEquals(0, ScreenStreamProtoMapper.toProtoCapabilities(null).getCodecsCount());
    }

    private static AudioDescriptor defaultDescriptor() {
        return AudioDescriptor.builder(1, "aac", "audio/mp4a-latm", 48_000, 2, 128_000)
                .configParts(Collections.singletonList(new CodecConfigPart("csd-0", ASC)))
                .build();
    }
}
