package com.obby.android.localscreenshare.core.net;

import com.obby.android.localscreenshare.core.support.MutableNanoClock;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link DatagramLossTracker}.
 *
 * <p>The tracker decides when the viewer asks for a key frame, so the tests pin both the evidence
 * that is enough and the evidence that is not: a single loss, a stale loss outside the window and a
 * request inside its cooldown must all stay quiet.</p>
 */
public class DatagramLossTrackerTest {

    private final MutableNanoClock clock = new MutableNanoClock(1_000_000_000L);

    private final DatagramLossTracker tracker = new DatagramLossTracker(clock);

    @Test
    public void oneLoss_isNotEnough() {
        tracker.onAccessUnitIncomplete();

        assertFalse(tracker.shouldRequestJoinPoint());
        assertEquals(1, tracker.getConsecutiveIncomplete());
    }

    @Test
    public void twoConsecutiveLosses_triggerARequest() {
        tracker.onAccessUnitIncomplete();
        tracker.onAccessUnitIncomplete();

        assertTrue(tracker.shouldRequestJoinPoint());
        assertEquals(0, tracker.getConsecutiveIncomplete());
    }

    @Test
    public void threeLossesInTheWindow_triggerARequestEvenWithGoodUnitsBetween() {
        tracker.onAccessUnitIncomplete();
        tracker.onAccessUnitComplete();
        clock.advance(100_000_000L);
        tracker.onAccessUnitIncomplete();
        tracker.onAccessUnitComplete();
        clock.advance(100_000_000L);

        assertFalse(tracker.shouldRequestJoinPoint());

        tracker.onAccessUnitIncomplete();

        assertTrue(tracker.shouldRequestJoinPoint());
        assertEquals(0, tracker.getWindowCount());
    }

    @Test
    public void lossesOlderThanTheWindow_areForgotten() {
        tracker.onAccessUnitIncomplete();
        tracker.onAccessUnitComplete();
        clock.advance(DatagramLossTracker.WINDOW_NS + 1L);
        tracker.onAccessUnitIncomplete();
        tracker.onAccessUnitComplete();
        clock.advance(100_000_000L);
        tracker.onAccessUnitIncomplete();

        assertFalse(tracker.shouldRequestJoinPoint());
        assertEquals(2, tracker.getWindowCount());
    }

    @Test
    public void cooldown_blocksASecondRequest() {
        tracker.onAccessUnitIncomplete();
        tracker.onAccessUnitIncomplete();
        assertTrue(tracker.shouldRequestJoinPoint());

        clock.advance(DatagramLossTracker.COOLDOWN_NS - 1L);
        tracker.onAccessUnitIncomplete();
        tracker.onAccessUnitIncomplete();
        assertFalse(tracker.shouldRequestJoinPoint());

        clock.advance(1L);
        assertTrue(tracker.shouldRequestJoinPoint());
    }

    @Test
    public void completingAUnit_resetsTheConsecutiveCount() {
        tracker.onAccessUnitIncomplete();
        tracker.onAccessUnitComplete();

        assertEquals(0, tracker.getConsecutiveIncomplete());

        tracker.onAccessUnitIncomplete();
        assertFalse(tracker.shouldRequestJoinPoint());
    }

    @Test
    public void aGrantedRequest_clearsTheEvidence() {
        tracker.onAccessUnitIncomplete();
        tracker.onAccessUnitIncomplete();
        assertTrue(tracker.shouldRequestJoinPoint());

        clock.advance(DatagramLossTracker.COOLDOWN_NS);
        assertFalse(tracker.shouldRequestJoinPoint());
        assertEquals(0, tracker.getConsecutiveIncomplete());
        assertEquals(0, tracker.getWindowCount());
    }
}
