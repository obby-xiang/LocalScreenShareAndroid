package com.obby.android.localscreenshare.core.client;

import com.obby.android.localscreenshare.core.client.CatchUpPolicy.Decision;
import com.obby.android.localscreenshare.core.client.CatchUpPolicy.Reason;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class CatchUpSamplerTest {

    private static final long STEP_NS = 100_000_000L;

    private static final long CLEAR_WAIT_NS = 20_000_000L;

    private static final int CLEAR_DEPTH = 4;

    private static final long BACKLOG_WAIT_NS = CatchUpPolicy.BACKLOG_WAIT_NANOS;

    private static final int BACKLOG_DEPTH = CatchUpPolicy.BACKLOG_QUEUE_DEPTH;

    /**
     * Feeds one backlog signal in fixed steps until a decision other than NONE appears.
     */
    private static CatchUpSampler.Result feedBacklog(CatchUpSampler sampler, long fromNs,
                                                     long toNs, int depth, long waitNs) {
        CatchUpSampler.Result result = null;
        for (long nowNs = fromNs; nowNs <= toNs; nowNs += STEP_NS) {
            result = sampler.onDecoded(nowNs, depth, waitNs, true);
            if (result.decision != Decision.NONE) {
                return result;
            }
        }
        return result;
    }

    /**
     * Starts one depth-based backlog episode and returns the sample that triggered it.
     */
    private static CatchUpSampler.Result triggerFirstEpisode(CatchUpSampler sampler) {
        CatchUpSampler.Result result = feedBacklog(sampler, 0L, CatchUpPolicy.SUSTAIN_NANOS,
                BACKLOG_DEPTH, CLEAR_WAIT_NS);
        assertEquals(Decision.START_CATCH_UP, result.decision);
        return result;
    }

    @Test
    public void onDecoded_ordinaryPathReusesTheSharedResult() {
        CatchUpSampler sampler = new CatchUpSampler();

        CatchUpSampler.Result first =
                sampler.onDecoded(0L, CLEAR_DEPTH, CLEAR_WAIT_NS, true);
        CatchUpSampler.Result second =
                sampler.onDecoded(STEP_NS, CLEAR_DEPTH, CLEAR_WAIT_NS, true);

        // Both samples are ordinary NONE samples, so the sampler allocates no result object.
        assertSame(first, second);
    }

    @Test
    public void onDecoded_singleSlowFrameDoesNotStart() {
        CatchUpSampler sampler = new CatchUpSampler();

        CatchUpSampler.Result result =
                sampler.onDecoded(0L, CLEAR_DEPTH, BACKLOG_WAIT_NS, true);

        assertFalse(result.finishedEpisode);
        assertEquals(Decision.NONE, result.decision);
        assertFalse(sampler.isCatchingUp());
    }

    @Test
    public void onDecoded_sustainedQueueWaitStarts() {
        CatchUpSampler sampler = new CatchUpSampler();

        CatchUpSampler.Result result = feedBacklog(sampler, 0L, CatchUpPolicy.SUSTAIN_NANOS,
                CLEAR_DEPTH, BACKLOG_WAIT_NS);

        assertEquals(Decision.START_CATCH_UP, result.decision);
        assertTrue(sampler.isCatchingUp());
        assertEquals(Reason.QUEUE_WAIT, sampler.getLastReason());
    }

    @Test
    public void onDecoded_sustainedQueueDepthStarts() {
        CatchUpSampler sampler = new CatchUpSampler();

        CatchUpSampler.Result result = triggerFirstEpisode(sampler);

        assertTrue(sampler.isCatchingUp());
        assertEquals(Reason.QUEUE_DEPTH, sampler.getLastReason());
    }

    @Test
    public void onDecoded_finishesThenSamplesInThatOrder() {
        CatchUpSampler sampler = new CatchUpSampler();
        for (long nowNs = 0L; nowNs < CatchUpPolicy.SUSTAIN_NANOS; nowNs += STEP_NS) {
            assertEquals(Decision.NONE,
                    sampler.onDecoded(nowNs, BACKLOG_DEPTH, CLEAR_WAIT_NS, true).decision);
        }
        // A loss episode opens while the backlog timer is already running.
        assertTrue(sampler.onExternalCatchUp(CatchUpPolicy.SUSTAIN_NANOS - STEP_NS));

        CatchUpSampler.Result result = sampler.onDecoded(CatchUpPolicy.SUSTAIN_NANOS,
                BACKLOG_DEPTH, CLEAR_WAIT_NS, true);

        // The same unit is both the join point of the old episode and the start of a new one.
        assertTrue(result.finishedEpisode);
        assertEquals(Decision.START_CATCH_UP, result.decision);
        assertTrue(sampler.isCatchingUp());
        assertEquals(CatchUpPolicy.SUSTAIN_NANOS, sampler.getEpisodeStartedNs());
    }

    @Test
    public void onDecoded_whileCatchingUpAndNotStreamingStaysOpen() {
        CatchUpSampler sampler = new CatchUpSampler();
        triggerFirstEpisode(sampler);

        for (long nowNs = CatchUpPolicy.SUSTAIN_NANOS + STEP_NS;
                nowNs < CatchUpPolicy.SUSTAIN_NANOS + CatchUpPolicy.CATCH_UP_TIMEOUT_NANOS;
                nowNs += STEP_NS) {
            CatchUpSampler.Result result =
                    sampler.onDecoded(nowNs, CLEAR_DEPTH, CLEAR_WAIT_NS, false);
            assertFalse(result.finishedEpisode);
            assertEquals(Decision.NONE, result.decision);
        }

        assertTrue(sampler.isCatchingUp());
    }

    @Test
    public void onDecoded_streamingTransitionFinishesEpisode() {
        CatchUpSampler sampler = new CatchUpSampler();
        triggerFirstEpisode(sampler);

        CatchUpSampler.Result result = sampler.onDecoded(
                CatchUpPolicy.SUSTAIN_NANOS + STEP_NS, CLEAR_DEPTH, CLEAR_WAIT_NS, true);

        assertTrue(result.finishedEpisode);
        assertEquals(Decision.NONE, result.decision);
        assertFalse(sampler.isCatchingUp());
    }

    @Test
    public void onDecoded_thirdCatchUpWithoutConvergenceFails() {
        CatchUpSampler sampler = new CatchUpSampler();
        int starts = 0;
        Decision failure = Decision.NONE;
        for (long nowNs = 0L; nowNs <= 60_000_000_000L; nowNs += STEP_NS) {
            CatchUpSampler.Result result =
                    sampler.onDecoded(nowNs, BACKLOG_DEPTH, CLEAR_WAIT_NS, true);
            if (result.decision == Decision.START_CATCH_UP) {
                starts++;
            } else if (result.decision == Decision.CONVERGENCE_FAILED) {
                failure = result.decision;
                break;
            }
        }

        assertEquals(CatchUpPolicy.MAX_CONSECUTIVE_CATCH_UPS, starts);
        assertEquals(Decision.CONVERGENCE_FAILED, failure);
        assertEquals(CatchUpPolicy.MAX_CONSECUTIVE_CATCH_UPS, sampler.getConsecutiveCatchUps());
        assertFalse(sampler.isCatchingUp());
    }

    @Test
    public void onDecoded_withinCooldownDoesNotStart() {
        CatchUpSampler sampler = new CatchUpSampler();
        triggerFirstEpisode(sampler);

        long cooldownEndNs = CatchUpPolicy.SUSTAIN_NANOS + CatchUpPolicy.COOLDOWN_NANOS;
        for (long nowNs = CatchUpPolicy.SUSTAIN_NANOS + STEP_NS;
                nowNs < cooldownEndNs; nowNs += STEP_NS) {
            CatchUpSampler.Result result =
                    sampler.onDecoded(nowNs, BACKLOG_DEPTH, CLEAR_WAIT_NS, true);
            assertEquals(Decision.NONE, result.decision);
        }

        assertEquals(1, sampler.getConsecutiveCatchUps());
        assertFalse(sampler.isCatchingUp());
    }

    @Test
    public void onExternalCatchUp_startsEpisodeAndIsVisible() {
        CatchUpSampler sampler = new CatchUpSampler();

        assertTrue(sampler.onExternalCatchUp(1_234_000_000L));

        assertTrue(sampler.isCatchingUp());
        assertEquals(1_234_000_000L, sampler.getEpisodeStartedNs());
        assertEquals(Reason.EXTERNAL, sampler.getLastReason());
        assertEquals(0, sampler.getConsecutiveCatchUps());
    }

    @Test
    public void onExternalCatchUp_mergesIntoOpenEpisode() {
        CatchUpSampler sampler = new CatchUpSampler();
        assertTrue(sampler.onExternalCatchUp(1_000_000_000L));

        assertFalse(sampler.onExternalCatchUp(2_000_000_000L));

        assertTrue(sampler.isCatchingUp());
        // The open episode keeps its original start: repeated loss cannot prolong it.
        assertEquals(1_000_000_000L, sampler.getEpisodeStartedNs());
    }

    @Test
    public void onExternalCatchUp_doesNotConsumeConsecutiveBudget() {
        CatchUpSampler sampler = new CatchUpSampler();
        for (int i = 0; i < 5; i++) {
            long lossAtNs = i * 1_000_000_000L;
            assertTrue(sampler.onExternalCatchUp(lossAtNs));
            CatchUpSampler.Result result = sampler.onDecoded(lossAtNs + STEP_NS, CLEAR_DEPTH,
                    CLEAR_WAIT_NS, true);
            assertTrue(result.finishedEpisode);
            assertEquals(Decision.NONE, result.decision);
        }
        assertEquals(0, sampler.getConsecutiveCatchUps());

        CatchUpSampler.Result result = feedBacklog(sampler, 5_000_000_000L, 7_000_000_000L,
                BACKLOG_DEPTH, CLEAR_WAIT_NS);

        assertEquals(Decision.START_CATCH_UP, result.decision);
        assertEquals(1, sampler.getConsecutiveCatchUps());
    }

    @Test
    public void onExternalCatchUp_episodeEndsOnJoinPoint() {
        CatchUpSampler sampler = new CatchUpSampler();
        assertTrue(sampler.onExternalCatchUp(1_000_000_000L));

        CatchUpSampler.Result result = sampler.onDecoded(1_100_000_000L, CLEAR_DEPTH,
                CLEAR_WAIT_NS, true);

        assertTrue(result.finishedEpisode);
        assertEquals(Decision.NONE, result.decision);
        assertFalse(sampler.isCatchingUp());
        assertEquals(Long.MIN_VALUE, sampler.getEpisodeStartedNs());
    }

    @Test
    public void episodeStartedNs_isSentinelWhenIdle() {
        CatchUpSampler sampler = new CatchUpSampler();
        assertEquals(Long.MIN_VALUE, sampler.getEpisodeStartedNs());

        sampler.onExternalCatchUp(1_000_000_000L);
        assertNotEquals(Long.MIN_VALUE, sampler.getEpisodeStartedNs());

        sampler.onDecoded(1_100_000_000L, CLEAR_DEPTH, CLEAR_WAIT_NS, true);
        assertEquals(Long.MIN_VALUE, sampler.getEpisodeStartedNs());
    }

    @Test
    public void sampleCount_countsEveryOnDecoded() {
        CatchUpSampler sampler = new CatchUpSampler();
        for (int i = 0; i < 10; i++) {
            sampler.onDecoded(i * STEP_NS, CLEAR_DEPTH, CLEAR_WAIT_NS, true);
        }
        sampler.onExternalCatchUp(2_000_000_000L);

        assertEquals(10L, sampler.getSampleCount());
    }
}
