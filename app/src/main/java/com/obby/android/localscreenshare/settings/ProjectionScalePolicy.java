package com.obby.android.localscreenshare.settings;

/**
 * Decides the lowest capture percentage that is usable on the current display and encoder.
 *
 * <p><b>Why.</b> A capture percentage is turned into a size by multiplying both edges of the
 * window bounds, and the encoder only accepts sizes inside its advertised range. The 2026-10-21
 * device test offered 11%, 10% and 5% on a 1080x2340 display; every one of them produced a size
 * below the hardware encoder's 144x144 minimum, so all three were refused and written back. The
 * settings sheet now raises its lower bound instead of offering percentages the encoder cannot
 * take.</p>
 *
 * <p><b>Reference edge.</b> The percentage scales width and height independently, so the binding
 * constraint is whichever edge yields the higher percentage: {@code max(ceil(100 * minWidth /
 * boundsWidth), ceil(100 * minHeight / boundsHeight))}, which is the same as dividing by the
 * shorter edge. Writing it this way keeps the rule correct when the display rotates: a rotation
 * swaps the two bounds and the two terms with them, so portrait and landscape produce the same
 * floor. The rule is pure integer arithmetic with no Android type, so it is unit-tested on the
 * JVM.</p>
 *
 * <p><b>Fallback.</b> When the encoder capabilities or the window bounds are unknown, the floor
 * stays at {@link ProjectionSettings#MIN_SCALE_PERCENT}, the value every encoder in the field has
 * accepted so far. The floor never exceeds {@link ProjectionSettings#MAX_SCALE_PERCENT}: a window
 * smaller than the encoder minimum leaves no usable percentage at all, and reporting 101% would
 * only break the slider range.</p>
 */
public final class ProjectionScalePolicy {

    private ProjectionScalePolicy() {
    }

    /**
     * Returns the lowest usable capture percentage for the given encoder minimum and window bounds.
     *
     * @param encoderMinimumWidth  smallest width the encoder accepts, in pixels; non-positive when
     *                             the capability is unknown
     * @param encoderMinimumHeight smallest height the encoder accepts, in pixels; non-positive
     *                             when the capability is unknown
     * @param windowWidth          width the percentage is applied to, in pixels; non-positive when
     *                             the bounds are unknown
     * @param windowHeight         height the percentage is applied to, in pixels; non-positive
     *                             when the bounds are unknown
     * @param stepPercent          slider step in percent; non-positive values are treated as 1
     * @return a percentage in {@code [MIN_SCALE_PERCENT, MAX_SCALE_PERCENT]} that is a multiple of
     *     {@code stepPercent} counted from {@code MIN_SCALE_PERCENT}
     */
    public static int getMinimumPercent(final int encoderMinimumWidth,
                                        final int encoderMinimumHeight,
                                        final int windowWidth, final int windowHeight,
                                        final int stepPercent) {
        if (encoderMinimumWidth <= 0 || encoderMinimumHeight <= 0
            || windowWidth <= 0 || windowHeight <= 0) {
            return ProjectionSettings.MIN_SCALE_PERCENT;
        }

        final int step = stepPercent > 0 ? stepPercent : 1;
        final long required = Math.max(
            ceilPercent(encoderMinimumWidth, windowWidth),
            ceilPercent(encoderMinimumHeight, windowHeight));
        final long roundedUp = (required + step - 1) / step * step;
        final long clamped = Math.max(ProjectionSettings.MIN_SCALE_PERCENT,
            Math.min(ProjectionSettings.MAX_SCALE_PERCENT, roundedUp));
        return (int) clamped;
    }

    /**
     * Returns the smallest whole percentage of {@code available} that is at least {@code minimum}.
     */
    private static long ceilPercent(final int minimum, final int available) {
        return (100L * minimum + available - 1) / available;
    }
}
