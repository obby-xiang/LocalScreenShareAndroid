package com.obby.android.localscreenshare.service;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * JVM tests for the capture-size transition.
 *
 * <p>The device report that motivated this class: the display was resized before the encoder was
 * switched, so an encoder that rejected the odd size left the display shrunk under an encoder that
 * still encoded the old geometry — a squeezed viewer picture with a black band, with the rejected
 * percentage still stored as if it had been applied. The transition is therefore expected to ask
 * the encoder first and to change nothing at all when the encoder refuses.</p>
 */
public class ProjectionSizeCoordinatorTest {

    @Test
    public void acceptedSize_resizesTheDisplayAfterTheEncoderSwitch() {
        List<String> calls = new ArrayList<>();

        ProjectionSizeCoordinator.Outcome outcome = ProjectionSizeCoordinator.apply(884, 1918,
                (width, height) -> {
                    calls.add("encoder " + width + "x" + height);
                    return true;
                },
                (width, height) -> calls.add("display " + width + "x" + height));

        assertEquals(ProjectionSizeCoordinator.Outcome.APPLIED, outcome);
        assertEquals(Arrays.asList("encoder 884x1918", "display 884x1918"), calls);
    }

    @Test
    public void rejectedSize_leavesTheDisplayUntouched() {
        List<String> calls = new ArrayList<>();

        ProjectionSizeCoordinator.Outcome outcome = ProjectionSizeCoordinator.apply(885, 1918,
                (width, height) -> {
                    calls.add("encoder " + width + "x" + height);
                    return false;
                },
                (width, height) -> calls.add("display " + width + "x" + height));

        assertEquals(ProjectionSizeCoordinator.Outcome.REJECTED, outcome);
        assertEquals(Arrays.asList("encoder 885x1918"), calls);
    }

    @Test
    public void capturePathWithoutAnEncoder_resizesTheDisplay() {
        List<String> calls = new ArrayList<>();

        ProjectionSizeCoordinator.Outcome outcome = ProjectionSizeCoordinator.apply(884, 1918, null,
                (width, height) -> calls.add("display " + width + "x" + height));

        assertEquals(ProjectionSizeCoordinator.Outcome.APPLIED, outcome);
        assertEquals(Arrays.asList("display 884x1918"), calls);
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingDisplay_isRejected() {
        ProjectionSizeCoordinator.apply(884, 1918, null, null);
    }
}
