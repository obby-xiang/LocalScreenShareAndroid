package com.obby.android.localscreenshare.support;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.util.Range;

import com.obby.android.localscreenshare.core.codec.CodecIds;
import com.obby.android.localscreenshare.core.codec.h264.H264Codec;
import com.obby.android.localscreenshare.core.stream.ClientCapabilities;
import com.obby.android.localscreenshare.core.stream.CodecCapability;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime codec capability probing over {@link MediaCodecList}.
 *
 * <p>Capabilities are discovered from the device's codec list, never inferred from the API level or
 * the device form factor. Probing is best-effort: any failure to query a codec leaves that codec
 * out of the result rather than guessing a value. The reported capability is the decoder's most
 * capable configuration; profile and level are left unspecified for now.</p>
 */
public final class CodecCapabilityProbe {

    private static final String JPEG_MIME = "image/jpeg";

    private CodecCapabilityProbe() {
    }

    /**
     * Probes the device's decoders for the codecs this client can receive, for the HELLO message.
     *
     * <p>H.264 is the compatibility baseline and JPEG is the fallback; each is included only when a
     * decoder is actually present.</p>
     */
    public static ClientCapabilities probeClientDecoders() {
        Map<String, CodecCapability> capabilities = new LinkedHashMap<>();
        probeDecoder(capabilities, CodecIds.H264, H264Codec.MIME);
        probeDecoder(capabilities, CodecIds.JPEG, JPEG_MIME);
        return new ClientCapabilities(capabilities);
    }

    /** Returns true when the device advertises at least one encoder for {@code mime}. */
    public static boolean hasEncoder(String mime) {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (!info.isEncoder()) {
                    continue;
                }
                try {
                    info.getCapabilitiesForType(mime);
                    return true;
                } catch (IllegalArgumentException ignored) {
                    // this codec does not encode mime
                }
            }
        } catch (RuntimeException ignored) {
            // probing failure means "not proven available"
        }
        return false;
    }

    private static void probeDecoder(Map<String, CodecCapability> capabilities, String codecId,
                                     String mime) {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (info.isEncoder()) {
                    continue;
                }
                MediaCodecInfo.CodecCapabilities codecCapabilities;
                try {
                    codecCapabilities = info.getCapabilitiesForType(mime);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                android.media.MediaCodecInfo.VideoCapabilities video =
                        codecCapabilities.getVideoCapabilities();
                if (video == null) {
                    continue;
                }
                Range<Integer> widths = video.getSupportedWidths();
                Range<Integer> heights = video.getSupportedHeights();
                Range<Integer> frameRates = video.getSupportedFrameRates();
                Range<Integer> bitrates = video.getBitrateRange();
                capabilities.put(codecId, new CodecCapability(codecId, mime, null, null,
                        widths == null ? 0 : widths.getUpper(),
                        heights == null ? 0 : heights.getUpper(),
                        frameRates == null ? 0 : frameRates.getUpper(),
                        bitrates == null ? 0 : bitrates.getUpper(),
                        true, codecCapabilities.isFeatureSupported(
                                MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback)));
                return;
            }
        } catch (RuntimeException ignored) {
            // probing failure means "not proven available"
        }
    }
}
