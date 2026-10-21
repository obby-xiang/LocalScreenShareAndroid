package com.obby.android.localscreenshare.transport;

import com.obby.android.localscreenshare.core.model.AccessUnitFlags;
import com.obby.android.localscreenshare.core.model.AudioAccessUnit;
import com.obby.android.localscreenshare.core.model.AudioCodecCapability;
import com.obby.android.localscreenshare.core.model.AudioDescriptor;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.CodecConfigPart;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.JoinInfo;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.stream.ClientCapabilities;
import com.obby.android.localscreenshare.core.stream.CodecCapability;
import com.obby.android.localscreenshare.grpc.screenstream.Capabilities;
import com.obby.android.localscreenshare.grpc.screenstream.ClientMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stateless mapper between the framework's stream model and the wire protocol.
 *
 * <p>The stream identity ({@link StreamKey}) is implicit on the wire - one stream per RPC - so the
 * server-side mappings do not carry it and the client-side mappings require it as an explicit
 * argument to rebuild descriptors and access units. The {@code secure} flag is session-level
 * metadata, not part of a {@link StreamDescriptor}; it travels on the proto descriptor and is read
 * separately by the receiving side. The {@code config_hash} is sent for diagnostics only: the
 * client recomputes it from the rebuilt descriptor, so it is never trusted as input.</p>
 *
 * <p>All methods are static and side-effect free; the proto byte payloads are copied by the proto
 * builders, so returned objects never alias the framework's byte arrays.</p>
 */
public final class ScreenStreamProtoMapper {

    /**
     * Version of the wire protocol implemented by this mapper.
     */
    public static final int PROTOCOL_VERSION = 1;

    private ScreenStreamProtoMapper() {
    }

    /**
     * Maps a framework descriptor to its wire form.
     *
     * @param descriptor the published descriptor; must not be null
     * @param secure     the session-level secure flag carried out of band
     * @throws IllegalArgumentException if {@code descriptor} is null
     */
    public static com.obby.android.localscreenshare.grpc.screenstream.Descriptor toProtoDescriptor(
            StreamDescriptor descriptor, boolean secure) {
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor must not be null");
        }
        com.obby.android.localscreenshare.grpc.screenstream.Descriptor.Builder builder =
                com.obby.android.localscreenshare.grpc.screenstream.Descriptor.newBuilder()
                        .setProtocolVersion(PROTOCOL_VERSION)
                        .setConfigEpoch(descriptor.getConfigEpoch())
                        .setCodecId(descriptor.getCodecId())
                        .setMime(descriptor.getMime())
                        .setCodedWidth(descriptor.getCodedWidth())
                        .setCodedHeight(descriptor.getCodedHeight())
                        .setVisibleWidth(descriptor.getVisibleWidth())
                        .setVisibleHeight(descriptor.getVisibleHeight())
                        .setRotationDegrees(descriptor.getRotationDegrees())
                        .setBitstreamFormat(descriptor.getBitstreamFormat().name())
                        .setConfigHash(descriptor.getConfigHash())
                        .setSecure(secure);
        if (descriptor.getProfile() != null) {
            builder.setProfile(descriptor.getProfile());
        }
        if (descriptor.getLevel() != null) {
            builder.setLevel(descriptor.getLevel());
        }
        for (CodecConfigPart part : descriptor.getConfigParts()) {
            builder.addConfigParts(toProtoConfigPart(part));
        }
        builder.setColor(toProtoColor(descriptor.getColorInfo()));
        return builder.build();
    }

    /**
     * Rebuilds a framework descriptor from its wire form.
     *
     * @param streamKey the stream identity, implicit on the wire; required
     * @param descriptor the received proto descriptor; must not be null
     * @throws IllegalArgumentException if an argument is null or the wire format is malformed
     */
    public static StreamDescriptor toStreamDescriptor(StreamKey streamKey,
            com.obby.android.localscreenshare.grpc.screenstream.Descriptor descriptor) {
        if (streamKey == null) {
            throw new IllegalArgumentException("streamKey must not be null");
        }
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor must not be null");
        }
        return StreamDescriptor.builder(streamKey, descriptor.getConfigEpoch(),
                        descriptor.getCodecId(), descriptor.getMime(),
                        BitstreamFormat.valueOf(descriptor.getBitstreamFormat()))
                .profile(nullIfEmpty(descriptor.getProfile()))
                .level(nullIfEmpty(descriptor.getLevel()))
                .codedSize(descriptor.getCodedWidth(), descriptor.getCodedHeight())
                .visibleSize(descriptor.getVisibleWidth(), descriptor.getVisibleHeight())
                .rotationDegrees(descriptor.getRotationDegrees())
                .configParts(toConfigParts(descriptor.getConfigPartsList()))
                .colorInfo(toColor(descriptor.getColor()))
                .build();
    }

    /**
     * Maps a framework access unit to its wire form.
     *
     * @throws IllegalArgumentException if {@code accessUnit} is null
     */
    public static com.obby.android.localscreenshare.grpc.screenstream.AccessUnit toProtoAccessUnit(
            EncodedAccessUnit accessUnit) {
        if (accessUnit == null) {
            throw new IllegalArgumentException("accessUnit must not be null");
        }
        com.obby.android.localscreenshare.grpc.screenstream.AccessUnit.Builder builder =
                com.obby.android.localscreenshare.grpc.screenstream.AccessUnit.newBuilder()
                        .setConfigEpoch(accessUnit.getConfigEpoch())
                        .setSequence(accessUnit.getSequence())
                        .setPtsUs(accessUnit.getPtsUs())
                        .setHasDts(accessUnit.hasDtsUs())
                        .setSyncPoint(accessUnit.getFlags().isSyncPoint())
                        .setJoinPoint(accessUnit.getFlags().isJoinPoint())
                        .setConfig(accessUnit.getFlags().isConfig())
                        .setPayload(com.google.protobuf.ByteString.copyFrom(accessUnit.getPayload()));
        if (accessUnit.hasDtsUs()) {
            builder.setDtsUs(accessUnit.getDtsUs());
        }
        if (accessUnit.getTemporalLayerId() != null) {
            builder.setHasTemporalLayerId(true);
            builder.setTemporalLayerId(accessUnit.getTemporalLayerId());
        }
        JoinInfo joinInfo = accessUnit.getJoinInfo();
        if (joinInfo != null) {
            for (CodecConfigPart part : joinInfo.getRequiredConfigParts()) {
                builder.addJoinConfigParts(toProtoConfigPart(part));
            }
        }
        return builder.build();
    }

    /**
     * Rebuilds a framework access unit from its wire form.
     *
     * @param streamKey the stream identity, implicit on the wire; required
     * @param accessUnit the received proto access unit; must not be null
     * @throws IllegalArgumentException if an argument is null or the wire payload is empty
     */
    public static EncodedAccessUnit toAccessUnit(StreamKey streamKey,
            com.obby.android.localscreenshare.grpc.screenstream.AccessUnit accessUnit) {
        if (streamKey == null) {
            throw new IllegalArgumentException("streamKey must not be null");
        }
        if (accessUnit == null) {
            throw new IllegalArgumentException("accessUnit must not be null");
        }
        long dtsUs = accessUnit.getHasDts()
                ? accessUnit.getDtsUs()
                : EncodedAccessUnit.DTS_UNSET;
        List<CodecConfigPart> joinParts = toConfigParts(accessUnit.getJoinConfigPartsList());
        JoinInfo joinInfo = (accessUnit.getJoinPoint() || !joinParts.isEmpty())
                ? new JoinInfo(accessUnit.getConfigEpoch(), joinParts)
                : null;
        Integer temporalLayerId = accessUnit.getHasTemporalLayerId()
                ? accessUnit.getTemporalLayerId()
                : null;
        AccessUnitFlags flags = AccessUnitFlags.of(accessUnit.getSyncPoint(),
                accessUnit.getJoinPoint(), accessUnit.getConfig());
        return new EncodedAccessUnit(streamKey, accessUnit.getConfigEpoch(),
                accessUnit.getSequence(), accessUnit.getPtsUs(), dtsUs, flags, joinInfo,
                temporalLayerId, accessUnit.getPayload().toByteArray());
    }

    /**
     * Maps an audio descriptor to its wire form.
     *
     * @param descriptor the published audio descriptor; must not be null
     * @throws IllegalArgumentException if {@code descriptor} is null
     */
    public static com.obby.android.localscreenshare.grpc.screenstream.AudioDescriptor
            toProtoAudioDescriptor(AudioDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor must not be null");
        }
        com.obby.android.localscreenshare.grpc.screenstream.AudioDescriptor.Builder builder =
                com.obby.android.localscreenshare.grpc.screenstream.AudioDescriptor.newBuilder()
                        .setProtocolVersion(PROTOCOL_VERSION)
                        .setConfigEpoch(descriptor.getConfigEpoch())
                        .setCodecId(descriptor.getCodecId())
                        .setMime(descriptor.getMime())
                        .setSampleRate(descriptor.getSampleRate())
                        .setChannels(descriptor.getChannels())
                        .setBitrateBps(descriptor.getBitrateBps())
                        .setFrameSamples(descriptor.getFrameSamples())
                        .setConfigHash(descriptor.getConfigHash());
        if (descriptor.getProfile() != null) {
            builder.setProfile(descriptor.getProfile());
        }
        for (CodecConfigPart part : descriptor.getConfigParts()) {
            builder.addConfigParts(toProtoConfigPart(part));
        }
        return builder.build();
    }

    /**
     * Rebuilds an audio descriptor from its wire form.
     *
     * @param descriptor the received proto descriptor; must not be null
     * @throws IllegalArgumentException if {@code descriptor} is null or the wire values are invalid
     */
    public static AudioDescriptor toAudioDescriptor(
            com.obby.android.localscreenshare.grpc.screenstream.AudioDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor must not be null");
        }
        AudioDescriptor.Builder builder = AudioDescriptor.builder(descriptor.getConfigEpoch(),
                        descriptor.getCodecId(), descriptor.getMime(), descriptor.getSampleRate(),
                        descriptor.getChannels(), descriptor.getBitrateBps())
                .profile(nullIfEmpty(descriptor.getProfile()))
                .frameSamples(descriptor.getFrameSamples())
                .configParts(toConfigParts(descriptor.getConfigPartsList()));
        return builder.build();
    }

    /**
     * Maps an audio access unit to its wire form.
     *
     * @throws IllegalArgumentException if {@code accessUnit} is null
     */
    public static com.obby.android.localscreenshare.grpc.screenstream.AudioAccessUnit
            toProtoAudioAccessUnit(AudioAccessUnit accessUnit) {
        if (accessUnit == null) {
            throw new IllegalArgumentException("accessUnit must not be null");
        }
        return com.obby.android.localscreenshare.grpc.screenstream.AudioAccessUnit.newBuilder()
                .setConfigEpoch(accessUnit.getConfigEpoch())
                .setSequence(accessUnit.getSequence())
                .setPtsUs(accessUnit.getPtsUs())
                .setPayload(com.google.protobuf.ByteString.copyFrom(accessUnit.getPayload()))
                .build();
    }

    /**
     * Rebuilds an audio access unit from its wire form.
     *
     * @param accessUnit the received proto access unit; must not be null
     * @throws IllegalArgumentException if {@code accessUnit} is null or its payload is empty
     */
    public static AudioAccessUnit toAudioAccessUnit(
            com.obby.android.localscreenshare.grpc.screenstream.AudioAccessUnit accessUnit) {
        if (accessUnit == null) {
            throw new IllegalArgumentException("accessUnit must not be null");
        }
        return new AudioAccessUnit(accessUnit.getConfigEpoch(), accessUnit.getSequence(),
                accessUnit.getPtsUs(), accessUnit.getPayload().toByteArray());
    }

    /**
     * Maps client capabilities to a wire capabilities message.
     *
     * <p>Each per-codec capability carries its profile, level and size/rate bounds; a zero bound or
     * a null profile/level means "unconstrained" and is simply omitted on the wire. Audio decoders
     * are written into the same message's {@code audio_codecs} list, so one capabilities message
     * describes both tracks.</p>
     */
    public static Capabilities toProtoCapabilities(ClientCapabilities capabilities) {
        Capabilities.Builder builder = Capabilities.newBuilder();
        if (capabilities != null) {
            for (CodecCapability capability : capabilities.getCapabilities().values()) {
                builder.addCodecs(toProtoCodecCapability(capability));
            }
            for (AudioCodecCapability capability : capabilities.getAudioCapabilities().values()) {
                builder.addAudioCodecs(toProtoAudioCodecCapability(capability));
            }
        }
        return builder.build();
    }

    /**
     * Rebuilds client capabilities from a wire capabilities message, or empty capabilities when
     * null. An old client sends no {@code audio_codecs} entries, so its audio map is empty.
     */
    public static ClientCapabilities toClientCapabilities(Capabilities capabilities) {
        Map<String, CodecCapability> map = new LinkedHashMap<>();
        Map<String, AudioCodecCapability> audioMap = new LinkedHashMap<>();
        if (capabilities != null) {
            for (com.obby.android.localscreenshare.grpc.screenstream.CodecCapability proto
                    : capabilities.getCodecsList()) {
                if (proto.getCodecId() != null && !proto.getCodecId().isEmpty()) {
                    map.put(proto.getCodecId(), toCodecCapability(proto));
                }
            }
            for (com.obby.android.localscreenshare.grpc.screenstream.AudioCodecCapability proto
                    : capabilities.getAudioCodecsList()) {
                if (proto.getCodecId() != null && !proto.getCodecId().isEmpty()) {
                    audioMap.put(proto.getCodecId(), toAudioCodecCapability(proto));
                }
            }
        }
        return new ClientCapabilities(map, audioMap);
    }

    private static com.obby.android.localscreenshare.grpc.screenstream.CodecCapability
            toProtoCodecCapability(CodecCapability capability) {
        com.obby.android.localscreenshare.grpc.screenstream.CodecCapability.Builder builder =
                com.obby.android.localscreenshare.grpc.screenstream.CodecCapability.newBuilder()
                        .setCodecId(capability.getCodecId())
                        .setMaxWidth(capability.getMaxWidth())
                        .setMaxHeight(capability.getMaxHeight())
                        .setMaxFrameRate(capability.getMaxFrameRate())
                        .setMaxBitrateBps(capability.getMaxBitrateBps())
                        .setSurfaceOutput(capability.isSurfaceOutput())
                        .setAdaptive(capability.isAdaptive());
        if (capability.getMime() != null) {
            builder.setMime(capability.getMime());
        }
        if (capability.getProfile() != null) {
            builder.setProfile(capability.getProfile());
        }
        if (capability.getLevel() != null) {
            builder.setLevel(capability.getLevel());
        }
        return builder.build();
    }

    private static CodecCapability toCodecCapability(
            com.obby.android.localscreenshare.grpc.screenstream.CodecCapability proto) {
        return new CodecCapability(proto.getCodecId(), nullIfEmpty(proto.getMime()),
                nullIfEmpty(proto.getProfile()), nullIfEmpty(proto.getLevel()),
                proto.getMaxWidth(), proto.getMaxHeight(), proto.getMaxFrameRate(),
                proto.getMaxBitrateBps(), proto.getSurfaceOutput(), proto.getAdaptive());
    }

    private static com.obby.android.localscreenshare.grpc.screenstream.AudioCodecCapability
            toProtoAudioCodecCapability(AudioCodecCapability capability) {
        com.obby.android.localscreenshare.grpc.screenstream.AudioCodecCapability.Builder builder =
                com.obby.android.localscreenshare.grpc.screenstream.AudioCodecCapability
                        .newBuilder()
                        .setCodecId(capability.getCodecId())
                        .setMaxSampleRate(capability.getMaxSampleRate())
                        .setMaxChannels(capability.getMaxChannels())
                        .setMaxBitrateBps(capability.getMaxBitrateBps());
        if (capability.getMime() != null) {
            builder.setMime(capability.getMime());
        }
        return builder.build();
    }

    private static AudioCodecCapability toAudioCodecCapability(
            com.obby.android.localscreenshare.grpc.screenstream.AudioCodecCapability proto) {
        return new AudioCodecCapability(proto.getCodecId(), nullIfEmpty(proto.getMime()),
                proto.getMaxSampleRate(), proto.getMaxChannels(), proto.getMaxBitrateBps());
    }

    /**
     * Dispatches one client message to the supplied listener based on its payload oneof.
     *
     * @throws IllegalArgumentException if {@code message} or {@code listener} is null, or the
     *                                  message carries no recognized payload
     */
    public static void dispatchClientMessage(ClientMessage message, ClientMessageListener listener) {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        switch (message.getPayloadCase()) {
            case HELLO:
                listener.onHello(message.getHello());
                break;
            case READY:
                listener.onReady(message.getReady());
                break;
            case REQUEST_KEYFRAME:
                listener.onRequestKeyframe(message.getRequestKeyframe());
                break;
            case PING:
                listener.onPing(message.getPing());
                break;
            case PAYLOAD_NOT_SET:
            default:
                throw new IllegalArgumentException("client message has no recognized payload");
        }
    }

    private static com.obby.android.localscreenshare.grpc.screenstream.ConfigPart toProtoConfigPart(
            CodecConfigPart part) {
        return com.obby.android.localscreenshare.grpc.screenstream.ConfigPart.newBuilder()
                .setId(part.getId())
                .setPayload(com.google.protobuf.ByteString.copyFrom(part.getPayload()))
                .build();
    }

    private static List<CodecConfigPart> toConfigParts(
            List<com.obby.android.localscreenshare.grpc.screenstream.ConfigPart> protoParts) {
        List<CodecConfigPart> parts = new ArrayList<>(protoParts.size());
        for (com.obby.android.localscreenshare.grpc.screenstream.ConfigPart part : protoParts) {
            parts.add(new CodecConfigPart(part.getId(), part.getPayload().toByteArray()));
        }
        return parts;
    }

    private static com.obby.android.localscreenshare.grpc.screenstream.ColorInfo toProtoColor(
            com.obby.android.localscreenshare.core.model.ColorInfo color) {
        com.obby.android.localscreenshare.grpc.screenstream.ColorInfo.Builder builder =
                com.obby.android.localscreenshare.grpc.screenstream.ColorInfo.newBuilder()
                        .setFullRange(color.isFullRange());
        if (color.getColorSpace() != null) {
            builder.setColorSpace(color.getColorSpace());
        }
        if (color.getTransferFunction() != null) {
            builder.setTransferFunction(color.getTransferFunction());
        }
        return builder.build();
    }

    private static com.obby.android.localscreenshare.core.model.ColorInfo toColor(
            com.obby.android.localscreenshare.grpc.screenstream.ColorInfo color) {
        if (color == null) {
            return com.obby.android.localscreenshare.core.model.ColorInfo.UNSPECIFIED;
        }
        return new com.obby.android.localscreenshare.core.model.ColorInfo(
                nullIfEmpty(color.getColorSpace()),
                nullIfEmpty(color.getTransferFunction()),
                color.getFullRange());
    }

    private static String nullIfEmpty(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
