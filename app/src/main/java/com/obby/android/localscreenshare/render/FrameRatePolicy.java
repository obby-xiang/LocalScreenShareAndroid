package com.obby.android.localscreenshare.render;

/**
 * Chooses which {@code Surface.setFrameRate} variant a content frame-rate hint maps to.
 *
 * <p>The platform method exists in two shapes: API 30 added the two-argument variant and API 31
 * added the change strategy that lets the platform apply the request only when it does not cause a
 * visible mode switch. Below API 30 no variant exists at all, and a call would fail with
 * {@link NoSuchMethodError} at run time, which no compile-time check can prevent; the API level is
 * therefore an argument instead of a platform read, and this class stays pure JVM so the branch is
 * covered by ordinary unit tests.</p>
 *
 * <p>The class does not catch anything. Swallowing a platform or vendor failure is the rendering
 * target's responsibility, because only it knows that the hint is best effort and must not disturb
 * playback; a policy that swallowed exceptions would hide a broken seam from its callers.</p>
 *
 * <p>A non-positive frame rate means unknown or cleared and never reaches the platform. This class
 * allocates nothing and holds no lock.</p>
 */
public final class FrameRatePolicy {

    /**
     * Lowest API level that has {@code Surface.setFrameRate(float, int)}.
     */
    static final int MIN_SDK = 30;

    /**
     * Lowest API level that has the seamless change strategy.
     */
    static final int SEAMLESS_SDK = 31;

    private FrameRatePolicy() {
    }

    /**
     * Applies one content frame-rate hint through {@code applier} when the platform can take it.
     *
     * <p>Below {@link #MIN_SDK} and for a non-positive frame rate nothing is applied, so the
     * caller's applier is not invoked at all. From {@link #SEAMLESS_SDK} on the request is marked
     * seamless, which asks the platform to change the display mode only when the change does not
     * cause a visible glitch; API 30 has no strategy argument and gets the plain variant. The
     * method is package-private because {@link FrameRateApplier} is: only the rendering package
     * calls it, and a public method must not expose a package-private parameter type.</p>
     *
     * @param frameRate content frame rate in frames per second; non-positive means unknown
     * @param sdkInt    platform API level, passed in so the branch is testable on the JVM
     * @param applier   performs the actual platform call; exceptions propagate unchanged
     */
    static void applyFrameRate(int frameRate, int sdkInt, FrameRateApplier applier) {
        if (frameRate <= 0 || sdkInt < MIN_SDK) {
            return;
        }
        applier.apply(frameRate, sdkInt >= SEAMLESS_SDK);
    }

    /**
     * Performs the platform call for one API level.
     *
     * <p>Package-private so the policy file stays the only place that knows the API split and a
     * JVM test can implement it directly. An implementation reports {@code seamless} by passing
     * {@code Surface.CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS} on API 31 and later.</p>
     */
    interface FrameRateApplier {

        /**
         * Applies one hint.
         *
         * @param frameRate content frame rate in frames per second; always positive
         * @param seamless  whether the platform supports and should use the seamless strategy
         */
        void apply(int frameRate, boolean seamless);
    }
}
