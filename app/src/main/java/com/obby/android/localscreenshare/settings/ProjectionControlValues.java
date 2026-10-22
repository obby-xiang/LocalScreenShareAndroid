package com.obby.android.localscreenshare.settings;

/**
 * Values the video settings sheet displays for a stored {@link ProjectionSettings} snapshot.
 *
 * <p><b>Why.</b> The sheet used to apply the stored values only when it was opened. The service
 * writes the applied capture percentage back when the encoder rejects a size, and that write landed
 * in the preferences while the sheet stayed open, so the slider kept showing the percentage the
 * user had just dragged to until the sheet was closed and reopened. The controls are now refreshed
 * on every projection preference change, which means the mapping from stored values to slider
 * values has to be total: every result is inside the slider range and on its step, so applying it
 * cannot throw and cannot be rejected by the widget.</p>
 *
 * <p><b>Clamping.</b> Two different limits meet here. {@link ProjectionSettings} clamps to the range
 * the encoder side accepts at all, and the sheet additionally raises the lower capture percentage to
 * the minimum the current display and encoder can use (see {@link ProjectionScalePolicy}). A value
 * stored by another version below that floor is displayed at the floor, because the floor is the
 * lowest value the user can still choose. The rule is pure arithmetic, so it is unit-tested on the
 * JVM.</p>
 */
public final class ProjectionControlValues {

    /**
     * Capture percentage slider step.
     */
    public static final int SCALE_STEP_PERCENT = 1;

    /**
     * Frame rate slider step, in frames per second.
     */
    public static final int FRAME_RATE_STEP = 1;

    /**
     * Lowest bitrate the slider offers, in megabits per second.
     */
    public static final float MIN_BITRATE_MBPS = 0.5f;

    /**
     * Highest bitrate the slider offers, in megabits per second.
     */
    public static final float MAX_BITRATE_MBPS = 20f;

    /**
     * Bitrate slider step, in megabits per second.
     */
    public static final float BITRATE_STEP_MBPS = 0.5f;

    /**
     * Key-frame interval slider step, in seconds.
     */
    public static final float IDR_INTERVAL_STEP_SECONDS = 1f;

    /**
     * JPEG quality slider step.
     */
    public static final int QUALITY_STEP = 1;

    private final int scalePercent;
    private final int frameRate;
    private final float bitrateMbps;
    private final float idrIntervalSeconds;
    private final int jpegQuality;
    private final boolean h264;

    private ProjectionControlValues(final int scalePercent, final int frameRate,
                                    final float bitrateMbps, final float idrIntervalSeconds,
                                    final int jpegQuality, final boolean h264) {
        this.scalePercent = scalePercent;
        this.frameRate = frameRate;
        this.bitrateMbps = bitrateMbps;
        this.idrIntervalSeconds = idrIntervalSeconds;
        this.jpegQuality = jpegQuality;
        this.h264 = h264;
    }

    /**
     * Maps a stored snapshot onto the values the video settings controls display.
     *
     * <p>The capture percentage is raised to {@code minimumScalePercent}, the bitrate is snapped to
     * the slider step and clamped to the slider range, and the key-frame interval is expressed in
     * whole seconds. Frame rate and quality already come clamped out of {@link ProjectionSettings}
     * and are passed through.</p>
     *
     * @param settings            stored projection settings; never null
     * @param minimumScalePercent lowest capture percentage the display and encoder allow
     * @return an immutable snapshot of the displayed values; never null
     */
    public static ProjectionControlValues of(final ProjectionSettings settings,
                                             final int minimumScalePercent) {
        final int floor = clamp(minimumScalePercent,
            ProjectionSettings.MIN_SCALE_PERCENT, ProjectionSettings.MAX_SCALE_PERCENT);
        return new ProjectionControlValues(
            Math.max(floor, settings.getScalePercent()),
            settings.getFrameRate(),
            snap(clamp(settings.getBitrateBps() / 1_000_000f,
                MIN_BITRATE_MBPS, MAX_BITRATE_MBPS), MIN_BITRATE_MBPS, BITRATE_STEP_MBPS),
            snap(clamp(settings.getIdrIntervalMs() / 1000f,
                    ProjectionSettings.MIN_IDR_INTERVAL_MS / 1000f,
                    ProjectionSettings.MAX_IDR_INTERVAL_MS / 1000f),
                ProjectionSettings.MIN_IDR_INTERVAL_MS / 1000f, IDR_INTERVAL_STEP_SECONDS),
            settings.getJpegQuality(),
            settings.isH264());
    }

    /**
     * Returns the capture percentage to display, at least the current usable minimum.
     */
    public int getScalePercent() {
        return scalePercent;
    }

    /**
     * Returns the frame rate to display, in frames per second.
     */
    public int getFrameRate() {
        return frameRate;
    }

    /**
     * Returns the bitrate to display, in megabits per second, on the slider step.
     */
    public float getBitrateMbps() {
        return bitrateMbps;
    }

    /**
     * Returns the key-frame interval to display, in seconds, on the slider step.
     */
    public float getIdrIntervalSeconds() {
        return idrIntervalSeconds;
    }

    /**
     * Returns the JPEG quality to display.
     */
    public int getJpegQuality() {
        return jpegQuality;
    }

    /**
     * Returns whether the stream is encoded as H.264.
     */
    public boolean isH264() {
        return h264;
    }

    /**
     * Returns whether the bitrate control applies to the selected codec.
     *
     * <p>The JPEG path ignores encoder parameters: {@code JpegEncoder.updateParameters} is a no-op
     * because the capture side already produced a complete image, so a bitrate has no effect there.
     * The sheet hides the row instead of offering a control that does nothing.</p>
     */
    public boolean isBitrateApplicable() {
        return h264;
    }

    /**
     * Returns whether the key-frame interval control applies to the selected codec.
     *
     * <p>Every JPEG frame is independently decodable, so that path has no key-frame interval to
     * request; the sheet hides the row for it.</p>
     */
    public boolean isIdrIntervalApplicable() {
        return h264;
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(final float value, final float min, final float max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Rounds {@code value} to the nearest multiple of {@code step} counted from {@code origin}.
     */
    private static float snap(final float value, final float origin, final float step) {
        return origin + Math.round((value - origin) / step) * step;
    }
}
