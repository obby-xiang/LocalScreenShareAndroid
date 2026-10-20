package com.obby.android.localscreenshare.core.codec.mediacodec;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;

import com.obby.android.localscreenshare.core.codec.EncoderSizeAlignment;

/**
 * Reads the size alignment an encoder advertises over {@link MediaCodecList}.
 *
 * <p><b>Why probe instead of assuming even sizes.</b> Most encoders accept any even width and
 * height, but some consumers and older hardware only accept multiples of four, eight or sixteen.
 * The alignment therefore comes from the same capability source a caller would use to decide
 * whether the encoder exists at all, and the capture size is aligned with exactly that value, so
 * the probe and the encoding size cannot drift apart.</p>
 *
 * <p><b>Best effort.</b> The codec list is walked in platform preference order and the first
 * encoder that supports the media type answers, which is the codec
 * {@code MediaCodec.createEncoderByType} is expected to pick. Any failure — no encoder, no video
 * capabilities, an exception from the platform — falls back to {@link EncoderSizeAlignment#EVEN}
 * instead of guessing a larger step, because a wrong alignment would shrink the capture for no
 * reason while an even size is accepted everywhere the encoder accepts anything.</p>
 */
public final class EncoderAlignmentProbe {

    private EncoderAlignmentProbe() {
    }

    /**
     * Returns the size alignment of the encoder that would encode {@code mime}.
     *
     * @param mime media type of the encoder, for example {@code video/avc}
     * @return the advertised alignment, or {@link EncoderSizeAlignment#EVEN} when it is unknown
     */
    public static EncoderSizeAlignment probe(final String mime) {
        if (mime == null || mime.trim().isEmpty()) {
            return EncoderSizeAlignment.EVEN;
        }
        try {
            for (final MediaCodecInfo info
                : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!info.isEncoder()) {
                    continue;
                }
                final MediaCodecInfo.CodecCapabilities capabilities;
                try {
                    capabilities = info.getCapabilitiesForType(mime);
                } catch (IllegalArgumentException e) {
                    // This encoder does not encode the media type; try the next one.
                    continue;
                }
                final MediaCodecInfo.VideoCapabilities video = capabilities.getVideoCapabilities();
                if (video == null) {
                    continue;
                }
                final int widthAlignment = video.getWidthAlignment();
                final int heightAlignment = video.getHeightAlignment();
                if (widthAlignment > 0 && heightAlignment > 0) {
                    return EncoderSizeAlignment.of(widthAlignment, heightAlignment);
                }
            }
        } catch (RuntimeException e) {
            // A platform that refuses to enumerate its codecs is treated as "alignment unknown".
            return EncoderSizeAlignment.EVEN;
        }
        return EncoderSizeAlignment.EVEN;
    }
}
