package com.obby.android.localscreenshare.render;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Geometry of the last-frame snapshot animation between the floating window and the activity.
 *
 * <p>The snapshot view is laid out at the destination rectangle, so the animation only needs the
 * scale and translation that put it on the source rectangle. The tests pin the two properties the
 * transition relies on: an identical pair of rectangles produces no visible offset, and a smaller
 * source produces a correspondingly smaller start scale with the center difference as translation.</p>
 */
public class ViewerTransitionGeometryTest {

    private static final float EPSILON = 1e-4f;

    @Test
    public void identicalRectanglesLeaveTheSnapshotInPlace() {
        ViewerTransitionGeometry.Start start = ViewerTransitionGeometry.compute(
            100, 200, 300, 400, 100, 200, 300, 400);

        assertFalse(start.isVisible());
        assertEquals(1f, start.getScaleX(), EPSILON);
        assertEquals(0f, start.getTranslationX(), EPSILON);
    }

    @Test
    public void aSmallerSourceScalesTheSnapshotDownAroundTheDestinationCenter() {
        ViewerTransitionGeometry.Start start = ViewerTransitionGeometry.compute(
            350, 300, 300, 400, 200, 100, 600, 800);

        assertEquals(0.5f, start.getScaleX(), EPSILON);
        assertEquals(0.5f, start.getScaleY(), EPSILON);
        // Both rectangles share the same center, so there is nothing to translate.
        assertEquals(0f, start.getTranslationX(), EPSILON);
        assertEquals(0f, start.getTranslationY(), EPSILON);
    }

    @Test
    public void anOffsetSourceTranslatesTowardsItsOwnCenter() {
        ViewerTransitionGeometry.Start start = ViewerTransitionGeometry.compute(
            0, 0, 100, 100, 300, 300, 100, 100);

        assertTrue(start.isVisible());
        assertEquals(1f, start.getScaleX(), EPSILON);
        // Source center (50, 50) minus target center (350, 350).
        assertEquals(-300f, start.getTranslationX(), EPSILON);
        assertEquals(-300f, start.getTranslationY(), EPSILON);
    }

    @Test
    public void nonUniformRectanglesScaleEachAxisSeparately() {
        ViewerTransitionGeometry.Start start = ViewerTransitionGeometry.compute(
            0, 0, 200, 100, 0, 0, 400, 400);

        assertEquals(0.5f, start.getScaleX(), EPSILON);
        assertEquals(0.25f, start.getScaleY(), EPSILON);
    }

    @Test
    public void aDegenerateRectangleDisablesTheAnimation() {
        ViewerTransitionGeometry.Start start = ViewerTransitionGeometry.compute(
            0, 0, 100, 100, 0, 0, 0, 400);

        assertFalse(start.isVisible());
        assertEquals(ViewerTransitionGeometry.Start.NONE, start);
    }

    @Test
    public void ignoringTheAnimationKeepsTheSnapshotAtTheDestination() {
        assertFalse(ViewerTransitionGeometry.Start.NONE.isVisible());
        assertEquals(1f, ViewerTransitionGeometry.Start.NONE.getScaleX(), EPSILON);
        assertEquals(1f, ViewerTransitionGeometry.Start.NONE.getScaleY(), EPSILON);
    }
}
