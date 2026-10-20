package com.obby.android.localscreenshare.settings;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * JVM tests for the capture percentage floor.
 *
 * <p>The device report that motivated this class: on a 1080x2340 display the sheet offered 11%, 10%
 * and 5%, which produced 116x256, 108x232 and 52x116; the hardware encoder only accepts
 * {@code [144x144, 4096x4096]}, so every one of those sizes was refused. The floor has to come from
 * the encoder minimum and the window bounds instead of a fixed percentage, and it has to survive a
 * rotation, because the service derives the capture size from the bounds of the current
 * orientation.</p>
 */
public class ProjectionScalePolicyTest {

    // Encoder minimum reported by the hardware encoder of the 2026-10-21 device test.
    private static final int PHONE_ENCODER_MINIMUM = 144;

    // Encoder minimum reported by the software encoder of the emulator.
    private static final int EMULATOR_ENCODER_MINIMUM = 16;

    @Test
    public void deviceMinimum_isRoundedUpToTheLowestUsablePercent() {
        // 144 / 1080 = 13.33%, so 13% still produces 140 px and only 14% reaches the minimum.
        assertEquals(14, ProjectionScalePolicy.getMinimumPercent(PHONE_ENCODER_MINIMUM,
            PHONE_ENCODER_MINIMUM, 1080, 2340, 1));
    }

    @Test
    public void rotation_keepsTheSameFloor() {
        assertEquals(14, ProjectionScalePolicy.getMinimumPercent(PHONE_ENCODER_MINIMUM,
            PHONE_ENCODER_MINIMUM, 2340, 1080, 1));
    }

    @Test
    public void shorterEdge_isTheBindingConstraint() {
        // A 1080x600 window is bound by its height: 144 / 600 = 24%.
        assertEquals(24, ProjectionScalePolicy.getMinimumPercent(PHONE_ENCODER_MINIMUM,
            PHONE_ENCODER_MINIMUM, 1080, 600, 1));
    }

    @Test
    public void exactDivision_needsNoExtraPercent() {
        assertEquals(12, ProjectionScalePolicy.getMinimumPercent(PHONE_ENCODER_MINIMUM,
            PHONE_ENCODER_MINIMUM, 1200, 1200, 1));
    }

    @Test
    public void encoderBelowTheGenericFloor_keepsTheFallback() {
        // The emulator encoder accepts 16x16, which is 2% of 1080; the generic 5% floor wins.
        assertEquals(ProjectionSettings.MIN_SCALE_PERCENT,
            ProjectionScalePolicy.getMinimumPercent(EMULATOR_ENCODER_MINIMUM,
                EMULATOR_ENCODER_MINIMUM, 1080, 2400, 1));
    }

    @Test
    public void unknownCapabilities_keepTheFallback() {
        assertEquals(ProjectionSettings.MIN_SCALE_PERCENT,
            ProjectionScalePolicy.getMinimumPercent(0, 0, 1080, 2400, 1));
        assertEquals(ProjectionSettings.MIN_SCALE_PERCENT,
            ProjectionScalePolicy.getMinimumPercent(-1, PHONE_ENCODER_MINIMUM, 1080, 2400, 1));
    }

    @Test
    public void unknownBounds_keepTheFallback() {
        assertEquals(ProjectionSettings.MIN_SCALE_PERCENT,
            ProjectionScalePolicy.getMinimumPercent(PHONE_ENCODER_MINIMUM,
                PHONE_ENCODER_MINIMUM, 0, 2400, 1));
        assertEquals(ProjectionSettings.MIN_SCALE_PERCENT,
            ProjectionScalePolicy.getMinimumPercent(PHONE_ENCODER_MINIMUM,
                PHONE_ENCODER_MINIMUM, 1080, 0, 1));
    }

    @Test
    public void floor_isRoundedUpToTheSliderStep() {
        // 14% raised to the next 5% step is 15%, which is where the slider can actually stop.
        assertEquals(15, ProjectionScalePolicy.getMinimumPercent(PHONE_ENCODER_MINIMUM,
            PHONE_ENCODER_MINIMUM, 1080, 2340, 5));
    }

    @Test
    public void nonPositiveStep_isTreatedAsOnePercent() {
        assertEquals(14, ProjectionScalePolicy.getMinimumPercent(PHONE_ENCODER_MINIMUM,
            PHONE_ENCODER_MINIMUM, 1080, 2340, 0));
    }

    @Test
    public void minimumAboveTheSliderRange_isClampedToTheMaximum() {
        assertEquals(ProjectionSettings.MAX_SCALE_PERCENT,
            ProjectionScalePolicy.getMinimumPercent(PHONE_ENCODER_MINIMUM,
                PHONE_ENCODER_MINIMUM, 100, 100, 1));
    }

    @Test
    public void widthAndHeightMinimums_useTheirOwnEdge() {
        // The floor is the larger of the two percentages, not the percentage of the smaller edge
        // alone: a 200 px width minimum binds at 19% of 1080, a 200 px height minimum at 34% of
        // 600, and swapping the two minimums must swap the answer.
        assertEquals(19, ProjectionScalePolicy.getMinimumPercent(200, 60, 1080, 600, 1));
        assertEquals(34, ProjectionScalePolicy.getMinimumPercent(60, 200, 1080, 600, 1));
    }
}
