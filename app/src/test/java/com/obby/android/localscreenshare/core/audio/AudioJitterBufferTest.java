package com.obby.android.localscreenshare.core.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link AudioJitterBuffer}.
 *
 * <p>Every {@code plan} call passes the media time buffered ahead of the playhead, which is the
 * quantity the session measures; a test that wants {@code n} buffered frames passes
 * {@code n * FRAME_US} microseconds.</p>
 */
public class AudioJitterBufferTest {

    private static final long FRAME_US = 21_333L;

    private static final long FRAME_MS = 21L;

    @Test
    public void prefillHoldsPlaybackUntilTheTargetDepth() {
        AudioJitterBuffer buffer = new AudioJitterBuffer();

        for (int bufferedFrames = 0; bufferedFrames < 5; bufferedFrames++) {
            AudioJitterBuffer.Plan plan = buffer.plan(bufferedFrames * FRAME_US, FRAME_US,
                    bufferedFrames * FRAME_US, 0L);

            assertTrue(plan.isAccept());
            assertFalse(plan.isStartPlayback());
        }

        // Five buffered frames plus this one are 128 ms, above the 120 ms target.
        AudioJitterBuffer.Plan last = buffer.plan(5 * FRAME_US, FRAME_US, 5 * FRAME_US, 0L);

        assertTrue(last.isStartPlayback());
        assertTrue(buffer.isStarted());
    }

    @Test
    public void prefillStartsAfterTheWaitLimitEvenWithNothingBuffered() {
        AudioJitterBuffer buffer = new AudioJitterBuffer();

        assertFalse(buffer.plan(0L, FRAME_US, 0L, 0L).isStartPlayback());
        assertFalse(buffer.plan(FRAME_US, FRAME_US, 0L, 100L).isStartPlayback());
        assertTrue(buffer.plan(2 * FRAME_US, FRAME_US, 0L,
                AudioJitterBuffer.MAX_PREFILL_WAIT_MS).isStartPlayback());
    }

    @Test
    public void aLongSessionWithNothingBufferedNeverDrops() {
        AudioJitterBuffer buffer = new AudioJitterBuffer();
        buffer.plan(0L, FRAME_US, AudioJitterBuffer.TARGET_DEPTH_MS * 1_000L, 0L);
        assertTrue(buffer.isStarted());

        // The regression this pins: a depth derived from the number of frames received grows
        // without bound, so every frame after the eight one asked to drop (depth - 90 ms) frames.
        for (int i = 1; i <= 5_000; i++) {
            AudioJitterBuffer.Plan plan = buffer.plan(i * FRAME_US, FRAME_US, 0L, i * FRAME_MS);

            assertEquals(0, plan.getDropOldestFrames());
        }

        assertEquals(0, buffer.getDroppedFrames());
    }

    @Test
    public void aDeepBacklogIsDrainedToTheRecoverDepth() {
        AudioJitterBuffer buffer = new AudioJitterBuffer();
        buffer.plan(0L, FRAME_US, AudioJitterBuffer.TARGET_DEPTH_MS * 1_000L, 0L);

        // Eleven buffered frames plus this one are 255 ms, above the 250 ms bound; the buffer is
        // drained back to 150 ms, which is five frames.
        AudioJitterBuffer.Plan plan = buffer.plan(FRAME_US, FRAME_US, 11 * FRAME_US, 0L);

        assertEquals(5, plan.getDropOldestFrames());
        assertTrue(plan.isAccept());
    }

    @Test
    public void depthBelowTheBoundNeverDrops() {
        AudioJitterBuffer buffer = new AudioJitterBuffer();
        buffer.plan(0L, FRAME_US, AudioJitterBuffer.TARGET_DEPTH_MS * 1_000L, 0L);

        AudioJitterBuffer.Plan plan = buffer.plan(FRAME_US, FRAME_US, 6 * FRAME_US, 0L);

        assertEquals(0, plan.getDropOldestFrames());
    }

    @Test
    public void aGapBecomesSilenceOfTheSameLength() {
        AudioJitterBuffer buffer = new AudioJitterBuffer();
        buffer.plan(0L, FRAME_US, 2 * FRAME_US, 0L);

        AudioJitterBuffer.Plan plan = buffer.plan(3 * FRAME_US, FRAME_US, 2 * FRAME_US, 0L);

        assertEquals(2, plan.getSilenceFrames());
        assertTrue(plan.isAccept());
        assertEquals(2, buffer.getSilencedFrames());
    }

    @Test
    public void framesThatGoBackwardsAreDropped() {
        AudioJitterBuffer buffer = new AudioJitterBuffer();
        buffer.plan(100_000L, FRAME_US, 2 * FRAME_US, 0L);

        AudioJitterBuffer.Plan plan = buffer.plan(50_000L, FRAME_US, 2 * FRAME_US, 0L);

        assertFalse(plan.isAccept());
        assertEquals(1, buffer.getDroppedFrames());
    }

    @Test
    public void aShortGapIsFilledByTheMeasuredAmount() {
        AudioJitterBuffer buffer = new AudioJitterBuffer();

        AudioJitterBuffer.Underrun underrun = buffer.onUnderrun(64L, FRAME_US);

        assertFalse(underrun.isRestart());
        // 64 ms of missing media is four whole frames, not the single frame the old recovery wrote.
        assertEquals(4, underrun.getSilenceFrames());
        assertEquals(1, buffer.getUnderruns());
        assertEquals(4, buffer.getSilencedFrames());
    }

    @Test
    public void aGapBelowOneFrameAsksForOneFrame() {
        AudioJitterBuffer buffer = new AudioJitterBuffer();

        AudioJitterBuffer.Underrun underrun = buffer.onUnderrun(3L, FRAME_US);

        assertFalse(underrun.isRestart());
        assertEquals(1, underrun.getSilenceFrames());
    }

    @Test
    public void aLongGapRestartsPlaybackInsteadOfPadding() {
        AudioJitterBuffer buffer = new AudioJitterBuffer();

        AudioJitterBuffer.Underrun underrun =
                buffer.onUnderrun(AudioJitterBuffer.RESTART_GAP_MS, FRAME_US);

        assertTrue(underrun.isRestart());
        assertEquals(0, underrun.getSilenceFrames());
        assertEquals(0, buffer.getSilencedFrames());
    }

    @Test
    public void driftGuardRespectsThresholdAndCooldown() {
        AudioJitterBuffer buffer = new AudioJitterBuffer();

        assertEquals(0, buffer.driftCorrectionFrames(20_000L, FRAME_US, 0L));
        // The audible audio moved ahead of the timeline and has to be delayed by one silence frame.
        assertEquals(1, buffer.driftCorrectionFrames(60_000L, FRAME_US, 0L));
        assertEquals(0, buffer.driftCorrectionFrames(60_000L, FRAME_US, 199L));
        // The audible audio moved behind and skips a buffered frame to catch up.
        assertEquals(-1, buffer.driftCorrectionFrames(-60_000L, FRAME_US,
                AudioJitterBuffer.DRIFT_COOLDOWN_MS));
        assertEquals(0, buffer.driftCorrectionFrames(AudioJitterBuffer.UNSET, FRAME_US, 10_000L));
    }

    @Test
    public void resetClearsTimingDecisions() {
        AudioJitterBuffer buffer = new AudioJitterBuffer();
        buffer.plan(0L, FRAME_US, AudioJitterBuffer.TARGET_DEPTH_MS * 1_000L, 0L);
        buffer.plan(FRAME_US, FRAME_US, AudioJitterBuffer.TARGET_DEPTH_MS * 1_000L, 0L);
        assertTrue(buffer.isStarted());

        buffer.reset();

        assertFalse(buffer.isStarted());
        assertEquals(0, buffer.plan(0L, FRAME_US, 0L, 0L).getSilenceFrames());
        assertFalse(buffer.plan(0L, FRAME_US, 0L, 0L).isStartPlayback());
    }

    @Test
    public void frameDurationFallsBackToTheAacDefault() {
        AudioJitterBuffer buffer = new AudioJitterBuffer();
        buffer.plan(0L, 0L, 2 * FRAME_US, 0L);

        AudioJitterBuffer.Plan plan = buffer.plan(2 * 21_333L, 0L, 2 * FRAME_US, 0L);

        assertEquals(1, plan.getSilenceFrames());
    }
}
