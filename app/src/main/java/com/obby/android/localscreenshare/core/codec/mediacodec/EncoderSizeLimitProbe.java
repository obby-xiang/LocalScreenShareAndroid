package com.obby.android.localscreenshare.core.codec.mediacodec;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.util.Range;
import android.util.Size;

import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.core.codec.EncoderSizeAlignment;

/**
 * Reads the smallest capture size an encoder advertises over {@link MediaCodecList}.
 *
 * <p><b>Why probe.</b> The 2026-10-21 device test set the capture percentage to 11%, 10% and 5% on
 * a 1080x2340 display. Every attempt produced a size below the hardware encoder's
 * {@code [144x144, 4096x4096]} range: {@code VENC: ... should between [144 X 144] [4096 X 4096]},
 * so the encoder refused the configuration and the failure path had to write the percentage back.
 * The percentage itself is computed from the window bounds, so only the encoder knows which of the
 * offered percentages is usable; the settings sheet asks this probe instead of assuming a value.</p>
 *
 * <p><b>Best effort.</b> The codec list is walked in platform preference order and the first
 * encoder that supports the media type answers, which is the codec
 * {@code MediaCodec.createEncoderByType} is expected to pick. The advertised minimum is raised to
 * the encoder's own size alignment, because the capture size is aligned down before it reaches the
 * encoder and a minimum that is not a multiple of the alignment would otherwise be undershot. Any
 * failure — no encoder, no video capabilities, an exception from the platform — returns
 * {@code null}, and the caller keeps its fallback percentage instead of guessing.</p>
 */
public final class EncoderSizeLimitProbe {

    private EncoderSizeLimitProbe() {
    }

    /**
     * Returns the smallest size the encoder for {@code mime} accepts, rounded up to its alignment.
     *
     * @param mime media type of the encoder, for example {@code video/avc}
     * @return the advertised minimum size, or {@code null} when it cannot be determined
     */
    @Nullable
    public static Size probeMinimumSize(final String mime) {
        if (mime == null || mime.trim().isEmpty()) {
            return null;
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
                final Range<Integer> widths = video.getSupportedWidths();
                final Range<Integer> heights = video.getSupportedHeights();
                if (widths == null || heights == null) {
                    continue;
                }
                final int widthAlignment = Math.max(1, video.getWidthAlignment());
                final int heightAlignment = Math.max(1, video.getHeightAlignment());
                final EncoderSizeAlignment alignment =
                    EncoderSizeAlignment.of(widthAlignment, heightAlignment);
                return new Size(
                    alignment.alignWidthUp(widths.getLower()),
                    alignment.alignHeightUp(heights.getLower()));
            }
        } catch (RuntimeException e) {
            // A platform that refuses to enumerate its codecs is treated as "minimum unknown".
            return null;
        }
        return null;
    }
}
