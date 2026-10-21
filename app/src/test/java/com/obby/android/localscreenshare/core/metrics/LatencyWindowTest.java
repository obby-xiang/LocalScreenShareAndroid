package com.obby.android.localscreenshare.core.metrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link LatencyWindow}.
 *
 * <p>Percentile expectations use the nearest-rank definition documented on the class: the
 * {@code p} percentile is the sample at sorted index {@code ceil(p / 100 * n) - 1}.</p>
 */
public class LatencyWindowTest {

    @Test
    public void emptyWindow_reportsNoSamples() {
        LatencyWindow window = new LatencyWindow(8);

        LatencyWindow.Stats stats = window.snapshot();

        assertEquals(0, stats.getCount());
        assertEquals(0L, stats.getMax());
        assertFalse(stats.hasSamples());
        assertEquals(LatencyWindow.Stats.EMPTY, stats);
    }

    @Test
    public void singleSample_isEveryPercentile() {
        LatencyWindow window = new LatencyWindow(8);

        window.record(42L);

        LatencyWindow.Stats stats = window.snapshot();
        assertEquals(1, stats.getCount());
        assertEquals(42L, stats.getP50());
        assertEquals(42L, stats.getP95());
        assertEquals(42L, stats.getP99());
        assertEquals(42L, stats.getMax());
    }

    @Test
    public void hundredSamples_useNearestRank() {
        LatencyWindow window = new LatencyWindow(128);
        for (int i = 1; i <= 100; i++) {
            window.record(i);
        }

        LatencyWindow.Stats stats = window.snapshot();
        assertEquals(100, stats.getCount());
        assertEquals(50L, stats.getP50());
        assertEquals(95L, stats.getP95());
        assertEquals(99L, stats.getP99());
        assertEquals(100L, stats.getMax());
    }

    @Test
    public void twentySamples_roundPercentilesUpToTheNextRank() {
        LatencyWindow window = new LatencyWindow(32);
        for (int i = 1; i <= 20; i++) {
            window.record(i);
        }

        LatencyWindow.Stats stats = window.snapshot();
        assertEquals(10L, stats.getP50());
        assertEquals(19L, stats.getP95());
        assertEquals(20L, stats.getMax());
    }

    @Test
    public void identicalSamples_collapseToThatValue() {
        LatencyWindow window = new LatencyWindow(16);
        for (int i = 0; i < 10; i++) {
            window.record(7L);
        }

        LatencyWindow.Stats stats = window.snapshot();
        assertEquals(7L, stats.getP50());
        assertEquals(7L, stats.getP95());
        assertEquals(7L, stats.getP99());
        assertEquals(7L, stats.getMax());
    }

    @Test
    public void zeroSamples_areSamples() {
        LatencyWindow window = new LatencyWindow(4);
        window.record(0L);
        window.record(0L);
        window.record(5L);

        LatencyWindow.Stats stats = window.snapshot();
        assertEquals(3, stats.getCount());
        assertEquals(0L, stats.getP50());
        assertEquals(5L, stats.getMax());
    }

    @Test
    public void fullWindow_keepsTheNewestSamplesAndDropsTheOldest() {
        LatencyWindow window = new LatencyWindow(4);
        for (int i = 1; i <= 6; i++) {
            window.record(i);
        }

        LatencyWindow.Stats stats = window.snapshot();
        assertEquals(4, stats.getCount());
        assertEquals(4L, stats.getP50());
        assertEquals(6L, stats.getMax());
        assertEquals(6L, window.getRecordedCount());
    }

    @Test
    public void nonPowerOfTwoCapacity_isRoundedUpWithoutChangingTheReportedCount() {
        LatencyWindow window = new LatencyWindow(3);
        for (int i = 1; i <= 5; i++) {
            window.record(i);
        }

        assertEquals(4, window.snapshot().getCount());
        assertEquals(5L, window.snapshot().getMax());
    }

    @Test
    public void reset_dropsEverySample() {
        LatencyWindow window = new LatencyWindow(8);
        window.record(10L);
        window.record(20L);

        window.reset();

        assertEquals(0L, window.getRecordedCount());
        assertFalse(window.snapshot().hasSamples());
        window.record(3L);
        assertEquals(3L, window.snapshot().getMax());
    }

    @Test
    public void nonPositiveCapacity_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LatencyWindow(0));
        assertThrows(IllegalArgumentException.class, () -> new LatencyWindow(-1));
    }

    @Test
    public void stats_valueSemantics_differForDifferentSummaries() {
        LatencyWindow first = new LatencyWindow(8);
        LatencyWindow second = new LatencyWindow(8);
        first.record(1L);
        second.record(2L);

        assertNotEquals(first.snapshot(), second.snapshot());
        assertEquals(first.snapshot(), first.snapshot());
        assertTrue(first.snapshot().hasSamples());
    }
}
