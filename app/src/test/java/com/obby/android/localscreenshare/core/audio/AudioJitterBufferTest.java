package com.obby.android.localscreenshare.core.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link AudioJitterBuffer}.
 */
public class AudioJitterBufferTest {

    private static final long FRAME_US = 21_333L;

    private static final long FRAME_MS = 21L;

    @Test
    public void prefillHoldsPlaybackUntilTheTargetDepth() {
        AudioJitterBuffer buffer = new AudioJitterBuffer();

        AudioJitterBuffer.Plan first = buffer.plan(0L, FRAME_US, 0, 0L);
        AudioJitterBuffer.Plan second = buffer.plan(21_333L, FRAME_US, 1, 0L);
        AudioJitterBuffer.Plan third = buffer.plan(42_666L, FRAME_US, 2, 0L);

        assertTrue(first.isAccept());
        assertFalse(first.isStartPlayback());
        assertFalse(second.isStartPlayback());
        assertTrue(third.isStartPlayback());
        assertTrue(buffer.isStarted());
    }

    @Test
    public void prefillStartsAfterTheWaitLimitEvenWithOneFrame() {
        AudioJitterBuffer buffer = new AudioJitterBuffer();

        assertFalse(buffer.plan(0L, FRAME_US, 0, 0L).isStartPlayback());
        assertFalse(buffer.plan(21_333L, FRAME_US, 0, 100L).isStartPlayback());
        assertTrue(buffer.plan(42_666L, FRAME_US, 0,
                AudioJitterBuffer.MAX_PREFILL_WAIT_MS).isStartPlayback());
    }

    @Test
    public void aGapBecomesSilenceOfTheSameLength() {
        AudioJitterBuffer buffer = new AudioJitterBuffer();
        buffer.plan(0L, FRAME_US, 2, 0L);

        AudioJitterBuffer.Plan plan = buffer.plan(3 * FRAME_US, FRAME_US, 2, 0L);

        assertEquals(2, plan.getSilenceFrames());
        assertTrue(plan.isAccept());
        assertEquals(2, buffer.getSilencedFrames());
    }

    @Test
    public void framesThatGoBackwardsAreDropped() {
        AudioJitterBuffer buffer = new AudioJitterBuffer();
        buffer.plan(100_000L, FRAME_US, 2, 0L);

        AudioJitterBuffer.Plan plan = buffer.plan(50_000L, FRAME_US, 2, 0L);

        assertFalse(plan.isAccept());
        assertEquals(1, buffer.getDroppedFrames());
    }

    @Test
    public void exceedingTheMaximumDepthDropsTheOldestFrames() {
        AudioJitterBuffer buffer = new AudioJitterBuffer();
        buffer.plan(0L, FRAME_US, 2, 0L);

        // Nine buffered frames plus this one is 210 ms, above the 150 ms bound; the buffer is
        // drained back to 90 ms, which is six frames.
        AudioJitterBuffer.Plan plan = buffer.plan(9 * FRAME_US, FRAME_US, 9, 0L);

        assertEquals(6, plan.getDropOldestFrames());
        assertTrue(plan.isAccept());
    }

    @Test
    public void depthBelowTheBoundNeverDrops() {
        AudioJitterBuffer buffer = new AudioJitterBuffer();
        buffer.plan(0L, FRAME_US, 2, 0L);

        AudioJitterBuffer.Plan plan = buffer.plan(FRAME_US, FRAME_US, 6, 0L);

        assertEquals(0, plan.getDropOldestFrames());
    }

    @Test
    public void underrunProducesOneSilenceFrame() {
        AudioJitterBuffer buffer = new AudioJitterBuffer();

        assertEquals(1, buffer.onUnderrun(FRAME_US));
        assertEquals(1, buffer.getUnderruns());
        assertEquals(1, buffer.getSilencedFrames());
    }

    @Test
    public void driftGuardRespectsThresholdAndCooldown() {
        AudioJitterBuffer buffer = new AudioJitterBuffer();
        final long expected = 1_000_000L;

        assertEquals(0, buffer.driftCorrectionFrames(expected, expected + 20_000L, FRAME_US, 0L));
        assertEquals(-1, buffer.driftCorrectionFrames(expected, expected + 60_000L, FRAME_US, 0L));
        assertEquals(0, buffer.driftCorrectionFrames(expected, expected + 60_000L, FRAME_US, 199L));
        assertEquals(1, buffer.driftCorrectionFrames(expected, expected - 60_000L, FRAME_US,
                AudioJitterBuffer.DRIFT_COOLDOWN_MS));
        assertEquals(0, buffer.driftCorrectionFrames(expected, AudioJitterBuffer.UNSET, FRAME_US,
                10_000L));
    }

    @Test
    public void resetClearsTimingDecisions() {
        AudioJitterBuffer buffer = new AudioJitterBuffer();
        buffer.plan(0L, FRAME_US, 2, 0L);
        buffer.plan(FRAME_US, FRAME_US, 2, 0L);
        assertTrue(buffer.isStarted());

        buffer.reset();

        assertFalse(buffer.isStarted());
        assertEquals(0, buffer.plan(0L, FRAME_US, 0, 0L).getSilenceFrames());
        assertFalse(buffer.plan(0L, FRAME_US, 0, 0L).isStartPlayback());
    }

    @Test
    public void frameDurationFallsBackToTheAacDefault() {
        AudioJitterBuffer buffer = new AudioJitterBuffer();
        buffer.plan(0L, 0L, 2, 0L);

        AudioJitterBuffer.Plan plan = buffer.plan(2 * 21_333L, 0L, 2, 0L);

        assertEquals(1, plan.getSilenceFrames());
    }
}
