package com.obby.android.localscreenshare.core.metrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.obby.android.localscreenshare.core.support.MutableNanoClock;

import org.junit.Test;

/**
 * Unit tests for {@link RateSampler} and {@link MetricsRate}.
 *
 * <p>The regression this class guards is the reported one: a rate derived from a cumulative counter
 * divided by the snapshot period grows without bound. Every test therefore drives the clock and the
 * counters together and asserts the rate of the interval, not the average since the scope began.</p>
 */
public class RateSamplerTest {

    private static final long ONE_SECOND_NS = 1_000_000_000L;

    @Test
    public void sample_reportsTheDeltaOfTheInterval() {
        MutableNanoClock clock = new MutableNanoClock();
        PipelineMetrics metrics = PipelineMetrics.create(clock, 8);
        RateSampler sampler = new RateSampler(metrics);

        // First interval: 30 units and 1 Mbps over one second.
        metrics.add(PipelineMetrics.Counter.ACCESS_UNITS, 30L);
        metrics.add(PipelineMetrics.Counter.ACCESS_UNIT_BYTES, 125_000L);
        clock.advance(ONE_SECOND_NS);
        MetricsRate first = sampler.sample();

        assertEquals(1_000L, first.getPeriodMs());
        assertEquals(30L, first.getUnits());
        assertEquals(125_000L, first.getBytes());
        assertEquals(30d, first.getFramesPerSecond(), 1e-9d);
        assertEquals(1_000_000d, first.getBitsPerSecond(), 1e-9d);

        // Second interval: the same traffic. A cumulative reading would now report 60 fps.
        metrics.add(PipelineMetrics.Counter.ACCESS_UNITS, 30L);
        metrics.add(PipelineMetrics.Counter.ACCESS_UNIT_BYTES, 125_000L);
        clock.advance(ONE_SECOND_NS);
        MetricsRate second = sampler.sample();

        assertEquals(30L, second.getUnits());
        assertEquals(30d, second.getFramesPerSecond(), 1e-9d);
        assertEquals(1_000_000d, second.getBitsPerSecond(), 1e-9d);
    }

    @Test
    public void sample_usesTheMeasuredPeriodNotTheNominalOne() {
        MutableNanoClock clock = new MutableNanoClock();
        PipelineMetrics metrics = PipelineMetrics.create(clock, 8);
        RateSampler sampler = new RateSampler(metrics);

        metrics.add(PipelineMetrics.Counter.ACCESS_UNITS, 60L);
        // A delayed handler: two seconds elapsed instead of the five-second cadence.
        clock.advance(2 * ONE_SECOND_NS);

        MetricsRate rate = sampler.sample();

        assertEquals(2_000L, rate.getPeriodMs());
        assertEquals(30d, rate.getFramesPerSecond(), 1e-9d);
    }

    @Test
    public void sample_reportsZeroFramesWhenNothingArrived() {
        MutableNanoClock clock = new MutableNanoClock();
        PipelineMetrics metrics = PipelineMetrics.create(clock, 8);
        RateSampler sampler = new RateSampler(metrics);

        clock.advance(500_000_000L);
        MetricsRate rate = sampler.sample();

        assertTrue(rate.hasInterval());
        assertEquals(0, rate.getUnits());
        assertEquals(0d, rate.getFramesPerSecond(), 1e-9d);
        assertEquals(0d, rate.getBitsPerSecond(), 1e-9d);
    }

    @Test
    public void sample_isEmptyWithoutAnElapsedInterval() {
        MutableNanoClock clock = new MutableNanoClock();
        PipelineMetrics metrics = PipelineMetrics.create(clock, 8);
        RateSampler sampler = new RateSampler(metrics);

        metrics.add(PipelineMetrics.Counter.ACCESS_UNITS, 3L);

        MetricsRate rate = sampler.sample();

        assertFalse(rate.hasInterval());
        assertEquals(MetricsRate.EMPTY, rate);
        assertEquals(0d, rate.getFramesPerSecond(), 1e-9d);
    }

    @Test
    public void sample_isEmptyForADisabledScope() {
        RateSampler sampler = new RateSampler(PipelineMetrics.NONE);

        assertEquals(MetricsRate.EMPTY, sampler.sample());
    }

    @Test
    public void sample_keepsItsBaselineAcrossReadings() {
        MutableNanoClock clock = new MutableNanoClock();
        PipelineMetrics metrics = PipelineMetrics.create(clock, 8);
        RateSampler sampler = new RateSampler(metrics);

        metrics.add(PipelineMetrics.Counter.ACCESS_UNITS, 10L);
        clock.advance(ONE_SECOND_NS);
        assertEquals(10L, sampler.sample().getUnits());

        // Two samplers on one scope keep independent baselines, which is what lets the one-second
        // overlay and the five-second log line read the same counters.
        RateSampler second = new RateSampler(metrics);
        metrics.add(PipelineMetrics.Counter.ACCESS_UNITS, 5L);
        clock.advance(ONE_SECOND_NS);

        assertEquals(5L, sampler.sample().getUnits());
        assertEquals(5L, second.sample().getUnits());
    }

    @Test
    public void rate_rejectsNegativeValues() {
        assertThrows(IllegalArgumentException.class, () -> new MetricsRate(-1L, 0L, 0L));
        assertThrows(IllegalArgumentException.class, () -> new MetricsRate(0L, -1L, 0L));
        assertThrows(IllegalArgumentException.class, () -> new MetricsRate(0L, 0L, -1L));
        assertThrows(IllegalArgumentException.class, () -> new RateSampler(null));
    }

    @Test
    public void rate_isAValue() {
        MetricsRate rate = new MetricsRate(2_000L, 12L, 250_000L);

        assertEquals(rate, new MetricsRate(2_000L, 12L, 250_000L));
        assertEquals(rate.hashCode(), new MetricsRate(2_000L, 12L, 250_000L).hashCode());
        assertEquals(6d, rate.getFramesPerSecond(), 1e-9d);
        assertEquals(1_000_000d, rate.getBitsPerSecond(), 1e-9d);
        assertTrue(MetricsRate.EMPTY.toString().contains("periodMs=0"));
    }
}
