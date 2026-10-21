package com.obby.android.localscreenshare.core.metrics;

import java.util.Arrays;

/**
 * Immutable snapshot of one {@link PipelineMetrics} scope.
 *
 * <p>Windows, counters and gauges are copied by ordinal, so a snapshot is independent of the live
 * scope and can be passed between threads without locking. A disabled scope produces a snapshot
 * whose windows are all {@link LatencyWindow.Stats#EMPTY} and whose counters and gauges are zero,
 * which is what makes "the switch was off" observable instead of ambiguous.</p>
 */
public final class MetricsSnapshot {

    private final long periodMs;

    private final LatencyWindow.Stats[] windows;

    private final long[] counters;

    private final long[] gauges;

    /**
     * Creates a snapshot; the arrays are copied and must cover every enum constant.
     *
     * @param periodMs duration the counters accumulated over
     * @param windows  window summaries indexed by {@link PipelineMetrics.Window#ordinal()}
     * @param counters counter values indexed by {@link PipelineMetrics.Counter#ordinal()}
     * @param gauges   gauge values indexed by {@link PipelineMetrics.Gauge#ordinal()}
     * @throws IllegalArgumentException if an array is null or has the wrong length
     */
    public MetricsSnapshot(long periodMs, LatencyWindow.Stats[] windows, long[] counters,
                           long[] gauges) {
        if (windows == null || counters == null || gauges == null) {
            throw new IllegalArgumentException("snapshot arrays must not be null");
        }
        if (windows.length != PipelineMetrics.Window.values().length
                || counters.length != PipelineMetrics.Counter.values().length
                || gauges.length != PipelineMetrics.Gauge.values().length) {
            throw new IllegalArgumentException("snapshot arrays must cover every enum constant");
        }
        this.periodMs = periodMs;
        this.windows = Arrays.copyOf(windows, windows.length);
        this.counters = Arrays.copyOf(counters, counters.length);
        this.gauges = Arrays.copyOf(gauges, gauges.length);
    }

    /**
     * Returns the duration the counters accumulated over, in milliseconds.
     */
    public long getPeriodMs() {
        return periodMs;
    }

    /**
     * Returns one window's summary; never null.
     */
    public LatencyWindow.Stats getWindow(PipelineMetrics.Window window) {
        return windows[window.ordinal()];
    }

    /**
     * Returns one counter's value.
     */
    public long getCounter(PipelineMetrics.Counter counter) {
        return counters[counter.ordinal()];
    }

    /**
     * Returns one gauge's value.
     */
    public long getGauge(PipelineMetrics.Gauge gauge) {
        return gauges[gauge.ordinal()];
    }

    /**
     * Returns how many events per second a counter accumulated.
     *
     * @param counter counter to convert
     * @return events per second, or zero when the period is not positive
     */
    public double perSecond(PipelineMetrics.Counter counter) {
        if (periodMs <= 0L) {
            return 0d;
        }
        return getCounter(counter) * 1000d / periodMs;
    }

    /**
     * Returns how many bits per second the {@link PipelineMetrics.Counter#ACCESS_UNIT_BYTES}
     * counter accumulated.
     *
     * @return bits per second, or zero when the period is not positive
     */
    public double bitrateBps() {
        return perSecond(PipelineMetrics.Counter.ACCESS_UNIT_BYTES) * 8d;
    }

    /**
     * Returns whether every window is empty and every counter is zero, which is what a disabled
     * scope or a silent pipeline reports.
     */
    public boolean isEmpty() {
        for (LatencyWindow.Stats window : windows) {
            if (window.hasSamples()) {
                return false;
            }
        }
        for (long counter : counters) {
            if (counter != 0L) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MetricsSnapshot)) {
            return false;
        }
        MetricsSnapshot that = (MetricsSnapshot) other;
        return periodMs == that.periodMs && Arrays.equals(windows, that.windows)
                && Arrays.equals(counters, that.counters) && Arrays.equals(gauges, that.gauges);
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(periodMs);
        result = 31 * result + Arrays.hashCode(windows);
        result = 31 * result + Arrays.hashCode(counters);
        result = 31 * result + Arrays.hashCode(gauges);
        return result;
    }

    @Override
    public String toString() {
        return "MetricsSnapshot{periodMs=" + periodMs + ", counters=" + Arrays.toString(counters)
                + ", gauges=" + Arrays.toString(gauges) + '}';
    }
}
