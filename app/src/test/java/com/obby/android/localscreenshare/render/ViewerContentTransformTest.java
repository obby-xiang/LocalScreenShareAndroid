package com.obby.android.localscreenshare.render;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

/**
 * Geometry of the texture transform that maps decoded content into its display box.
 *
 * <p>The floating window sizes itself to the content, so its box equals the view and the transform
 * must reproduce exactly what the window computed before the shared panel existed. The full-screen
 * activity fills the display and letterboxes the content, which is the case the old window-only code
 * never had to handle. Both are pure arithmetic, so both are pinned here.</p>
 */
public class ViewerContentTransformTest {

    private static final float EPSILON = 1e-4f;

    @Test
    public void anUnattachedViewYieldsTheIdentity() {
        assertSame(ViewerContentTransform.IDENTITY,
            ViewerContentTransform.compute(0, 0, 300, 200, 90));
        assertSame(ViewerContentTransform.IDENTITY,
            ViewerContentTransform.compute(300, 0, 300, 200, 0));
    }

    @Test
    public void aBoxEqualToTheViewNeedsNoScalingWhenUnrotated() {
        ViewerContentTransform transform = ViewerContentTransform.compute(300, 200, 0, 0, 0);

        assertEquals(1f, transform.getScaleX(), EPSILON);
        assertEquals(1f, transform.getScaleY(), EPSILON);
        assertEquals(0, transform.getRotationDegrees());
        assertEquals(150f, transform.getCenterX(), EPSILON);
        assertEquals(100f, transform.getCenterY(), EPSILON);
    }

    @Test
    public void aQuarterTurnSwapsTheScaleFactorsAroundTheViewCenter() {
        // This is the formula the window used: scale by height/width and width/height, then rotate.
        ViewerContentTransform transform = ViewerContentTransform.compute(300, 200, 300, 200, 90);

        assertEquals(200f / 300f, transform.getScaleX(), EPSILON);
        assertEquals(300f / 200f, transform.getScaleY(), EPSILON);
        assertEquals(90, transform.getRotationDegrees());
    }

    @Test
    public void aLetterboxedBoxOnAFullScreenViewScalesBothAxes() {
        ViewerContentTransform transform = ViewerContentTransform.compute(1000, 1000, 800, 450, 0);

        assertEquals(0.8f, transform.getScaleX(), EPSILON);
        assertEquals(0.45f, transform.getScaleY(), EPSILON);
        assertEquals(500f, transform.getCenterX(), EPSILON);
    }

    @Test
    public void aLetterboxedBoxOfAQuarterTurnStreamUsesTheSwappedBox() {
        ViewerContentTransform transform = ViewerContentTransform.compute(1000, 1000, 800, 450, 270);

        // Before the rotation the picture occupies a 450x800 box, after it 800x450.
        assertEquals(0.45f, transform.getScaleX(), EPSILON);
        assertEquals(0.8f, transform.getScaleY(), EPSILON);
        assertEquals(270, transform.getRotationDegrees());
    }

    @Test
    public void aHalfTurnRotatesWithoutScaling() {
        ViewerContentTransform transform = ViewerContentTransform.compute(300, 200, 300, 200, 180);

        assertEquals(1f, transform.getScaleX(), EPSILON);
        assertEquals(1f, transform.getScaleY(), EPSILON);
        assertEquals(180, transform.getRotationDegrees());
    }

    @Test
    public void anUnsupportedRotationIsIgnored() {
        ViewerContentTransform transform = ViewerContentTransform.compute(300, 200, 300, 200, 45);

        assertEquals(0, transform.getRotationDegrees());
        assertEquals(1f, transform.getScaleX(), EPSILON);
    }

    @Test
    public void theResultIsIndependentOfViewAndDisplayOrientationPair() {
        // A portrait phone showing a landscape stream: the box is the letterboxed content.
        ViewerContentTransform transform = ViewerContentTransform.compute(1080, 2340, 1080, 607, 0);

        assertFalse(transform == ViewerContentTransform.IDENTITY);
        assertEquals(1f, transform.getScaleX(), EPSILON);
        assertEquals(607f / 2340f, transform.getScaleY(), EPSILON);
    }
}
