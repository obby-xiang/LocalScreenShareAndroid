package com.obby.android.localscreenshare.core.client;

import com.obby.android.localscreenshare.core.client.CatchUpPolicy.Decision;
import com.obby.android.localscreenshare.core.client.CatchUpPolicy.Reason;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CatchUpPolicyTest {

    private static final long STEP_NS = 100_000_000L;

    private static final long CLEAR_WAIT_NS = 20_000_000L;

    private static final int CLEAR_DEPTH = 4;

    private static final long BACKLOG_WAIT_NS = CatchUpPolicy.BACKLOG_WAIT_NANOS;

    /**
     * Feeds backlog samples in fixed steps and returns the first decision that is not NONE.
     */
    private static Decision feedBacklog(CatchUpPolicy policy, long fromNs, long toNs, int depth,
                                        long waitNs) {
        for (long nowNs = fromNs; nowNs <= toNs; nowNs += STEP_NS) {
            Decision decision = policy.onSample(nowNs, depth, waitNs);
            if (decision != Decision.NONE) {
                return decision;
            }
        }
        return Decision.NONE;
    }

    /**
     * Feeds clear samples in fixed steps.
     */
    private static void feedClear(CatchUpPolicy policy, long fromNs, long toNs) {
        for (long nowNs = fromNs; nowNs <= toNs; nowNs += STEP_NS) {
            assertEquals(Decision.NONE, policy.onSample(nowNs, CLEAR_DEPTH, CLEAR_WAIT_NS));
        }
    }

    /**
     * Starts one episode by feeding a backlog from zero and returns the moment it triggered.
     */
    private static long triggerFirstEpisode(CatchUpPolicy policy, int depth, long waitNs) {
        long triggerAtNs = CatchUpPolicy.SUSTAIN_NANOS;
        assertEquals(Decision.START_CATCH_UP,
                feedBacklog(policy, 0L, triggerAtNs, depth, waitNs));
        return triggerAtNs;
    }

    @Test
    public void clearQueue_neverTriggers() {
        CatchUpPolicy policy = new CatchUpPolicy();

        feedClear(policy, 0L, 60_000_000_000L);

        assertFalse(policy.isCatchingUp());
        assertEquals(Reason.NONE, policy.getLastReason());
        assertEquals(0, policy.getConsecutiveCatchUps());
    }

    @Test
    public void transientBacklog_restartsTheSustainTimer() {
        CatchUpPolicy policy = new CatchUpPolicy();

        assertEquals(Decision.NONE,
                feedBacklog(policy, 0L, CatchUpPolicy.SUSTAIN_NANOS - STEP_NS, CLEAR_DEPTH,
                        BACKLOG_WAIT_NS));
        // One clear sample in between: the burst before it never counted as sustained.
        assertEquals(Decision.NONE,
                policy.onSample(CatchUpPolicy.SUSTAIN_NANOS, CLEAR_DEPTH, CLEAR_WAIT_NS));
        long secondBurstStartNs = CatchUpPolicy.SUSTAIN_NANOS + STEP_NS;
        assertEquals(Decision.NONE,
                feedBacklog(policy, secondBurstStartNs,
                        secondBurstStartNs + CatchUpPolicy.SUSTAIN_NANOS - STEP_NS, CLEAR_DEPTH,
                        BACKLOG_WAIT_NS));

        assertFalse(policy.isCatchingUp());
        assertEquals(0, policy.getConsecutiveCatchUps());
    }

    @Test
    public void sustainedWait_triggersWithTheWaitReason() {
        CatchUpPolicy policy = new CatchUpPolicy();

        long triggerAtNs = triggerFirstEpisode(policy, CLEAR_DEPTH, BACKLOG_WAIT_NS);

        assertEquals(CatchUpPolicy.SUSTAIN_NANOS, triggerAtNs);
        assertTrue(policy.isCatchingUp());
        assertEquals(Reason.QUEUE_WAIT, policy.getLastReason());
        assertEquals(1, policy.getConsecutiveCatchUps());
    }

    @Test
    public void deepQueue_triggersWithTheDepthReason() {
        CatchUpPolicy policy = new CatchUpPolicy();

        triggerFirstEpisode(policy, CatchUpPolicy.BACKLOG_QUEUE_DEPTH, CLEAR_WAIT_NS);

        assertEquals(Reason.QUEUE_DEPTH, policy.getLastReason());
    }

    @Test
    public void queueWait_takesPrecedenceOverDepth() {
        CatchUpPolicy policy = new CatchUpPolicy();

        triggerFirstEpisode(policy, CatchUpPolicy.BACKLOG_QUEUE_DEPTH, BACKLOG_WAIT_NS);

        assertEquals(Reason.QUEUE_WAIT, policy.getLastReason());
    }

    @Test
    public void samplesWhileCatchingUp_areIgnored() {
        CatchUpPolicy policy = new CatchUpPolicy();
        long triggerAtNs = triggerFirstEpisode(policy, CatchUpPolicy.BACKLOG_QUEUE_DEPTH,
                CLEAR_WAIT_NS);

        // A backlogged queue is not a new signal while the episode waits for its join point.
        assertEquals(Decision.NONE,
                feedBacklog(policy, triggerAtNs + STEP_NS,
                        triggerAtNs + CatchUpPolicy.CATCH_UP_TIMEOUT_NANOS - 2L * STEP_NS,
                        CatchUpPolicy.BACKLOG_QUEUE_DEPTH, CLEAR_WAIT_NS));

        assertTrue(policy.isCatchingUp());
        assertEquals(1, policy.getConsecutiveCatchUps());
    }

    @Test
    public void backlogRightAfterAJoinPoint_waitsForTheCooldown() {
        CatchUpPolicy policy = new CatchUpPolicy();
        long triggerAtNs = triggerFirstEpisode(policy, CatchUpPolicy.BACKLOG_QUEUE_DEPTH,
                CLEAR_WAIT_NS);
        policy.onJoinPoint(triggerAtNs + STEP_NS);

        long cooldownEndNs = triggerAtNs + CatchUpPolicy.COOLDOWN_NANOS;
        assertEquals(Decision.NONE,
                feedBacklog(policy, triggerAtNs + 2L * STEP_NS, cooldownEndNs - STEP_NS,
                        CatchUpPolicy.BACKLOG_QUEUE_DEPTH, CLEAR_WAIT_NS));
        assertFalse(policy.isCatchingUp());

        // The backlog is still there, so the first sample after the cooldown starts the episode.
        assertEquals(Decision.START_CATCH_UP,
                policy.onSample(cooldownEndNs, CatchUpPolicy.BACKLOG_QUEUE_DEPTH, CLEAR_WAIT_NS));
        assertEquals(2, policy.getConsecutiveCatchUps());
    }

    @Test
    public void repeatedFailures_reportOnceThenHoldOff() {
        CatchUpPolicy policy = new CatchUpPolicy();
        long episodeCount = 0L;
        long failureAtNs = -1L;
        for (long nowNs = 0L; nowNs <= 60_000_000_000L; nowNs += STEP_NS) {
            Decision decision = policy.onSample(nowNs, CLEAR_DEPTH, BACKLOG_WAIT_NS);
            if (decision == Decision.START_CATCH_UP) {
                episodeCount++;
                policy.onJoinPoint(nowNs);
            } else if (decision == Decision.CONVERGENCE_FAILED) {
                failureAtNs = nowNs;
                break;
            }
        }

        assertEquals(CatchUpPolicy.MAX_CONSECUTIVE_CATCH_UPS, episodeCount);
        assertTrue(failureAtNs > 0L);
        assertFalse(policy.isCatchingUp());

        // Held off: the same backlog starts nothing at all.
        long holdEndNs = failureAtNs + CatchUpPolicy.HOLD_NANOS;
        assertEquals(Decision.NONE,
                feedBacklog(policy, failureAtNs + STEP_NS, holdEndNs - STEP_NS, CLEAR_DEPTH,
                        BACKLOG_WAIT_NS));
        assertEquals(CatchUpPolicy.MAX_CONSECUTIVE_CATCH_UPS, policy.getConsecutiveCatchUps());

        // After the hold the mechanism is available again with a fresh consecutive count.
        assertEquals(Decision.START_CATCH_UP,
                feedBacklog(policy, holdEndNs, holdEndNs + CatchUpPolicy.SUSTAIN_NANOS + STEP_NS,
                        CLEAR_DEPTH, BACKLOG_WAIT_NS));
        assertEquals(1, policy.getConsecutiveCatchUps());
    }

    @Test
    public void sustainedClearAfterCatchUp_countsAsConverged() {
        CatchUpPolicy policy = new CatchUpPolicy();
        long triggerAtNs = triggerFirstEpisode(policy, CatchUpPolicy.BACKLOG_QUEUE_DEPTH,
                CLEAR_WAIT_NS);
        policy.onJoinPoint(triggerAtNs + STEP_NS);

        feedClear(policy, triggerAtNs + 2L * STEP_NS,
                triggerAtNs + CatchUpPolicy.CONVERGED_CLEAR_NANOS + 2L * STEP_NS);

        assertEquals(0, policy.getConsecutiveCatchUps());
    }

    @Test
    public void episodeWithoutJoinPoint_timesOutAndMayRunAgain() {
        CatchUpPolicy policy = new CatchUpPolicy();
        long triggerAtNs = triggerFirstEpisode(policy, CLEAR_DEPTH, BACKLOG_WAIT_NS);

        // No join point ever arrives: the episode expires and a fresh sustained backlog is allowed
        // once the cooldown has elapsed as well.
        assertEquals(Decision.START_CATCH_UP,
                feedBacklog(policy, triggerAtNs + STEP_NS,
                        triggerAtNs + CatchUpPolicy.COOLDOWN_NANOS + CatchUpPolicy.SUSTAIN_NANOS,
                        CLEAR_DEPTH, BACKLOG_WAIT_NS));
        assertEquals(2, policy.getConsecutiveCatchUps());
    }

    @Test
    public void joinPointOutsideAnEpisode_isIgnored() {
        CatchUpPolicy policy = new CatchUpPolicy();

        policy.onJoinPoint(1_000_000_000L);

        assertFalse(policy.isCatchingUp());
        assertEquals(0, policy.getConsecutiveCatchUps());
        assertEquals(Reason.NONE, policy.getLastReason());
    }

    @Test
    public void onExternalCatchUp_startsEpisode() {
        CatchUpPolicy policy = new CatchUpPolicy();

        policy.onExternalCatchUp(5_000_000_000L);

        assertTrue(policy.isCatchingUp());
        assertEquals(Reason.EXTERNAL, policy.getLastReason());
        assertEquals(0, policy.getConsecutiveCatchUps());

        policy.onJoinPoint(5_100_000_000L);

        assertFalse(policy.isCatchingUp());
    }

    @Test
    public void onExternalCatchUp_mergesWithoutDoubleCount() {
        CatchUpPolicy policy = new CatchUpPolicy();
        policy.onExternalCatchUp(1_000_000_000L);

        policy.onExternalCatchUp(2_000_000_000L);

        // The merge does not refresh the start: the episode still expires one timeout after 1 s.
        assertEquals(Decision.NONE,
                policy.onSample(3_900_000_000L, CLEAR_DEPTH, CLEAR_WAIT_NS));
        assertTrue(policy.isCatchingUp());
        assertEquals(Decision.NONE,
                policy.onSample(4_000_000_000L, CLEAR_DEPTH, CLEAR_WAIT_NS));
        assertFalse(policy.isCatchingUp());
        assertEquals(0, policy.getConsecutiveCatchUps());
    }

    @Test
    public void onExternalCatchUp_doesNotIncrementConsecutive() {
        CatchUpPolicy policy = new CatchUpPolicy();
        long triggerAtNs = triggerFirstEpisode(policy, CatchUpPolicy.BACKLOG_QUEUE_DEPTH,
                CLEAR_WAIT_NS);
        policy.onJoinPoint(triggerAtNs + STEP_NS);
        assertEquals(1, policy.getConsecutiveCatchUps());

        policy.onExternalCatchUp(triggerAtNs + 2L * STEP_NS);

        assertEquals(1, policy.getConsecutiveCatchUps());
        assertTrue(policy.isCatchingUp());
        assertEquals(Reason.EXTERNAL, policy.getLastReason());
    }

    @Test
    public void onExternalCatchUp_doesNotTriggerConvergenceHold() {
        CatchUpPolicy policy = new CatchUpPolicy();
        for (int i = 0; i < 20; i++) {
            policy.onExternalCatchUp(i * STEP_NS);
            policy.onJoinPoint(i * STEP_NS + 1L);
        }

        // External episodes neither consume the budget nor set the cooldown: a sustained backlog
        // still starts a load episode normally.
        assertEquals(Decision.START_CATCH_UP,
                feedBacklog(policy, 3_000_000_000L, 5_000_000_000L,
                        CatchUpPolicy.BACKLOG_QUEUE_DEPTH, CLEAR_WAIT_NS));
        assertEquals(1, policy.getConsecutiveCatchUps());
        assertEquals(Reason.QUEUE_DEPTH, policy.getLastReason());
    }

    @Test
    public void isSuperseded_dropsMediaButKeepsDescriptors() {
        assertFalse(CatchUpPolicy.isSuperseded(7, 7, false));
        assertTrue(CatchUpPolicy.isSuperseded(6, 7, false));
        assertFalse(CatchUpPolicy.isSuperseded(6, 7, true));
    }
}
