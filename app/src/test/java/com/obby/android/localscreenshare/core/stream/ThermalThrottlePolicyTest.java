package com.obby.android.localscreenshare.core.stream;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for {@link ThermalThrottlePolicy}.
 *
 * <p>The cases pin the ladder: the lower half of the platform scale changes nothing, the first
 * throttling band caps the frame rate only, and the top bands halve the capture area. They also
 * pin that an unknown status and an already-lower user value are both left alone, because a
 * temporary override must never raise a value the user chose.</p>
 */
public class ThermalThrottlePolicyTest {

    @Test
    public void lowerStatuses_keepUserSettings() {
        assertEquals(ThermalThrottlePolicy.Tier.NONE,
                ThermalThrottlePolicy.tierFor(ThermalThrottlePolicy.STATUS_NONE));
        assertEquals(ThermalThrottlePolicy.Tier.NONE,
                ThermalThrottlePolicy.tierFor(ThermalThrottlePolicy.STATUS_LIGHT));
        assertEquals(ThermalThrottlePolicy.Tier.NONE,
                ThermalThrottlePolicy.tierFor(ThermalThrottlePolicy.STATUS_MODERATE));
    }

    @Test
    public void severeStatus_capsFrameRateOnly() {
        final ThermalThrottlePolicy.Tier tier =
                ThermalThrottlePolicy.tierFor(ThermalThrottlePolicy.STATUS_SEVERE);

        assertEquals(ThermalThrottlePolicy.Tier.REDUCED, tier);
        assertEquals(30, ThermalThrottlePolicy.capFrameRate(tier, 60));
        assertEquals(100, ThermalThrottlePolicy.capScalePercent(tier, 100));
    }

    @Test
    public void topStatuses_capFrameRateAndScale() {
        for (int status : new int[]{ThermalThrottlePolicy.STATUS_CRITICAL,
                ThermalThrottlePolicy.STATUS_EMERGENCY, ThermalThrottlePolicy.STATUS_SHUTDOWN}) {
            final ThermalThrottlePolicy.Tier tier = ThermalThrottlePolicy.tierFor(status);

            assertEquals(ThermalThrottlePolicy.Tier.MINIMAL, tier);
            assertEquals(30, ThermalThrottlePolicy.capFrameRate(tier, 60));
            assertEquals(50, ThermalThrottlePolicy.capScalePercent(tier, 100));
        }
    }

    @Test
    public void unknownStatus_isNotThrottled() {
        assertEquals(ThermalThrottlePolicy.Tier.NONE,
                ThermalThrottlePolicy.tierFor(-1));
        assertEquals(ThermalThrottlePolicy.Tier.NONE,
                ThermalThrottlePolicy.tierFor(99));
    }

    @Test
    public void caps_neverRaiseAUserValue() {
        assertEquals(24, ThermalThrottlePolicy.capFrameRate(
                ThermalThrottlePolicy.Tier.REDUCED, 24));
        assertEquals(40, ThermalThrottlePolicy.capScalePercent(
                ThermalThrottlePolicy.Tier.MINIMAL, 40));
        assertEquals(15, ThermalThrottlePolicy.capScalePercent(
                ThermalThrottlePolicy.Tier.NONE, 15));
    }

    @Test
    public void caps_keepAtLeastOne() {
        assertEquals(1, ThermalThrottlePolicy.capFrameRate(ThermalThrottlePolicy.Tier.NONE, 0));
        assertEquals(1, ThermalThrottlePolicy.capFrameRate(ThermalThrottlePolicy.Tier.MINIMAL, 0));
        assertEquals(1, ThermalThrottlePolicy.capScalePercent(ThermalThrottlePolicy.Tier.MINIMAL, 0));
    }

    @Test
    public void unchangedStatus_producesTheStoredSettings() {
        final ThermalThrottlePolicy.Tier tier = ThermalThrottlePolicy.tierFor(
                ThermalThrottlePolicy.STATUS_MODERATE);

        assertEquals(60, ThermalThrottlePolicy.capFrameRate(tier, 60));
        assertEquals(100, ThermalThrottlePolicy.capScalePercent(tier, 100));
    }
}
