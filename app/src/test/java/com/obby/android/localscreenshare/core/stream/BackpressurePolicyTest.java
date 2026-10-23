package com.obby.android.localscreenshare.core.stream;

import static org.junit.Assert.assertEquals;

import com.obby.android.localscreenshare.core.model.StreamFeedback;

import org.junit.Test;

/**
 * Unit tests for {@link BackpressurePolicy}.
 */
public class BackpressurePolicyTest {

    private static final int CEILING_BPS = 4_000_000;

    @Test
    public void classify_depthAtThresholdIsCongested() {
        assertEquals(BackpressurePolicy.Level.CONGESTED,
                BackpressurePolicy.classify(new StreamFeedback(8, 0, 0, 0L)));
    }

    @Test
    public void classify_waitAtThresholdIsCongested() {
        assertEquals(BackpressurePolicy.Level.CONGESTED,
                BackpressurePolicy.classify(new StreamFeedback(0, 0, 400, 0L)));
    }

    @Test
    public void classify_anyCatchUpIsCongested() {
        assertEquals(BackpressurePolicy.Level.CONGESTED,
                BackpressurePolicy.classify(new StreamFeedback(0, 1, 0, 0L)));
    }

    @Test
    public void classify_clearNeedsAllThreeBelow() {
        assertEquals(BackpressurePolicy.Level.CLEAR,
                BackpressurePolicy.classify(new StreamFeedback(2, 0, 100, 0L)));

        assertEquals(BackpressurePolicy.Level.HOLD,
                BackpressurePolicy.classify(new StreamFeedback(3, 0, 100, 0L)));
        assertEquals(BackpressurePolicy.Level.HOLD,
                BackpressurePolicy.classify(new StreamFeedback(2, 0, 101, 0L)));
        assertEquals(BackpressurePolicy.Level.CONGESTED,
                BackpressurePolicy.classify(new StreamFeedback(2, 1, 100, 0L)));
    }

    @Test
    public void classify_middleBandIsHold() {
        assertEquals(BackpressurePolicy.Level.HOLD,
                BackpressurePolicy.classify(new StreamFeedback(3, 0, 0, 0L)));
        assertEquals(BackpressurePolicy.Level.HOLD,
                BackpressurePolicy.classify(new StreamFeedback(7, 0, 99, 0L)));
        assertEquals(BackpressurePolicy.Level.HOLD,
                BackpressurePolicy.classify(new StreamFeedback(0, 0, 399, 0L)));
    }

    @Test
    public void decide_classifiedThresholdsGateActions() {
        final BackpressurePolicy.Level atDepth = BackpressurePolicy.classify(
                new StreamFeedback(8, 0, 0, 0L));
        final BackpressurePolicy.Level atWait = BackpressurePolicy.classify(
                new StreamFeedback(0, 0, 400, 0L));
        final BackpressurePolicy.Level belowThresholds = BackpressurePolicy.classify(
                new StreamFeedback(3, 0, 0, 0L));

        assertEquals(BackpressurePolicy.Action.LOWER,
                BackpressurePolicy.decide(atDepth, BackpressurePolicy.Action.NONE, CEILING_BPS,
                        CEILING_BPS, 1, true, 0, 5_000L));
        assertEquals(BackpressurePolicy.Action.LOWER,
                BackpressurePolicy.decide(atWait, BackpressurePolicy.Action.NONE, CEILING_BPS,
                        CEILING_BPS, 1, true, 0, 5_000L));
        assertEquals(BackpressurePolicy.Action.NONE,
                BackpressurePolicy.decide(belowThresholds, BackpressurePolicy.Action.NONE,
                        CEILING_BPS, CEILING_BPS, 1, true, 0, 5_000L));
    }

    @Test
    public void decide_clearNeverLowers() {
        assertEquals(BackpressurePolicy.Action.NONE,
                BackpressurePolicy.decide(BackpressurePolicy.Level.CLEAR,
                        BackpressurePolicy.Action.NONE, CEILING_BPS, CEILING_BPS,
                        1, true, 5, 5_000L));
    }

    @Test
    public void decide_multipleClientsNeverAct() {
        assertEquals(BackpressurePolicy.Action.NONE,
                BackpressurePolicy.decide(BackpressurePolicy.Level.CONGESTED,
                        BackpressurePolicy.Action.NONE, CEILING_BPS, CEILING_BPS,
                        2, true, 0, 5_000L));
    }

    @Test
    public void decide_nonH264NeverActs() {
        assertEquals(BackpressurePolicy.Action.NONE,
                BackpressurePolicy.decide(BackpressurePolicy.Level.CONGESTED,
                        BackpressurePolicy.Action.NONE, CEILING_BPS, CEILING_BPS,
                        1, false, 0, 5_000L));
    }

    @Test
    public void decide_congestedLowersOneStep() {
        assertEquals(BackpressurePolicy.Action.LOWER,
                BackpressurePolicy.decide(BackpressurePolicy.Level.CONGESTED,
                        BackpressurePolicy.Action.NONE, CEILING_BPS, CEILING_BPS,
                        1, true, 0, 5_000L));
        assertEquals(3_000_000,
                BackpressurePolicy.targetBitrate(BackpressurePolicy.Action.LOWER, CEILING_BPS,
                        CEILING_BPS));
    }

    @Test
    public void decide_holdNeitherLowersNorRaises() {
        assertEquals(BackpressurePolicy.Action.NONE,
                BackpressurePolicy.decide(BackpressurePolicy.Level.HOLD,
                        BackpressurePolicy.Action.NONE, CEILING_BPS, CEILING_BPS,
                        1, true, 0, 5_000L));
        assertEquals(BackpressurePolicy.Action.NONE,
                BackpressurePolicy.decide(BackpressurePolicy.Level.HOLD,
                        BackpressurePolicy.Action.LOWER, 3_000_000, CEILING_BPS,
                        1, true, 2, 5_000L));
    }

    @Test
    public void decide_floorsAtFortyPercent() {
        final int first = BackpressurePolicy.targetBitrate(BackpressurePolicy.Action.LOWER,
                CEILING_BPS, CEILING_BPS);
        final int second = BackpressurePolicy.targetBitrate(BackpressurePolicy.Action.LOWER,
                first, CEILING_BPS);
        final int third = BackpressurePolicy.targetBitrate(BackpressurePolicy.Action.LOWER,
                second, CEILING_BPS);
        final int fourth = BackpressurePolicy.targetBitrate(BackpressurePolicy.Action.LOWER,
                third, CEILING_BPS);

        assertEquals(3_000_000, first);
        assertEquals(2_250_000, second);
        assertEquals(1_687_500, third);
        assertEquals(1_600_000, fourth);
    }

    @Test
    public void decide_respectsMinActionInterval() {
        assertEquals(BackpressurePolicy.Action.NONE,
                BackpressurePolicy.decide(BackpressurePolicy.Level.CONGESTED,
                        BackpressurePolicy.Action.NONE, CEILING_BPS, CEILING_BPS,
                        1, true, 0, 4_999L));
        assertEquals(BackpressurePolicy.Action.LOWER,
                BackpressurePolicy.decide(BackpressurePolicy.Level.CONGESTED,
                        BackpressurePolicy.Action.NONE, CEILING_BPS, CEILING_BPS,
                        1, true, 0, 5_000L));
    }

    @Test
    public void decide_raiseNeedsTwoClearReports() {
        assertEquals(BackpressurePolicy.Action.NONE,
                BackpressurePolicy.decide(BackpressurePolicy.Level.CLEAR,
                        BackpressurePolicy.Action.LOWER, 3_000_000, CEILING_BPS,
                        1, true, 1, 5_000L));
        assertEquals(BackpressurePolicy.Action.RAISE,
                BackpressurePolicy.decide(BackpressurePolicy.Level.CLEAR,
                        BackpressurePolicy.Action.LOWER, 3_000_000, CEILING_BPS,
                        1, true, 2, 5_000L));
    }

    @Test
    public void decide_raiseGoesStraightToCeiling() {
        assertEquals(CEILING_BPS,
                BackpressurePolicy.targetBitrate(BackpressurePolicy.Action.RAISE, 1_500_000,
                        CEILING_BPS));
    }

    @Test
    public void decide_restoreOnlyWhenOverridden() {
        assertEquals(BackpressurePolicy.Action.NONE,
                BackpressurePolicy.decide(BackpressurePolicy.Level.CONGESTED,
                        BackpressurePolicy.Action.NONE, CEILING_BPS, 0,
                        2, true, 0, 5_000L));
        assertEquals(BackpressurePolicy.Action.RESTORE,
                BackpressurePolicy.decide(BackpressurePolicy.Level.CONGESTED,
                        BackpressurePolicy.Action.LOWER, 2_000_000, CEILING_BPS,
                        2, true, 0, 5_000L));
    }

    @Test
    public void decide_restoreTargetsCeiling() {
        assertEquals(CEILING_BPS,
                BackpressurePolicy.targetBitrate(BackpressurePolicy.Action.RESTORE, 2_000_000,
                        CEILING_BPS));
    }
}
