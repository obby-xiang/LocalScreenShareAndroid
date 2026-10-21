package com.obby.android.localscreenshare.core.metrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.obby.android.localscreenshare.core.metrics.LatencyWindow.Stats;
import com.obby.android.localscreenshare.core.support.MutableNanoClock;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for {@link PipelineMetrics} and {@link MetricsSnapshot}.
 *
 * <p>The disabled assertions are the important ones: a scope that is switched off must stay
 * empty no matter how many probes run against it, because "the instrumentation costs nothing when
 * off" is an acceptance criterion rather than a comment.</p>
 */
public class PipelineMetricsTest {

    @Test
    public void none_recordsNothing() {
        PipelineMetrics metrics = PipelineMetrics.NONE;

        assertFalse(metrics.isEnabled());
        metrics.record(PipelineMetrics.Window.CAP_TO_OUT, 12L);
        metrics.increment(PipelineMetrics.Counter.ACCESS_UNITS);
        metrics.add(PipelineMetrics.Counter.ACCESS_UNIT_BYTES, 1024L);
        metrics.setGauge(PipelineMetrics.Gauge.RTT_EWMA, 5L);

        assertEquals(0L, metrics.getRecordedCount(PipelineMetrics.Window.CAP_TO_OUT));
        assertEquals(Stats.EMPTY, metrics.stats(PipelineMetrics.Window.CAP_TO_OUT));
        assertEquals(0L, metrics.count(PipelineMetrics.Counter.ACCESS_UNITS));
        assertEquals(0L, metrics.count(PipelineMetrics.Counter.ACCESS_UNIT_BYTES));
        assertEquals(0L, metrics.getGauge(PipelineMetrics.Gauge.RTT_EWMA));
        assertTrue(metrics.snapshot(1000L).isEmpty());
    }

    @Test
    public void disabledSnapshot_hasEveryWindowEmpty() {
        MetricsSnapshot snapshot = PipelineMetrics.NONE.snapshot(3000L);

        assertEquals(3000L, snapshot.getPeriodMs());
        for (PipelineMetrics.Window window : PipelineMetrics.Window.values()) {
            assertEquals(Stats.EMPTY, snapshot.getWindow(window));
        }
        for (PipelineMetrics.Counter counter : PipelineMetrics.Counter.values()) {
            assertEquals(0L, snapshot.getCounter(counter));
        }
    }

    @Test
    public void enabledScope_snapshotsRecordedWindowsAndCounters() {
        PipelineMetrics metrics = PipelineMetrics.create(new MutableNanoClock(), 8);
        metrics.record(PipelineMetrics.Window.SEND, 1_000_000L);
        metrics.record(PipelineMetrics.Window.SEND, 3_000_000L);
        metrics.add(PipelineMetrics.Counter.ACCESS_UNITS, 30L);
        metrics.add(PipelineMetrics.Counter.ACCESS_UNIT_BYTES, 4_000_000L);
        metrics.setGauge(PipelineMetrics.Gauge.PEER_COUNT, 2L);

        MetricsSnapshot snapshot = metrics.snapshot(1000L);

        assertTrue(metrics.isEnabled());
        assertEquals(1_000_000L, snapshot.getWindow(PipelineMetrics.Window.SEND).getP50());
        assertEquals(3_000_000L, snapshot.getWindow(PipelineMetrics.Window.SEND).getMax());
        assertEquals(30L, snapshot.getCounter(PipelineMetrics.Counter.ACCESS_UNITS));
        assertEquals(2L, snapshot.getGauge(PipelineMetrics.Gauge.PEER_COUNT));
    }

    @Test
    public void unusedWindow_reportsEmptyWithoutAllocatingSamples() {
        PipelineMetrics metrics = PipelineMetrics.create(new MutableNanoClock());

        assertEquals(0L, metrics.getRecordedCount(PipelineMetrics.Window.RTT));
        assertEquals(Stats.EMPTY, metrics.stats(PipelineMetrics.Window.RTT));
        assertTrue(metrics.snapshot(1000L).getWindow(PipelineMetrics.Window.RTT).getCount() == 0);
    }

    @Test
    public void snapshot_doesNotChangeAfterLaterRecording() {
        PipelineMetrics metrics = PipelineMetrics.create(new MutableNanoClock(), 8);
        metrics.record(PipelineMetrics.Window.RTT, 5_000_000L);

        MetricsSnapshot before = metrics.snapshot(1000L);
        metrics.record(PipelineMetrics.Window.RTT, 9_000_000L);
        MetricsSnapshot after = metrics.snapshot(1000L);

        assertEquals(1, before.getWindow(PipelineMetrics.Window.RTT).getCount());
        assertEquals(2, after.getWindow(PipelineMetrics.Window.RTT).getCount());
        assertNotEquals(before, after);
    }

    @Test
    public void nowNs_readsTheInjectedClock() {
        MutableNanoClock clock = new MutableNanoClock(1_000L);
        PipelineMetrics metrics = PipelineMetrics.create(clock);

        clock.advance(500L);

        assertEquals(1_500L, metrics.nowNs());
        assertSame(clock, metrics.getClock());
    }

    @Test
    public void concurrentRecording_keepsEverySampleCounted() throws Exception {
        final PipelineMetrics metrics = PipelineMetrics.create(new MutableNanoClock(), 64);
        final int threads = 2;
        final int perThread = 500;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            Thread writer = new Thread(() -> {
                try {
                    start.await();
                    for (int sample = 0; sample < perThread; sample++) {
                        metrics.increment(PipelineMetrics.Counter.ACCESS_UNITS);
                        metrics.record(PipelineMetrics.Window.ARRIVAL, 1_000L);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            writer.setDaemon(true);
            writer.start();
        }
        start.countDown();
        assertTrue(done.await(5L, TimeUnit.SECONDS));

        assertEquals(threads * perThread,
                metrics.count(PipelineMetrics.Counter.ACCESS_UNITS));
        assertEquals(threads * perThread,
                metrics.getRecordedCount(PipelineMetrics.Window.ARRIVAL));
        assertEquals(64, metrics.snapshot(1000L)
                .getWindow(PipelineMetrics.Window.ARRIVAL).getCount());
    }

    @Test
    public void invalidFactoryArguments_areRejected() {
        assertThrows(IllegalArgumentException.class, () -> PipelineMetrics.create(null));
        assertThrows(IllegalArgumentException.class,
                () -> PipelineMetrics.create(new MutableNanoClock(), 0));
    }

    @Test
    public void snapshotRejectsIncompleteArrays() {
        LatencyWindow.Stats[] windows =
                new LatencyWindow.Stats[PipelineMetrics.Window.values().length];
        long[] counters = new long[PipelineMetrics.Counter.values().length];
        long[] gauges = new long[PipelineMetrics.Gauge.values().length];

        assertThrows(IllegalArgumentException.class,
                () -> new MetricsSnapshot(1000L, null, counters, gauges));
        assertThrows(IllegalArgumentException.class,
                () -> new MetricsSnapshot(1000L, windows, new long[1], gauges));
        assertThrows(IllegalArgumentException.class,
                () -> new MetricsSnapshot(1000L, windows, counters, new long[1]));
    }

    @Test
    public void snapshotArraysAreCopied() {
        LatencyWindow.Stats[] windows =
                new LatencyWindow.Stats[PipelineMetrics.Window.values().length];
        for (int i = 0; i < windows.length; i++) {
            windows[i] = Stats.EMPTY;
        }
        long[] counters = new long[PipelineMetrics.Counter.values().length];
        long[] gauges = new long[PipelineMetrics.Gauge.values().length];
        counters[PipelineMetrics.Counter.ACCESS_UNITS.ordinal()] = 4L;

        MetricsSnapshot snapshot = new MetricsSnapshot(1000L, windows, counters, gauges);
        counters[PipelineMetrics.Counter.ACCESS_UNITS.ordinal()] = 99L;

        assertEquals(4L, snapshot.getCounter(PipelineMetrics.Counter.ACCESS_UNITS));
    }
}
