package com.obby.android.localscreenshare.render;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * JVM tests for the viewing-percentage menu projection.
 *
 * <p>The device report that motivated this model: the viewer's viewing percentage had no entry in
 * the user interface at all — the case that exercised 60% had to write the preference through
 * {@code run-as}. The rows therefore have to offer the presets, keep ascending order, and mark the
 * value that is really applied instead of pretending a neighbouring preset is selected.</p>
 */
public class ViewerScaleMenuModelTest {

    @Test
    public void presetsAreListedInAscendingOrder() {
        List<ViewerScaleMenuModel.Entry> entries = ViewerScaleMenuModel.build(80);

        assertEquals(Arrays.asList(60, 80, 100), percents(entries));
    }

    @Test
    public void appliedPreset_isTheOnlySelectedRow() {
        List<ViewerScaleMenuModel.Entry> entries = ViewerScaleMenuModel.build(80);

        for (ViewerScaleMenuModel.Entry entry : entries) {
            assertEquals("only the applied percentage may be marked",
                    entry.getPercent() == 80, entry.isSelected());
        }
    }

    @Test
    public void appliedPercentageOutsideThePresets_isListedInItsPlace() {
        List<ViewerScaleMenuModel.Entry> entries = ViewerScaleMenuModel.build(75);

        assertEquals(Arrays.asList(60, 75, 80, 100), percents(entries));
        for (ViewerScaleMenuModel.Entry entry : entries) {
            assertEquals(entry.getPercent() == 75, entry.isSelected());
        }
    }

    @Test
    public void appliedPercentageBelowTheSmallestPreset_isListedFirst() {
        List<ViewerScaleMenuModel.Entry> entries = ViewerScaleMenuModel.build(35);

        assertEquals(Arrays.asList(35, 60, 80, 100), percents(entries));
        assertTrue(entries.get(0).isSelected());
        assertFalse(entries.get(1).isSelected());
    }

    @Test
    public void percentageAboveTheRange_isClampedWithoutDuplicatingARow() {
        List<ViewerScaleMenuModel.Entry> entries = ViewerScaleMenuModel.build(140);

        assertEquals(Arrays.asList(60, 80, 100), percents(entries));
        assertTrue(entries.get(entries.size() - 1).isSelected());
    }

    @Test
    public void percentageBelowTheRange_isClampedToOneRow() {
        List<ViewerScaleMenuModel.Entry> entries = ViewerScaleMenuModel.build(0);

        assertEquals(Arrays.asList(ViewerScaleMenuModel.MIN_PERCENT, 60, 80, 100),
                percents(entries));
        assertTrue(entries.get(0).isSelected());
    }

    private static List<Integer> percents(List<ViewerScaleMenuModel.Entry> entries) {
        List<Integer> percents = new ArrayList<>(entries.size());
        for (ViewerScaleMenuModel.Entry entry : entries) {
            percents.add(entry.getPercent());
        }
        return percents;
    }
}
