package com.obby.android.localscreenshare.core.stream;

/**
 * Maps the platform thermal status onto the capture parameters a running share may use.
 *
 * <p><b>Why a temporary override.</b> The user's frame rate and capture scale are their choice and
 * stay stored untouched; a hot device only lowers the values that are actually applied, and the
 * stored preferences become effective again as soon as the device cools down. The override is
 * therefore a function of the current status, never a write to the preferences.</p>
 *
 * <p><b>Ladder.</b> Only the top of the platform scale is acted on. {@code LIGHT} and
 * {@code MODERATE} are the band in which the platform itself still runs normally, so lowering the
 * stream there would trade quality for nothing. {@code SEVERE} means the device is about to be
 * throttled hard and the frame-rate hint is lowered; {@code CRITICAL} and above mean the device is
 * already limiting hardware and the capture area is halved as well, which removes both composition
 * and encoder pixels. The status values mirror {@code PowerManager} so this class stays free of
 * Android types and its ladder is covered by JVM tests.</p>
 *
 * <p><b>Unknown status.</b> A negative or future value is treated as {@link Tier#NONE}: an
 * unrecognised status must not silently degrade the picture. Below API 29 the platform exposes no
 * status at all, which reaches this class as {@link #STATUS_NONE}.</p>
 */
public final class ThermalThrottlePolicy {

    /**
     * Platform value: no thermal pressure.
     */
    public static final int STATUS_NONE = 0;

    /**
     * Platform value: light thermal pressure.
     */
    public static final int STATUS_LIGHT = 1;

    /**
     * Platform value: moderate thermal pressure.
     */
    public static final int STATUS_MODERATE = 2;

    /**
     * Platform value: severe thermal pressure, the platform starts throttling.
     */
    public static final int STATUS_SEVERE = 3;

    /**
     * Platform value: critical thermal pressure.
     */
    public static final int STATUS_CRITICAL = 4;

    /**
     * Platform value: emergency thermal pressure.
     */
    public static final int STATUS_EMERGENCY = 5;

    /**
     * Platform value: the device is about to shut down.
     */
    public static final int STATUS_SHUTDOWN = 6;

    /**
     * Frame-rate cap of the reduced and the minimal tier, in frames per second.
     */
    public static final int THROTTLED_FRAME_RATE_LIMIT = 30;

    /**
     * Capture-scale cap of the minimal tier, in percent.
     */
    public static final int MINIMAL_SCALE_LIMIT_PERCENT = 50;

    /**
     * Capture parameters the current thermal status allows.
     */
    public enum Tier {

        /**
         * The user's settings apply unchanged.
         */
        NONE,

        /**
         * The frame rate is capped; the capture area is unchanged.
         */
        REDUCED,

        /**
         * The frame rate is capped and the capture area is halved.
         */
        MINIMAL
    }

    private ThermalThrottlePolicy() {
    }

    /**
     * Returns the tier for a platform thermal status.
     *
     * @param thermalStatus status as reported by the platform, or {@link #STATUS_NONE}
     * @return the tier to apply; never null
     */
    public static Tier tierFor(int thermalStatus) {
        switch (thermalStatus) {
            case STATUS_SEVERE:
                return Tier.REDUCED;
            case STATUS_CRITICAL:
            case STATUS_EMERGENCY:
            case STATUS_SHUTDOWN:
                return Tier.MINIMAL;
            default:
                return Tier.NONE;
        }
    }

    /**
     * Returns the stricter of two tiers.
     *
     * <p>The thermal status and the headroom forecast are independent signals; merging them may
     * only tighten the ladder, never relax it. A lower forecast therefore cannot undo a status
     * that already demands throttling, and the result is always at least as strict as either
     * input. The tier order is the ladder order, so the comparison is the enum's ordinal.</p>
     *
     * @param a one tier; required
     * @param b another tier; required
     * @return {@code a} or {@code b}, whichever is stricter
     */
    public static Tier stricter(final Tier a, final Tier b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    /**
     * Applies the tier's frame-rate cap to a requested frame rate.
     *
     * @param tier   tier to apply; required
     * @param requestedFrameRate frame rate the user asked for
     * @return a value between one and {@code requestedFrameRate}
     */
    public static int capFrameRate(Tier tier, int requestedFrameRate) {
        final int requested = Math.max(1, requestedFrameRate);
        return tier == Tier.NONE ? requested
                : Math.min(requested, THROTTLED_FRAME_RATE_LIMIT);
    }

    /**
     * Applies the tier's capture-scale cap to a requested capture percentage.
     *
     * @param tier   tier to apply; required
     * @param requestedScalePercent capture percentage the user asked for
     * @return a value between one and {@code requestedScalePercent}
     */
    public static int capScalePercent(Tier tier, int requestedScalePercent) {
        final int requested = Math.max(1, requestedScalePercent);
        return tier == Tier.MINIMAL
                ? Math.min(requested, MINIMAL_SCALE_LIMIT_PERCENT)
                : requested;
    }
}
