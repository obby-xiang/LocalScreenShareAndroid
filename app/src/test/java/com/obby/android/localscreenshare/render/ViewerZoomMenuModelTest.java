package com.obby.android.localscreenshare.render;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * JVM tests for the zoom menu projection.
 *
 * <p>The device report that motivated this model: with the default viewing percentage of eighty
 * percent the integer preset snapped to {@code 1} and produced exactly the same window as the
 * actual-size row, while the menu listed "1/2, 1/3, 1/4" and said nothing about the ratio any row
 * would apply. The rows are therefore expected to carry their effective scale, and the integer row
 * is expected to disappear when it would only repeat the actual-size window.</p>
 */
public class ViewerZoomMenuModelTest {

    // Portrait phone at full capture scale on a display with a 63 px status bar.
    private static final int CONTENT_WIDTH = 1080;

    private static final int CONTENT_HEIGHT = 2340;

    private static final int BOUNDS_WIDTH = 1080;

    private static final int BOUNDS_HEIGHT = 2274;

    private static final int MIN_SIZE = 48;

    @Test
    public void integerPresetThatEqualsActualSize_isOmitted() {
        List<ViewerZoomMenuModel.Entry> entries = ViewerZoomMenuModel.build(CONTENT_WIDTH,
                CONTENT_HEIGHT, 0, BOUNDS_WIDTH, BOUNDS_HEIGHT, 80, MIN_SIZE, ZoomMode.FIT);

        assertEquals(2, entries.size());
        assertEquals(ZoomMode.FIT, entries.get(0).getMode());
        assertEquals(ZoomMode.ONE_TO_ONE, entries.get(1).getMode());
        for (ViewerZoomMenuModel.Entry entry : entries) {
            assertFalse("the integer preset must not repeat the actual-size window",
                    entry.getMode() == ZoomMode.INTEGER);
        }
    }

    @Test
    public void rowsCarryTheirEffectiveScale() {
        List<ViewerZoomMenuModel.Entry> entries = ViewerZoomMenuModel.build(CONTENT_WIDTH,
                CONTENT_HEIGHT, 0, BOUNDS_WIDTH, BOUNDS_HEIGHT, 80, MIN_SIZE, ZoomMode.FIT);

        // Fit window uses 80% of the bounds but is limited by the content aspect ratio; the actual
        // size is capped by the display height. Both numbers are what the window really becomes.
        assertEquals(78, entries.get(0).getEffectivePercent());
        assertFalse(entries.get(0).isClamped());
        assertEquals(97, entries.get(1).getEffectivePercent());
        assertTrue(entries.get(1).isClamped());
    }

    @Test
    public void integerPresetWithItsOwnTier_isListedWithItsScale() {
        List<ViewerZoomMenuModel.Entry> entries = ViewerZoomMenuModel.build(CONTENT_WIDTH,
                CONTENT_HEIGHT, 0, BOUNDS_WIDTH, BOUNDS_HEIGHT, 60, MIN_SIZE, ZoomMode.FIT);

        assertEquals(3, entries.size());
        ViewerZoomMenuModel.Entry integer = entries.get(2);
        assertEquals(ZoomMode.INTEGER, integer.getMode());
        assertEquals(50, integer.getEffectivePercent());
        assertFalse(integer.isClamped());
    }

    @Test
    public void omittedIntegerPreset_selectsTheEquivalentRow() {
        List<ViewerZoomMenuModel.Entry> entries = ViewerZoomMenuModel.build(CONTENT_WIDTH,
                CONTENT_HEIGHT, 0, BOUNDS_WIDTH, BOUNDS_HEIGHT, 80, MIN_SIZE, ZoomMode.INTEGER);

        assertEquals(2, entries.size());
        assertFalse(entries.get(0).isSelected());
        assertTrue("the actual-size row is the same size, so it is the selected one",
                entries.get(1).isSelected());
    }

    @Test
    public void listedIntegerPreset_isSelectedForTheIntegerMode() {
        List<ViewerZoomMenuModel.Entry> entries = ViewerZoomMenuModel.build(CONTENT_WIDTH,
                CONTENT_HEIGHT, 0, BOUNDS_WIDTH, BOUNDS_HEIGHT, 60, MIN_SIZE, ZoomMode.INTEGER);

        assertFalse(entries.get(0).isSelected());
        assertFalse(entries.get(1).isSelected());
        assertTrue(entries.get(2).isSelected());
    }

    @Test
    public void exactlyOneRowIsSelected() {
        ZoomMode[] modes = {ZoomMode.FIT, ZoomMode.ONE_TO_ONE, ZoomMode.INTEGER};
        int[] percents = {80, 60, 33, 25};
        for (ZoomMode mode : modes) {
            for (int percent : percents) {
                List<ViewerZoomMenuModel.Entry> entries = ViewerZoomMenuModel.build(CONTENT_WIDTH,
                        CONTENT_HEIGHT, 0, BOUNDS_WIDTH, BOUNDS_HEIGHT, percent, MIN_SIZE, mode);
                int selected = 0;
                for (ViewerZoomMenuModel.Entry entry : entries) {
                    if (entry.isSelected()) {
                        selected++;
                    }
                }
                assertEquals("mode " + mode + " at " + percent + "% must select exactly one row",
                        1, selected);
            }
        }
    }

    @Test
    public void rotatedStream_swapsTheAxesBeforeReportingTheScale() {
        // A landscape stream shown on a portrait display: 2340x1080 rotated by 90 degrees behaves
        // like 1080x2340 for the menu, so the same percentages apply.
        List<ViewerZoomMenuModel.Entry> rotated = ViewerZoomMenuModel.build(CONTENT_HEIGHT,
                CONTENT_WIDTH, 90, BOUNDS_WIDTH, BOUNDS_HEIGHT, 80, MIN_SIZE, ZoomMode.FIT);
        List<ViewerZoomMenuModel.Entry> upright = ViewerZoomMenuModel.build(CONTENT_WIDTH,
                CONTENT_HEIGHT, 0, BOUNDS_WIDTH, BOUNDS_HEIGHT, 80, MIN_SIZE, ZoomMode.FIT);

        assertEquals(upright.size(), rotated.size());
        for (int i = 0; i < upright.size(); i++) {
            assertEquals(upright.get(i).getMode(), rotated.get(i).getMode());
            assertEquals(upright.get(i).getEffectivePercent(),
                    rotated.get(i).getEffectivePercent());
        }
    }

    @Test
    public void unknownGeometry_keepsEveryRowAtTheViewingPercentage() {
        List<ViewerZoomMenuModel.Entry> entries = ViewerZoomMenuModel.build(0, 0, 0,
                BOUNDS_WIDTH, BOUNDS_HEIGHT, 80, MIN_SIZE, ZoomMode.FIT);

        // Nothing is deduplicated before the first descriptor: every row falls back to the same
        // percentage, and hiding a row there would leave the menu without a mode the user stored.
        assertEquals(3, entries.size());
        for (ViewerZoomMenuModel.Entry entry : entries) {
            assertEquals(80, entry.getEffectivePercent());
        }
    }

    @Test
    public void entriesAreImmutable() {
        List<ViewerZoomMenuModel.Entry> entries = ViewerZoomMenuModel.build(CONTENT_WIDTH,
                CONTENT_HEIGHT, 0, BOUNDS_WIDTH, BOUNDS_HEIGHT, 60, MIN_SIZE, ZoomMode.FIT);
        assertNotNull(entries);
        try {
            entries.add(null);
            org.junit.Assert.fail("the menu projection must be immutable");
        } catch (UnsupportedOperationException expected) {
            assertNotNull(expected);
        }
    }
}
