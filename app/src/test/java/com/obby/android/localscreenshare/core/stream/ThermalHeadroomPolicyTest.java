package com.obby.android.localscreenshare.core.stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.obby.android.localscreenshare.core.stream.ThermalThrottlePolicy.Tier;

import org.junit.Test;

/**
 * Unit tests for {@link ThermalHeadroomPolicy}.
 *
 * <p>The platform reports thermal pressure rather than a remaining margin: one means the platform
 * starts throttling, values above one mean the device is already throttled, and the reading grows
 * hotter as it rises. The cases pin the hysteresis bands, the rounding rule that keeps the 0.70 and
 * 0.85 thresholds reachable, and the decision to ignore NaN and out-of-range samples without
 * changing the tier.</p>
 */
public class ThermalHeadroomPolicyTest {

    @Test
    public void onSample_nanKeepsCurrentTier() {
        final ThermalHeadroomPolicy policy = new ThermalHeadroomPolicy();

        assertEquals(Tier.REDUCED, policy.onSample(0.72f));
        assertEquals(Tier.REDUCED, policy.onSample(Float.NaN));
    }

    @Test
    public void onSample_entersReducedAtThreshold() {
        final ThermalHeadroomPolicy policy = new ThermalHeadroomPolicy();

        assertEquals(Tier.NONE, policy.onSample(0.69f));
        assertEquals(Tier.REDUCED, policy.onSample(0.70f));
    }

    @Test
    public void onSample_entersMinimalAtThreshold() {
        final ThermalHeadroomPolicy policy = new ThermalHeadroomPolicy();

        assertEquals(Tier.REDUCED, policy.onSample(0.84f));
        assertEquals(Tier.MINIMAL, policy.onSample(0.85f));
    }

    @Test
    public void onSample_keepsReducedAboveExit() {
        final ThermalHeadroomPolicy policy = new ThermalHeadroomPolicy();

        assertEquals(Tier.REDUCED, policy.onSample(0.70f));
        assertEquals(Tier.REDUCED, policy.onSample(0.65f));
    }

    @Test
    public void onSample_exitsReducedBelowExit() {
        final ThermalHeadroomPolicy policy = new ThermalHeadroomPolicy();

        assertEquals(Tier.REDUCED, policy.onSample(0.70f));
        assertEquals(Tier.NONE, policy.onSample(0.59f));
    }

    @Test
    public void onSample_keepsMinimalAboveExit() {
        final ThermalHeadroomPolicy policy = new ThermalHeadroomPolicy();

        assertEquals(Tier.MINIMAL, policy.onSample(0.85f));
        assertEquals(Tier.MINIMAL, policy.onSample(0.79f));
    }

    @Test
    public void onSample_downgradesMinimalBelowExit() {
        final ThermalHeadroomPolicy policy = new ThermalHeadroomPolicy();

        assertEquals(Tier.MINIMAL, policy.onSample(0.85f));
        assertEquals(Tier.REDUCED, policy.onSample(0.77f));
    }

    @Test
    public void onSample_ignoresOutOfRangeValues() {
        final ThermalHeadroomPolicy policy = new ThermalHeadroomPolicy();

        assertEquals(Tier.NONE, policy.onSample(-0.1f));
        assertEquals(Tier.NONE, policy.onSample(2.5f));
        assertEquals(Tier.MINIMAL, policy.onSample(0.90f));
        assertEquals(Tier.MINIMAL, policy.onSample(-0.1f));
        assertEquals(Tier.MINIMAL, policy.onSample(2.5f));
        assertEquals(Tier.MINIMAL, policy.onSample(1.5f));
    }

    @Test
    public void onSample_bandFlappingDoesNotOscillate() {
        final ThermalHeadroomPolicy policy = new ThermalHeadroomPolicy();
        int changes = 0;
        Tier previous = policy.getTier();

        for (int i = 0; i < 10; i++) {
            final Tier next = policy.onSample(i % 2 == 0 ? 0.60f : 0.70f);
            if (next != previous) {
                changes++;
            }
            previous = next;
        }

        assertTrue("tier changed " + changes + " times", changes <= 1);
    }

    @Test
    public void reset_returnsToNone() {
        final ThermalHeadroomPolicy policy = new ThermalHeadroomPolicy();

        assertEquals(Tier.MINIMAL, policy.onSample(0.95f));
        policy.reset();

        assertEquals(Tier.NONE, policy.getTier());
    }

    @Test
    public void pollIntervalRespectsOneHertz() {
        assertTrue(ThermalHeadroomPolicy.POLL_INTERVAL_MS >= 1000L);
    }

    @Test
    public void forecastSecondsMatchesTheDocumentedTen() {
        assertEquals(10, ThermalHeadroomPolicy.FORECAST_SECONDS);
    }
}
