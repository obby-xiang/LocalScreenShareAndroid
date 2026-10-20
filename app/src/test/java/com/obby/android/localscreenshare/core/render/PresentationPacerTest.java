package com.obby.android.localscreenshare.core.render;

import static org.junit.Assert.assertEquals;

import com.obby.android.localscreenshare.core.render.PresentationPacer.Decision;

import org.junit.Test;

/**
 * Unit tests for {@link PresentationPacer}.
 *
 * <p>Times are expressed as offsets from a fixed base so the expected decisions read as "late",
 * "on time" and "early" instead of absolute nanosecond values.</p>
 */
public class PresentationPacerTest {

    private static final long NOW = 1_000_000_000L;

    private static final long FRAME_NS = 33_000_000L;

    private final PresentationPacer pacer = new PresentationPacer();

    @Test
    public void firstFrame_isPresentedImmediately() {
        assertEquals(Decision.PRESENT_NOW,
                pacer.decide(NOW, NOW, PresentationPacer.UNSET, 0L));
    }

    @Test
    public void lateFrame_isPresentedImmediately() {
        assertEquals(Decision.PRESENT_NOW,
                pacer.decide(NOW, NOW - 3 * FRAME_NS, PresentationPacer.UNSET, 0L));
        assertEquals(Decision.PRESENT_NOW,
                pacer.decide(NOW, NOW - 3 * FRAME_NS, NOW - 5 * FRAME_NS, 0L));
    }

    @Test
    public void exactTarget_isPresentedNow() {
        assertEquals(Decision.PRESENT_NOW,
                pacer.decide(NOW, NOW, NOW - FRAME_NS, 0L));
    }

    @Test
    public void futureTarget_isScheduledAtItsTimestamp() {
        assertEquals(Decision.SCHEDULE_AT,
                pacer.decide(NOW, NOW + FRAME_NS, NOW - FRAME_NS, 0L));
    }

    @Test
    public void zeroMinimumInterval_presentsEveryNonStaleFrame() {
        long lastPresented = NOW - 2 * FRAME_NS;

        assertEquals(Decision.PRESENT_NOW, pacer.decide(NOW, NOW, lastPresented, 0L));
        assertEquals(Decision.PRESENT_NOW, pacer.decide(NOW, NOW - FRAME_NS, lastPresented, 0L));
    }

    @Test
    public void minimumInterval_dropsFramesInsideTheWindow() {
        long lastPresented = NOW - 10_000_000L;

        assertEquals(Decision.DROP, pacer.decide(NOW, NOW, lastPresented, FRAME_NS));
        assertEquals(Decision.PRESENT_NOW,
                pacer.decide(NOW, NOW, lastPresented, 10_000_000L));
    }

    @Test
    public void minimumInterval_allowsAScheduledFrameOutsideTheWindow() {
        long lastPresented = NOW - 10_000_000L;

        assertEquals(Decision.SCHEDULE_AT,
                pacer.decide(NOW, NOW + 30_000_000L, lastPresented, FRAME_NS));
    }

    @Test
    public void staleFrame_isDroppedEvenWithZeroInterval() {
        assertEquals(Decision.DROP,
                pacer.decide(NOW, NOW - 2 * FRAME_NS, NOW - FRAME_NS, 0L));
    }

    @Test
    public void clockMovedBackwards_doesNotStallTheStream() {
        long lastPresented = NOW + 5_000_000_000L;

        assertEquals(Decision.PRESENT_NOW, pacer.decide(NOW, NOW, lastPresented, FRAME_NS));
        assertEquals(Decision.SCHEDULE_AT,
                pacer.decide(NOW, NOW + FRAME_NS, lastPresented, FRAME_NS));
    }

    @Test
    public void decisionIsDeterministic() {
        for (int i = 0; i < 3; i++) {
            assertEquals(Decision.SCHEDULE_AT,
                    pacer.decide(NOW, NOW + FRAME_NS, NOW - FRAME_NS, FRAME_NS));
        }
    }
}
