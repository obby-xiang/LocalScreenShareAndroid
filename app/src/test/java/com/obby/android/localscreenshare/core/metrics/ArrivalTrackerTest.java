package com.obby.android.localscreenshare.core.metrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.obby.android.localscreenshare.core.support.MutableNanoClock;

import org.junit.Test;

/**
 * Unit tests for {@link ArrivalTracker}.
 *
 * <p>Timestamps are chosen so the expected values are exact: a producer that advances by exactly
 * one frame period and a local clock that advances by the same amount produce a zero relative
 * delay, while a late frame produces exactly the delay it accumulated.</p>
 */
public class ArrivalTrackerTest {

    private static final int EPOCH = 1;

    private static final long FRAME_NS = 33_000_000L;

    private static final long FRAME_US = 33_000L;

    @Test
    public void firstUnit_recordsNoIntervalAndNoDelay() {
        PipelineMetrics metrics = newMetrics();
        ArrivalTracker tracker = new ArrivalTracker(metrics);

        tracker.feed(EPOCH, 0L, 1_000_000L, 5_000_000_000L);

        assertEquals(0L, metrics.getRecordedCount(PipelineMetrics.Window.ARRIVAL));
        assertEquals(0L, metrics.getRecordedCount(PipelineMetrics.Window.ROWD));
        assertEquals(0L, metrics.count(PipelineMetrics.Counter.STALLS));
    }

    @Test
    public void steadyCadence_recordsIntervalsAndZeroRelativeDelay() {
        PipelineMetrics metrics = newMetrics();
        ArrivalTracker tracker = new ArrivalTracker(metrics);
        long ptsUs = 1_000_000L;
        long arrivalNs = 5_000_000_000L;

        tracker.feed(EPOCH, 0L, ptsUs, arrivalNs);
        for (int i = 1; i < 5; i++) {
            ptsUs += FRAME_US;
            arrivalNs += FRAME_NS;
            tracker.feed(EPOCH, i, ptsUs, arrivalNs);
        }

        assertEquals(4L, metrics.getRecordedCount(PipelineMetrics.Window.ARRIVAL));
        assertEquals(FRAME_NS, metrics.stats(PipelineMetrics.Window.ARRIVAL).getP50());
        assertEquals(0L, metrics.stats(PipelineMetrics.Window.ROWD).getMax());
        assertEquals(0L, metrics.count(PipelineMetrics.Counter.STALLS));
        assertEquals(0L, metrics.count(PipelineMetrics.Counter.SEQUENCE_GAPS));
    }

    @Test
    public void lateFrame_recordsItsExtraDelayAsRowd() {
        PipelineMetrics metrics = newMetrics();
        ArrivalTracker tracker = new ArrivalTracker(metrics);
        long ptsUs = 1_000_000L;
        long arrivalNs = 5_000_000_000L;

        tracker.feed(EPOCH, 0L, ptsUs, arrivalNs);
        ptsUs += FRAME_US;
        arrivalNs += FRAME_NS + 40_000_000L;
        tracker.feed(EPOCH, 1L, ptsUs, arrivalNs);

        assertEquals(40_000_000L, metrics.stats(PipelineMetrics.Window.ROWD).getMax());
    }

    @Test
    public void fasterFrame_reanchorsTheMinimumSoRowdStaysNonNegative() {
        PipelineMetrics metrics = newMetrics();
        ArrivalTracker tracker = new ArrivalTracker(metrics);

        // The first frame is slow, the second faster: the baseline moves to the faster frame and
        // the drift is expressed relative to it, never as a negative delay.
        tracker.feed(EPOCH, 0L, 1_000_000L, 5_000_000_000L);
        tracker.feed(EPOCH, 1L, 1_033_000L, 5_010_000_000L);

        assertEquals(0L, metrics.stats(PipelineMetrics.Window.ROWD).getMax());
    }

    @Test
    public void gapAboveThreshold_countsOneStallAndItsDuration() {
        PipelineMetrics metrics = newMetrics();
        ArrivalTracker tracker = new ArrivalTracker(metrics);
        long ptsUs = 1_000_000L;
        long arrivalNs = 5_000_000_000L;

        tracker.feed(EPOCH, 0L, ptsUs, arrivalNs);
        // One second without a frame: far above max(3 x 33ms, 150ms).
        arrivalNs += 1_000_000_000L;
        ptsUs += 1_000_000L;
        tracker.feed(EPOCH, 1L, ptsUs, arrivalNs);

        assertEquals(1L, metrics.count(PipelineMetrics.Counter.STALLS));
        assertEquals(1_000_000_000L,
                metrics.stats(PipelineMetrics.Window.STALL_DURATION).getMax());
    }

    @Test
    public void missingSequence_countsTheGap() {
        PipelineMetrics metrics = newMetrics();
        ArrivalTracker tracker = new ArrivalTracker(metrics);
        long ptsUs = 1_000_000L;
        long arrivalNs = 5_000_000_000L;

        tracker.feed(EPOCH, 0L, ptsUs, arrivalNs);
        tracker.feed(EPOCH, 1L, ptsUs + FRAME_US, arrivalNs + FRAME_NS);
        tracker.feed(EPOCH, 4L, ptsUs + 4 * FRAME_US, arrivalNs + 4 * FRAME_NS);

        assertEquals(2L, metrics.count(PipelineMetrics.Counter.SEQUENCE_GAPS));
    }

    @Test
    public void newEpoch_doesNotCountASequenceGap() {
        PipelineMetrics metrics = newMetrics();
        ArrivalTracker tracker = new ArrivalTracker(metrics);

        tracker.feed(EPOCH, 10L, 1_000_000L, 5_000_000_000L);
        tracker.feed(EPOCH + 1, 0L, 1_033_000L, 5_033_000_000L);

        assertEquals(0L, metrics.count(PipelineMetrics.Counter.SEQUENCE_GAPS));
    }

    @Test
    public void disabledScope_recordsNothing() {
        ArrivalTracker tracker = new ArrivalTracker(PipelineMetrics.NONE);

        tracker.feed(EPOCH, 0L, 1_000_000L, 5_000_000_000L);
        tracker.feed(EPOCH, 5L, 1_100_000L, 6_000_000_000L);

        assertTrue(PipelineMetrics.NONE.snapshot(1000L).isEmpty());
    }

    @Test
    public void stallThreshold_followsTheObservedCadence() {
        PipelineMetrics metrics = newMetrics();
        ArrivalTracker tracker = new ArrivalTracker(metrics);

        // Without any interval the floor applies.
        assertEquals(150_000_000L, tracker.getStallThresholdNs());

        long ptsUs = 1_000_000L;
        long arrivalNs = 5_000_000_000L;
        tracker.feed(EPOCH, 0L, ptsUs, arrivalNs);
        for (int i = 1; i <= 10; i++) {
            ptsUs += 100_000L;
            arrivalNs += 100_000_000L;
            tracker.feed(EPOCH, i, ptsUs, arrivalNs);
        }

        // A hundred-millisecond cadence raises the threshold to three intervals.
        assertEquals(300_000_000L, tracker.getStallThresholdNs());
    }

    private static PipelineMetrics newMetrics() {
        return PipelineMetrics.create(new MutableNanoClock(), 16);
    }
}
