package com.obby.android.localscreenshare.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link ViewerHudTextPolicy}.
 *
 * <p>The numbers follow the overlay scenarios from the metrics device report: the full-screen host
 * and a normal sized floating window must keep the legacy 11sp overlay, the 60% window must shrink
 * it, and the narrowest window must stop at the minimum size and leave truncation to the view. All
 * arithmetic is integer, so the expected sizes are exact pixel counts.</p>
 */
public class ViewerHudTextPolicyTest {

    private static final int MAX_SIZE = 30;

    private static final int MIN_SIZE = 24;

    @Test
    public void fitTextSizePx_widestLineFits_keepsMaximum() {
        assertEquals(MAX_SIZE, ViewerHudTextPolicy.fitTextSizePx(1000, MAX_SIZE, MIN_SIZE, 800));
    }

    @Test
    public void fitTextSizePx_widestLineFillsWidthExactly_keepsMaximum() {
        assertEquals(MAX_SIZE, ViewerHudTextPolicy.fitTextSizePx(800, MAX_SIZE, MIN_SIZE, 800));
    }

    @Test
    public void fitTextSizePx_halfWidth_halvesTheSize() {
        assertEquals(15, ViewerHudTextPolicy.fitTextSizePx(500, MAX_SIZE, 12, 1000));
    }

    @Test
    public void fitTextSizePx_slightlyNarrowerWindow_shrinksProportionally() {
        assertEquals(27, ViewerHudTextPolicy.fitTextSizePx(900, MAX_SIZE, MIN_SIZE, 1000));
    }

    @Test
    public void fitTextSizePx_fractionalScale_floorsToWholePixels() {
        assertEquals(25, ViewerHudTextPolicy.fitTextSizePx(850, MAX_SIZE, MIN_SIZE, 1000));
    }

    @Test
    public void fitTextSizePx_extremelyNarrow_clampsToMinimum() {
        assertEquals(MIN_SIZE, ViewerHudTextPolicy.fitTextSizePx(100, MAX_SIZE, MIN_SIZE, 1000));
    }

    @Test
    public void fitTextSizePx_scaledLineNeverExceedsTheAvailableWidth() {
        for (int available = MIN_SIZE; available <= 2000; available += 7) {
            final int widest = 1200;
            final int size = ViewerHudTextPolicy.fitTextSizePx(available, MAX_SIZE, MIN_SIZE, widest);
            if (size > MIN_SIZE) {
                assertTrue("available " + available + " size " + size,
                    (long) widest * size / MAX_SIZE <= available);
            }
            assertTrue(size >= MIN_SIZE && size <= MAX_SIZE);
        }
    }

    @Test
    public void fitTextSizePx_narrowerWidth_neverIncreasesTheSize() {
        int previous = MAX_SIZE;
        for (int available = 2000; available > 0; available -= 11) {
            final int size = ViewerHudTextPolicy.fitTextSizePx(available, MAX_SIZE, MIN_SIZE, 1200);
            assertTrue("available " + available + ": " + size + " > " + previous, size <= previous);
            previous = size;
        }
    }

    @Test
    public void fitTextSizePx_unknownWidthOrText_keepsMaximum() {
        assertEquals(MAX_SIZE, ViewerHudTextPolicy.fitTextSizePx(0, MAX_SIZE, MIN_SIZE, 1000));
        assertEquals(MAX_SIZE, ViewerHudTextPolicy.fitTextSizePx(-40, MAX_SIZE, MIN_SIZE, 1000));
        assertEquals(MAX_SIZE, ViewerHudTextPolicy.fitTextSizePx(500, MAX_SIZE, MIN_SIZE, 0));
        assertEquals(MAX_SIZE, ViewerHudTextPolicy.fitTextSizePx(500, MAX_SIZE, MIN_SIZE, -1));
    }

    @Test
    public void fitTextSizePx_minimumEqualsMaximum_returnsThatSize() {
        assertEquals(MAX_SIZE, ViewerHudTextPolicy.fitTextSizePx(100, MAX_SIZE, MAX_SIZE, 1000));
    }

    @Test
    public void fitTextSizePx_invalidBounds_rejected() {
        assertThrows(IllegalArgumentException.class,
            () -> ViewerHudTextPolicy.fitTextSizePx(500, 0, 0, 1000));
        assertThrows(IllegalArgumentException.class,
            () -> ViewerHudTextPolicy.fitTextSizePx(500, MAX_SIZE, 0, 1000));
        assertThrows(IllegalArgumentException.class,
            () -> ViewerHudTextPolicy.fitTextSizePx(500, MIN_SIZE, MAX_SIZE, 1000));
    }

    @Test
    public void overlayBounds_keepTheLegacySizeAboveTheMinimum() {
        assertTrue(ViewerHudTextPolicy.MIN_TEXT_SIZE_SP < ViewerHudTextPolicy.MAX_TEXT_SIZE_SP);
        assertEquals(11f, ViewerHudTextPolicy.MAX_TEXT_SIZE_SP, 1e-6f);
        assertEquals(9f, ViewerHudTextPolicy.MIN_TEXT_SIZE_SP, 1e-6f);
    }
}
