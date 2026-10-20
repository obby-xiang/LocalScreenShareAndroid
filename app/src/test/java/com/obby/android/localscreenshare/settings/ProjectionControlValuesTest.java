package com.obby.android.localscreenshare.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.obby.android.localscreenshare.core.codec.CodecIds;

import org.junit.Test;

/**
 * JVM tests for the values the video settings controls display.
 *
 * <p>The sheet refreshes its controls whenever a projection preference changes, including the
 * percentage the service writes back after an encoder rejection, so every mapping result has to be
 * a value the widget accepts: inside its range and on its step. A stored value that another version
 * wrote below the current capture floor is displayed at the floor, because that is the lowest value
 * the user can still choose.</p>
 */
public class ProjectionControlValuesTest {

    private static final float DELTA = 0.0001f;

    @Test
    public void storedDefaults_arePassedThrough() {
        ProjectionControlValues values =
            ProjectionControlValues.of(defaults(100, 4_000_000, 1_000, 85), 5);

        assertEquals(100, values.getScalePercent());
        assertEquals(60, values.getFrameRate());
        assertEquals(4.0f, values.getBitrateMbps(), DELTA);
        assertEquals(1.0f, values.getIdrIntervalSeconds(), DELTA);
        assertEquals(85, values.getJpegQuality());
        assertTrue(values.isH264());
    }

    @Test
    public void scaleBelowTheFloor_isDisplayedAtTheFloor() {
        assertEquals(14, ProjectionControlValues.of(defaults(5, 4_000_000, 1_000, 85), 14)
            .getScalePercent());
        assertEquals(14, ProjectionControlValues.of(defaults(13, 4_000_000, 1_000, 85), 14)
            .getScalePercent());
    }

    @Test
    public void scaleAtOrAboveTheFloor_isKept() {
        assertEquals(14, ProjectionControlValues.of(defaults(14, 4_000_000, 1_000, 85), 14)
            .getScalePercent());
        assertEquals(79, ProjectionControlValues.of(defaults(79, 4_000_000, 1_000, 85), 14)
            .getScalePercent());
    }

    @Test
    public void floorAboveTheSliderRange_isClampedToTheMaximum() {
        assertEquals(ProjectionSettings.MAX_SCALE_PERCENT,
            ProjectionControlValues.of(defaults(100, 4_000_000, 1_000, 85), 140)
                .getScalePercent());
    }

    @Test
    public void bitrateOffTheSliderStep_isSnappedToTheNearestStep() {
        assertEquals(4.5f, ProjectionControlValues.of(defaults(100, 4_300_000, 1_000, 85), 5)
            .getBitrateMbps(), DELTA);
        assertEquals(4.0f, ProjectionControlValues.of(defaults(100, 4_200_000, 1_000, 85), 5)
            .getBitrateMbps(), DELTA);
        assertEquals(4.0f, ProjectionControlValues.of(defaults(100, 4_000_000, 1_000, 85), 5)
            .getBitrateMbps(), DELTA);
    }

    @Test
    public void bitrateOutsideTheSliderRange_isClampedToAnEndPoint() {
        assertEquals(ProjectionControlValues.MIN_BITRATE_MBPS,
            ProjectionControlValues.of(defaults(100, ProjectionSettings.MIN_BITRATE_BPS, 1_000, 85),
                5).getBitrateMbps(), DELTA);
        assertEquals(ProjectionControlValues.MAX_BITRATE_MBPS,
            ProjectionControlValues.of(defaults(100, ProjectionSettings.MAX_BITRATE_BPS, 1_000, 85),
                5).getBitrateMbps(), DELTA);
    }

    @Test
    public void bitrate_isAlwaysOnTheSliderStep() {
        for (int bitrateBps = ProjectionSettings.MIN_BITRATE_BPS;
             bitrateBps <= 20_000_000; bitrateBps += 100_000) {
            float mbps = ProjectionControlValues.of(defaults(100, bitrateBps, 1_000, 85), 5)
                .getBitrateMbps();
            float steps = (mbps - ProjectionControlValues.MIN_BITRATE_MBPS)
                / ProjectionControlValues.BITRATE_STEP_MBPS;
            assertEquals("bitrate " + bitrateBps + " bps", Math.round(steps), steps, DELTA);
        }
    }

    @Test
    public void idrInterval_isDisplayedInWholeSeconds() {
        assertEquals(1.0f, ProjectionControlValues.of(defaults(100, 4_000_000, 1_000, 85), 5)
            .getIdrIntervalSeconds(), DELTA);
        assertEquals(2.0f, ProjectionControlValues.of(defaults(100, 4_000_000, 1_500, 85), 5)
            .getIdrIntervalSeconds(), DELTA);
        assertEquals(30.0f, ProjectionControlValues.of(defaults(100, 4_000_000, 30_000, 85), 5)
            .getIdrIntervalSeconds(), DELTA);
    }

    @Test
    public void frameRateOutsideTheRange_isClampedByTheSettingsSnapshot() {
        assertEquals(ProjectionSettings.MIN_FRAME_RATE,
            ProjectionControlValues.of(ProjectionSettings.of(4_000_000, 0, 1_000, 100, 85,
                CodecIds.H264), 5).getFrameRate());
        assertEquals(ProjectionSettings.MAX_FRAME_RATE,
            ProjectionControlValues.of(ProjectionSettings.of(4_000_000, 500, 1_000, 100, 85,
                CodecIds.H264), 5).getFrameRate());
    }

    @Test
    public void jpegCodec_isReportedAsNotH264() {
        ProjectionControlValues values = ProjectionControlValues.of(
            ProjectionSettings.of(4_000_000, 60, 1_000, 100, 70, CodecIds.JPEG), 5);

        assertFalse(values.isH264());
        assertEquals(70, values.getJpegQuality());
    }

    private static ProjectionSettings defaults(int scalePercent, int bitrateBps, int idrIntervalMs,
                                               int quality) {
        return ProjectionSettings.of(bitrateBps, 60, idrIntervalMs, scalePercent, quality,
            CodecIds.H264);
    }
}
