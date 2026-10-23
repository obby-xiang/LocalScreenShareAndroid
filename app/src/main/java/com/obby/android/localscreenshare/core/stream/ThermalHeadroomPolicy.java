package com.obby.android.localscreenshare.core.stream;

import com.obby.android.localscreenshare.core.stream.ThermalThrottlePolicy.Tier;

/**
 * Turns the platform's predicted thermal pressure into a thermal tier with hysteresis.
 *
 * <p><b>Reading direction.</b> {@code PowerManager.getThermalHeadroom(forecastSeconds)} returns a
 * thermal pressure ratio, not a remaining margin: {@code 1.0} means the platform starts throttling,
 * values above {@code 1.0} mean the device is already throttled, and a larger reading is hotter.
 * The class therefore enters a stricter tier as the ratio grows; the metric records the same ratio
 * in permille.</p>
 *
 * <p><b>Two thresholds per step.</b> Entering {@link Tier#REDUCED} needs 0.70 and leaving it needs
 * 0.60; entering {@link Tier#MINIMAL} needs 0.85 and leaving it needs 0.78. The gap is hysteresis:
 * a reading that hovers on a boundary must not toggle the capture parameters on every sample. All
 * four values are starting points pending device calibration, so they live here as named constants
 * rather than as inline numbers.</p>
 *
 * <p><b>Rounding.</b> A sample is converted with {@code Math.round(headroom * 1000f)}. A cast must
 * not be used: {@code 0.70f} is {@code 0.69999998...}, so truncation yields 699 and the 700
 * threshold would never be reached at the documented boundary.</p>
 *
 * <p><b>Invalid samples.</b> NaN and ratios outside {@code [0, MAX_VALID_HEADROOM]} are ignored and
 * leave the tier unchanged; they are not clamped into the valid range. The upper bound exists only
 * to reject garbage: a valid reading above {@code 1.0} is used as-is, because it truthfully says the
 * device is already throttled. The class allocates nothing and holds no lock, so the polling path
 * can call it directly.</p>
 */
public final class ThermalHeadroomPolicy {

    /**
     * Shortest allowed distance between two samples, in milliseconds.
     *
     * <p>The platform asks for at most one reading per second; the poll keeps that bound.</p>
     */
    public static final long POLL_INTERVAL_MS = 1000L;

    /**
     * Forecast window passed to the platform, in seconds.
     */
    public static final int FORECAST_SECONDS = 10;

    /**
     * Highest ratio accepted as a sample; anything above it is treated as garbage.
     *
     * <p>A valid sample is never clamped: {@code 1.0} already means throttling and {@code 2.0}
     * means heavily throttled.</p>
     */
    public static final float MAX_VALID_HEADROOM = 2.0f;

    /**
     * Thermal pressure that enters {@link Tier#REDUCED}, in permille; pending device calibration.
     */
    public static final int ENTER_REDUCED_PERMILLE = 700;

    /**
     * Thermal pressure that enters {@link Tier#MINIMAL}, in permille; pending device calibration.
     */
    public static final int ENTER_MINIMAL_PERMILLE = 850;

    /**
     * Thermal pressure that leaves {@link Tier#MINIMAL} downwards, in permille; pending device
     * calibration.
     */
    public static final int EXIT_MINIMAL_PERMILLE = 780;

    /**
     * Thermal pressure that leaves {@link Tier#REDUCED} downwards, in permille; pending device
     * calibration.
     */
    public static final int EXIT_REDUCED_PERMILLE = 600;

    private Tier mTier = Tier.NONE;

    /**
     * Tells whether a reading is a sample this policy accepts.
     *
     * <p>The rule lives here, next to {@link #MAX_VALID_HEADROOM}, so a caller that also has to
     * decide whether to publish the reading never restates the domain. A rejected reading leaves
     * the tier unchanged; it is not clamped.</p>
     *
     * @param headroom thermal pressure ratio as reported by the platform
     * @return true when the reading is inside {@code [0, MAX_VALID_HEADROOM]} and not NaN
     */
    public static boolean isValid(final float headroom) {
        return !Float.isNaN(headroom) && headroom >= 0f && headroom <= MAX_VALID_HEADROOM;
    }

    /**
     * Samples one thermal pressure ratio and returns the tier in force after it.
     *
     * <p>NaN and out-of-range values are ignored and leave the current tier unchanged. A valid
     * value may tighten the tier immediately, but only a reading below the matching exit threshold
     * relaxes it.</p>
     *
     * @param headroom thermal pressure ratio as reported by the platform; NaN when unavailable
     * @return the tier in force after this sample; never null
     */
    public Tier onSample(final float headroom) {
        if (!isValid(headroom)) {
            return mTier;
        }
        final int permille = Math.round(headroom * 1000f);
        switch (mTier) {
            case NONE:
                if (permille >= ENTER_MINIMAL_PERMILLE) {
                    mTier = Tier.MINIMAL;
                } else if (permille >= ENTER_REDUCED_PERMILLE) {
                    mTier = Tier.REDUCED;
                }
                break;
            case REDUCED:
                if (permille >= ENTER_MINIMAL_PERMILLE) {
                    mTier = Tier.MINIMAL;
                } else if (permille < EXIT_REDUCED_PERMILLE) {
                    mTier = Tier.NONE;
                }
                break;
            case MINIMAL:
                if (permille < EXIT_REDUCED_PERMILLE) {
                    mTier = Tier.NONE;
                } else if (permille < EXIT_MINIMAL_PERMILLE) {
                    mTier = Tier.REDUCED;
                }
                break;
            default:
                break;
        }
        return mTier;
    }

    /**
     * Returns the tier the last valid sample left in force.
     *
     * @return the current tier; never null
     */
    public Tier getTier() {
        return mTier;
    }

    /**
     * Forgets the forecast, so the next valid sample starts from {@link Tier#NONE}.
     */
    public void reset() {
        mTier = Tier.NONE;
    }
}
