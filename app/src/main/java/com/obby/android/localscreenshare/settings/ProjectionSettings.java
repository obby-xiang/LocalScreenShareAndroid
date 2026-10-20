package com.obby.android.localscreenshare.settings;

import com.obby.android.localscreenshare.core.codec.CodecIds;
import com.obby.android.localscreenshare.core.model.StreamParameters;

/**
 * Validated snapshot of the encoder-side projection preferences.
 *
 * <p>The stored preferences are plain integers and strings that may have been written by another
 * version, so every value is clamped or validated here instead of at each use site. Invalid input
 * falls back to the default and never throws: a settings screen that refuses to open because one
 * stored value is out of range is worse than one that silently uses the default. The snapshot also
 * owns the mapping to {@link StreamParameters}, so the service does not have to reassemble the
 * three fields by hand.</p>
 *
 * <p><b>Key-frame interval.</b> The encoder converts the interval to whole seconds and rounds up,
 * so a sub-second value would silently become one second. The snapshot therefore rounds up to a
 * whole second itself, which makes the value the user sees identical to the value the encoder
 * uses.</p>
 */
public final class ProjectionSettings {

    /**
     * Lowest accepted bitrate, in bits per second.
     */
    public static final int MIN_BITRATE_BPS = 100_000;

    /**
     * Highest accepted bitrate, in bits per second.
     */
    public static final int MAX_BITRATE_BPS = 50_000_000;

    /**
     * Lowest accepted frame rate, in frames per second.
     */
    public static final int MIN_FRAME_RATE = 1;

    /**
     * Highest accepted frame rate, in frames per second.
     */
    public static final int MAX_FRAME_RATE = 120;

    /**
     * Lowest accepted key-frame interval, in milliseconds.
     */
    public static final int MIN_IDR_INTERVAL_MS = 1_000;

    /**
     * Highest accepted key-frame interval, in milliseconds.
     */
    public static final int MAX_IDR_INTERVAL_MS = 30_000;

    /**
     * Lowest accepted capture scale, in percent.
     */
    public static final int MIN_SCALE_PERCENT = 5;

    /**
     * Highest accepted capture scale, in percent.
     */
    public static final int MAX_SCALE_PERCENT = 100;

    /**
     * Lowest accepted JPEG quality.
     */
    public static final int MIN_JPEG_QUALITY = 0;

    /**
     * Highest accepted JPEG quality.
     */
    public static final int MAX_JPEG_QUALITY = 100;

    /**
     * Default bitrate, in bits per second.
     */
    public static final int DEFAULT_BITRATE_BPS = 4_000_000;

    /**
     * Default frame rate, in frames per second.
     */
    public static final int DEFAULT_FRAME_RATE = 60;

    /**
     * Default key-frame interval, in milliseconds.
     */
    public static final int DEFAULT_IDR_INTERVAL_MS = 1_000;

    /**
     * Default capture scale, in percent.
     */
    public static final int DEFAULT_SCALE_PERCENT = 100;

    /**
     * Default JPEG quality.
     */
    public static final int DEFAULT_JPEG_QUALITY = 85;

    /**
     * Interval the key-frame spacing is rounded to, in milliseconds.
     */
    private static final int IDR_ROUNDING_MS = 1_000;

    private final int bitrateBps;
    private final int frameRate;
    private final int idrIntervalMs;
    private final int scalePercent;
    private final int jpegQuality;
    private final String codecId;

    private ProjectionSettings(int bitrateBps, int frameRate, int idrIntervalMs,
                               int scalePercent, int jpegQuality, String codecId) {
        this.bitrateBps = bitrateBps;
        this.frameRate = frameRate;
        this.idrIntervalMs = idrIntervalMs;
        this.scalePercent = scalePercent;
        this.jpegQuality = jpegQuality;
        this.codecId = codecId;
    }

    /**
     * Validates the stored values and builds a snapshot.
     *
     * <p>Null or blank codec ids and ids this build cannot encode fall back to H.264. The key-frame
     * interval is clamped to the accepted range and rounded up to whole seconds.</p>
     *
     * @param bitrateBps   target bitrate in bits per second
     * @param frameRate    frame-rate hint in frames per second
     * @param idrIntervalMs key-frame interval in milliseconds
     * @param scalePercent capture scale in percent
     * @param jpegQuality  JPEG quality, used only by the JPEG capture path
     * @param codecId      stored codec id; null means the default
     * @return an immutable snapshot; never null
     */
    public static ProjectionSettings of(int bitrateBps, int frameRate, int idrIntervalMs,
                                        int scalePercent, int jpegQuality, String codecId) {
        return new ProjectionSettings(
                clamp(bitrateBps, MIN_BITRATE_BPS, MAX_BITRATE_BPS),
                clamp(frameRate, MIN_FRAME_RATE, MAX_FRAME_RATE),
                roundIdrInterval(idrIntervalMs),
                clamp(scalePercent, MIN_SCALE_PERCENT, MAX_SCALE_PERCENT),
                clamp(jpegQuality, MIN_JPEG_QUALITY, MAX_JPEG_QUALITY),
                normalizeCodecId(codecId));
    }

    /**
     * Returns the validated target bitrate in bits per second.
     */
    public int getBitrateBps() {
        return bitrateBps;
    }

    /**
     * Returns the validated frame-rate hint in frames per second.
     */
    public int getFrameRate() {
        return frameRate;
    }

    /**
     * Returns the validated key-frame interval in milliseconds; a multiple of one second.
     */
    public int getIdrIntervalMs() {
        return idrIntervalMs;
    }

    /**
     * Returns the validated capture scale in percent.
     */
    public int getScalePercent() {
        return scalePercent;
    }

    /**
     * Returns the validated JPEG quality.
     */
    public int getJpegQuality() {
        return jpegQuality;
    }

    /**
     * Returns the validated codec id, either {@link CodecIds#H264} or {@link CodecIds#JPEG}.
     */
    public String getCodecId() {
        return codecId;
    }

    /**
     * Returns whether the stream is encoded as H.264.
     */
    public boolean isH264() {
        return CodecIds.H264.equals(codecId);
    }

    /**
     * Maps the snapshot onto the rate and cadence hints the stream runtime accepts.
     */
    public StreamParameters toStreamParameters() {
        return new StreamParameters(bitrateBps, frameRate, idrIntervalMs);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int roundIdrInterval(int intervalMs) {
        final int clamped = Math.max(MIN_IDR_INTERVAL_MS,
                Math.min(MAX_IDR_INTERVAL_MS, intervalMs));
        final int rounded = (clamped + IDR_ROUNDING_MS - 1) / IDR_ROUNDING_MS * IDR_ROUNDING_MS;
        return Math.min(MAX_IDR_INTERVAL_MS, rounded);
    }

    private static String normalizeCodecId(String codecId) {
        if (codecId != null) {
            final String normalized = codecId.trim().toLowerCase(java.util.Locale.ROOT);
            if (CodecIds.JPEG.equals(normalized)) {
                return CodecIds.JPEG;
            }
        }
        return CodecIds.H264;
    }
}
