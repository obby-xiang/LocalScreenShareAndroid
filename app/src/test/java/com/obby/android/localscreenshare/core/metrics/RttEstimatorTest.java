package com.obby.android.localscreenshare.core.metrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.obby.android.localscreenshare.core.support.MutableNanoClock;

import org.junit.Test;

/**
 * Unit tests for {@link RttEstimator}.
 *
 * <p>The clock is injected, so every expected round trip is exact and the timeout sweep is
 * asserted without sleeping.</p>
 */
public class RttEstimatorTest {

    @Test
    public void echo_producesOneSampleAndAnEwma() {
        MutableNanoClock clock = new MutableNanoClock();
        PipelineMetrics metrics = PipelineMetrics.create(clock, 8);
        RttEstimator estimator = new RttEstimator(metrics, clock);

        int seq = estimator.beginProbe();
        clock.advance(4_000_000L);
        assertTrue(estimator.onPong(seq));

        assertEquals(4_000_000L, metrics.stats(PipelineMetrics.Window.RTT).getP50());
        assertEquals(1L, metrics.count(PipelineMetrics.Counter.RTT_SAMPLES));
        assertEquals(4_000_000L, estimator.getEwmaNs());
        assertEquals(PipelineMetrics.RTT_KIND_PING_PONG,
                metrics.getGauge(PipelineMetrics.Gauge.RTT_KIND));
        assertEquals(4_000_000L, metrics.getGauge(PipelineMetrics.Gauge.RTT_EWMA));
    }

    @Test
    public void duplicateEcho_isIgnored() {
        MutableNanoClock clock = new MutableNanoClock();
        PipelineMetrics metrics = PipelineMetrics.create(clock, 8);
        RttEstimator estimator = new RttEstimator(metrics, clock);

        int seq = estimator.beginProbe();
        clock.advance(3_000_000L);
        assertTrue(estimator.onPong(seq));
        assertFalse(estimator.onPong(seq));

        assertEquals(1L, metrics.count(PipelineMetrics.Counter.RTT_SAMPLES));
    }

    @Test
    public void unknownEcho_isIgnored() {
        MutableNanoClock clock = new MutableNanoClock();
        PipelineMetrics metrics = PipelineMetrics.create(clock, 8);
        RttEstimator estimator = new RttEstimator(metrics, clock);

        assertFalse(estimator.onPong(42));
        assertEquals(0L, metrics.count(PipelineMetrics.Counter.RTT_SAMPLES));
    }

    @Test
    public void unansweredProbe_countsAsLostAfterTheTimeout() {
        MutableNanoClock clock = new MutableNanoClock();
        PipelineMetrics metrics = PipelineMetrics.create(clock, 8);
        RttEstimator estimator = new RttEstimator(metrics, clock);

        estimator.beginProbe();
        clock.advance(RttEstimator.TIMEOUT_NS - 1L);
        assertEquals(0, estimator.checkTimeouts());
        assertEquals(0L, metrics.count(PipelineMetrics.Counter.RTT_LOST));

        clock.advance(2L);
        assertEquals(1, estimator.checkTimeouts());
        assertEquals(1L, metrics.count(PipelineMetrics.Counter.RTT_LOST));
        assertEquals("a lost probe is counted once", 0, estimator.checkTimeouts());
    }

    @Test
    public void ewma_movesTowardsTheNewestSample() {
        MutableNanoClock clock = new MutableNanoClock();
        PipelineMetrics metrics = PipelineMetrics.create(clock, 8);
        RttEstimator estimator = new RttEstimator(metrics, clock);

        int first = estimator.beginProbe();
        clock.advance(10_000_000L);
        estimator.onPong(first);
        int second = estimator.beginProbe();
        clock.advance(18_000_000L);
        estimator.onPong(second);

        // alpha = 1/8: 10ms + (18ms - 10ms) / 8 = 11ms.
        assertEquals(11_000_000L, estimator.getEwmaNs());
        assertEquals(2L, metrics.count(PipelineMetrics.Counter.RTT_SAMPLES));
    }

    @Test
    public void disabledScope_producesNoSamples() {
        MutableNanoClock clock = new MutableNanoClock();
        RttEstimator estimator = new RttEstimator(PipelineMetrics.NONE, clock);

        int seq = estimator.beginProbe();
        assertFalse(estimator.onPong(seq));
        clock.advance(RttEstimator.TIMEOUT_NS * 2L);
        assertEquals(0, estimator.checkTimeouts());
        assertEquals(0L, estimator.getEwmaNs());
    }

    @Test
    public void sequenceWrapsWithoutLosingTheMatch() {
        MutableNanoClock clock = new MutableNanoClock();
        PipelineMetrics metrics = PipelineMetrics.create(clock, 8);
        RttEstimator estimator = new RttEstimator(metrics, clock);

        for (int i = 0; i < RttEstimator.CAPACITY * 3; i++) {
            int seq = estimator.beginProbe();
            clock.advance(1_000_000L);
            assertTrue("probe " + i + " must match", estimator.onPong(seq));
        }

        assertEquals(RttEstimator.CAPACITY * 3L,
                metrics.count(PipelineMetrics.Counter.RTT_SAMPLES));
    }
}
