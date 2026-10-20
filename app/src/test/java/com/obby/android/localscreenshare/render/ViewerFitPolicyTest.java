package com.obby.android.localscreenshare.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link ViewerFitPolicy}.
 *
 * <p>The numbers follow the scenarios in the viewer quality plan: a same-orientation pair that must
 * reproduce the legacy window, a landscape stream shown inside portrait bounds, a rotated stream,
 * and the {@link ZoomMode} snapping and clamping rules. All arithmetic is integer or float, so the
 * expected sizes are exact pixel counts rather than tolerances.</p>
 */
public class ViewerFitPolicyTest {

    private static final int MIN_SIZE = 56;

    private static final int PORTRAIT_BOUNDS_W = 1080;

    private static final int PORTRAIT_BOUNDS_H = 2400;

    @Test
    public void fit_sameOrientation_reproducesLegacyWindow() {
        ViewerFitPolicy.Fit fit = ViewerFitPolicy.fit(1080, 2340, 0,
                PORTRAIT_BOUNDS_W, PORTRAIT_BOUNDS_H, 80, MIN_SIZE, ZoomMode.FIT);

        assertEquals(864, fit.getWidth());
        assertEquals(1872, fit.getHeight());
        assertEquals(0.8f, fit.getScale(), 1e-6f);
        assertFalse(fit.isSnapped());
        assertFalse(fit.isClamped());
        assertFalse(fit.isFallback());
    }

    @Test
    public void fit_landscapeContentInsidePortraitBounds_keepsAspectRatio() {
        ViewerFitPolicy.Fit fit = ViewerFitPolicy.fit(2340, 1080, 0,
                PORTRAIT_BOUNDS_W, PORTRAIT_BOUNDS_H, 80, MIN_SIZE, ZoomMode.FIT);

        assertEquals(864, fit.getWidth());
        assertEquals(399, fit.getHeight());
        assertFalse(fit.isClamped());
        assertFalse(fit.isFallback());
    }

    @Test
    public void fit_rotation90_swapsContentAxes() {
        ViewerFitPolicy.Fit rotated = ViewerFitPolicy.fit(1080, 2340, 90,
                PORTRAIT_BOUNDS_W, PORTRAIT_BOUNDS_H, 80, MIN_SIZE, ZoomMode.FIT);
        ViewerFitPolicy.Fit direct = ViewerFitPolicy.fit(2340, 1080, 0,
                PORTRAIT_BOUNDS_W, PORTRAIT_BOUNDS_H, 80, MIN_SIZE, ZoomMode.FIT);

        assertEquals(direct.getWidth(), rotated.getWidth());
        assertEquals(direct.getHeight(), rotated.getHeight());
        assertEquals(direct.getScale(), rotated.getScale(), 1e-6f);
    }

    @Test
    public void fit_rotation270_swapsContentAxes() {
        ViewerFitPolicy.Fit rotated = ViewerFitPolicy.fit(1080, 2340, 270,
                PORTRAIT_BOUNDS_W, PORTRAIT_BOUNDS_H, 80, MIN_SIZE, ZoomMode.FIT);

        assertEquals(864, rotated.getWidth());
        assertEquals(399, rotated.getHeight());
    }

    @Test
    public void fit_rotation180AndUnsupportedAngles_leaveAxesUnchanged() {
        ViewerFitPolicy.Fit upright = ViewerFitPolicy.fit(1080, 2340, 0,
                PORTRAIT_BOUNDS_W, PORTRAIT_BOUNDS_H, 80, MIN_SIZE, ZoomMode.FIT);

        for (int rotation : new int[]{180, 45, -90, 360}) {
            ViewerFitPolicy.Fit fit = ViewerFitPolicy.fit(1080, 2340, rotation,
                    PORTRAIT_BOUNDS_W, PORTRAIT_BOUNDS_H, 80, MIN_SIZE, ZoomMode.FIT);
            assertEquals("rotation " + rotation, upright.getWidth(), fit.getWidth());
            assertEquals("rotation " + rotation, upright.getHeight(), fit.getHeight());
        }
    }

    @Test
    public void fit_boundsSmallerThanContent_shrinksBelowContent() {
        ViewerFitPolicy.Fit fit = ViewerFitPolicy.fit(1080, 2340, 0,
                540, 1200, 80, MIN_SIZE, ZoomMode.FIT);

        assertEquals(432, fit.getWidth());
        assertEquals(936, fit.getHeight());
        assertEquals(0.4f, fit.getScale(), 1e-6f);
    }

    @Test
    public void fit_scalePercentIsClampedToOneThroughHundred() {
        ViewerFitPolicy.Fit low = ViewerFitPolicy.fit(1080, 2340, 0,
                PORTRAIT_BOUNDS_W, PORTRAIT_BOUNDS_H, 0, 0, ZoomMode.FIT);
        ViewerFitPolicy.Fit high = ViewerFitPolicy.fit(1080, 2340, 0,
                PORTRAIT_BOUNDS_W, PORTRAIT_BOUNDS_H, 150, 0, ZoomMode.FIT);

        assertEquals(11, low.getWidth());
        assertEquals(23, low.getHeight());
        assertEquals(0.01f, low.getScale(), 1e-6f);
        assertEquals(1080, high.getWidth());
        assertEquals(2340, high.getHeight());
        assertEquals(1f, high.getScale(), 1e-6f);
    }

    @Test
    public void oneToOne_exactContentSize() {
        ViewerFitPolicy.Fit fit = ViewerFitPolicy.fit(1080, 2340, 0,
                PORTRAIT_BOUNDS_W, PORTRAIT_BOUNDS_H, 80, MIN_SIZE, ZoomMode.ONE_TO_ONE);

        assertEquals(1080, fit.getWidth());
        assertEquals(2340, fit.getHeight());
        assertEquals(1f, fit.getScale(), 1e-6f);
        assertFalse(fit.isSnapped());
        assertFalse(fit.isClamped());
    }

    @Test
    public void oneToOne_largerThanBounds_clampsUniformly() {
        ViewerFitPolicy.Fit fit = ViewerFitPolicy.fit(2340, 1080, 0,
                PORTRAIT_BOUNDS_W, PORTRAIT_BOUNDS_H, 80, MIN_SIZE, ZoomMode.ONE_TO_ONE);

        assertEquals(1080, fit.getWidth());
        assertEquals(498, fit.getHeight());
        assertEquals(1080f / 2340f, fit.getScale(), 1e-4f);
        assertTrue(fit.isClamped());
        assertFalse(fit.isSnapped());
    }

    @Test
    public void integer_snapsToNearestWholeNumberReduction() {
        assertInteger(80, 1080, 2340, 1f);
        assertInteger(100, 1080, 2340, 1f);
        assertInteger(60, 540, 1170, 0.5f);
        assertInteger(50, 540, 1170, 0.5f);
        assertInteger(33, 360, 780, 1f / 3f);
        assertInteger(26, 270, 585, 0.25f);
        assertInteger(25, 270, 585, 0.25f);
    }

    @Test
    public void integer_tie_snapsUpwards() {
        ViewerFitPolicy.Fit fit = ViewerFitPolicy.fit(1080, 2340, 0,
                2160, 4800, 75, 0, ZoomMode.INTEGER);

        assertEquals(1080, fit.getWidth());
        assertEquals(2340, fit.getHeight());
        assertEquals(1f, fit.getScale(), 1e-6f);
        assertTrue(fit.isSnapped());
    }

    @Test
    public void integer_exactTier_isNotReportedAsSnapped() {
        ViewerFitPolicy.Fit fit = ViewerFitPolicy.fit(1080, 2340, 0,
                2160, 4800, 50, 0, ZoomMode.INTEGER);

        assertEquals(540, fit.getWidth());
        assertEquals(1170, fit.getHeight());
        assertFalse(fit.isSnapped());
    }

    @Test
    public void integer_largerThanBounds_clampsAfterSnapping() {
        ViewerFitPolicy.Fit fit = ViewerFitPolicy.fit(1080, 2340, 0,
                540, 1200, 80, 0, ZoomMode.INTEGER);

        assertEquals(540, fit.getWidth());
        assertEquals(1170, fit.getHeight());
        assertEquals(0.5f, fit.getScale(), 1e-6f);
        assertTrue(fit.isSnapped());
        assertTrue(fit.isClamped());
    }

    @Test
    public void minimumSize_scalesBothAxesUp() {
        ViewerFitPolicy.Fit fit = ViewerFitPolicy.fit(100, 100, 0,
                200, 200, 10, MIN_SIZE, ZoomMode.FIT);

        assertEquals(MIN_SIZE, fit.getWidth());
        assertEquals(MIN_SIZE, fit.getHeight());
        assertEquals(0.56f, fit.getScale(), 1e-6f);
    }

    @Test
    public void minimumSize_appliesToOneToOneToo() {
        ViewerFitPolicy.Fit fit = ViewerFitPolicy.fit(40, 60, 0,
                1000, 1000, 80, MIN_SIZE, ZoomMode.ONE_TO_ONE);

        assertEquals(MIN_SIZE, fit.getWidth());
        assertEquals(84, fit.getHeight());
        assertEquals(1.4f, fit.getScale(), 1e-6f);
    }

    @Test
    public void nonPositiveMinimumSize_isIgnored() {
        ViewerFitPolicy.Fit fit = ViewerFitPolicy.fit(1080, 2340, 0,
                PORTRAIT_BOUNDS_W, PORTRAIT_BOUNDS_H, 80, -5, ZoomMode.FIT);

        assertEquals(864, fit.getWidth());
        assertEquals(1872, fit.getHeight());
    }

    @Test
    public void unknownContentGeometry_fallsBackToBoundsTimesPercent() {
        ViewerFitPolicy.Fit fit = ViewerFitPolicy.fit(0, 0, 0,
                PORTRAIT_BOUNDS_W, PORTRAIT_BOUNDS_H, 80, MIN_SIZE, ZoomMode.FIT);

        assertEquals(864, fit.getWidth());
        assertEquals(1920, fit.getHeight());
        assertEquals(0.8f, fit.getScale(), 1e-6f);
        assertTrue(fit.isFallback());
        assertFalse(fit.isSnapped());
        assertFalse(fit.isClamped());
    }

    @Test
    public void emptyBounds_fallsBackToMinimumSize() {
        ViewerFitPolicy.Fit fit = ViewerFitPolicy.fit(1080, 2340, 0, 0, 0, 80,
                MIN_SIZE, ZoomMode.FIT);

        assertEquals(MIN_SIZE, fit.getWidth());
        assertEquals(MIN_SIZE, fit.getHeight());
        assertTrue(fit.isFallback());
    }

    @Test
    public void fallback_ignoresZoomMode() {
        ViewerFitPolicy.Fit oneToOne = ViewerFitPolicy.fit(0, 0, 0,
                PORTRAIT_BOUNDS_W, PORTRAIT_BOUNDS_H, 80, MIN_SIZE, ZoomMode.ONE_TO_ONE);

        assertTrue(oneToOne.isFallback());
        assertEquals(864, oneToOne.getWidth());
        assertEquals(1920, oneToOne.getHeight());
    }

    @Test
    public void fit_preservesAspectRatioWithinOnePixel() {
        int[][] contents = {{1080, 2340}, {2340, 1080}, {1920, 1080}, {1440, 3120}, {800, 600}};
        int[][] bounds = {{1080, 2400}, {1440, 2560}, {720, 1280}};
        int[] percentages = {25, 40, 60, 80, 100};
        ZoomMode[] modes = {ZoomMode.FIT, ZoomMode.ONE_TO_ONE, ZoomMode.INTEGER};

        for (int[] content : contents) {
            for (int[] bound : bounds) {
                for (int percent : percentages) {
                    for (ZoomMode mode : modes) {
                        ViewerFitPolicy.Fit fit = ViewerFitPolicy.fit(content[0], content[1], 0,
                                bound[0], bound[1], percent, 0, mode);
                        long errorPx = Math.abs((long) fit.getWidth() * content[1]
                                - (long) fit.getHeight() * content[0]);
                        assertTrue(mode + " " + content[0] + "x" + content[1] + " in "
                                        + bound[0] + "x" + bound[1] + " at " + percent
                                        + "% has aspect error " + errorPx,
                                errorPx <= Math.max(content[0], content[1]));
                        if (mode != ZoomMode.ONE_TO_ONE) {
                            assertTrue(mode + " must not exceed the scaled bounds width",
                                    fit.getWidth() <= bound[0] + 1);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void fit_isDeterministic() {
        ViewerFitPolicy.Fit first = ViewerFitPolicy.fit(1080, 2340, 0,
                PORTRAIT_BOUNDS_W, PORTRAIT_BOUNDS_H, 80, MIN_SIZE, ZoomMode.INTEGER);
        ViewerFitPolicy.Fit second = ViewerFitPolicy.fit(1080, 2340, 0,
                PORTRAIT_BOUNDS_W, PORTRAIT_BOUNDS_H, 80, MIN_SIZE, ZoomMode.INTEGER);

        assertEquals(first.getWidth(), second.getWidth());
        assertEquals(first.getHeight(), second.getHeight());
        assertEquals(first.getScale(), second.getScale(), 0f);
        assertEquals(first.isSnapped(), second.isSnapped());
        assertEquals(first.isClamped(), second.isClamped());
        assertEquals(first.isFallback(), second.isFallback());
    }

    @Test
    public void fit_rejectsNullMode() {
        assertThrows(IllegalArgumentException.class, () -> ViewerFitPolicy.fit(1080, 2340, 0,
                PORTRAIT_BOUNDS_W, PORTRAIT_BOUNDS_H, 80, MIN_SIZE, null));
    }

    private static void assertInteger(int percent, int expectedWidth, int expectedHeight,
                                      float expectedScale) {
        ViewerFitPolicy.Fit fit = ViewerFitPolicy.fit(1080, 2340, 0,
                2160, 4800, percent, 0, ZoomMode.INTEGER);

        assertEquals("width at " + percent + "%", expectedWidth, fit.getWidth());
        assertEquals("height at " + percent + "%", expectedHeight, fit.getHeight());
        assertEquals("scale at " + percent + "%", expectedScale, fit.getScale(), 1e-6f);
    }
}
