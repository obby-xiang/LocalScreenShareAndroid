package com.obby.android.localscreenshare.core.metrics;

/**
 * Turns two readings of a scope's monotonically increasing counters into one interval rate.
 *
 * <p><b>Why the reader owns the baseline.</b> One scope can be read by more than one consumer: the
 * server writes its metric line every five seconds, the viewer refreshes its overlay every second
 * from the same scope. A baseline stored in the scope would be closed by whichever reader ran
 * first, so a five-second line would report the last one-second sliver. Each reader keeps its own
 * sampler instead, and the counters themselves stay cumulative and untouched.</p>
 *
 * <p><b>Measuring the period.</b> The interval is measured from the scope's clock rather than
 * assumed from the requested cadence: a main-thread handler can be delayed by an encoder rebuild or
 * a window change, and dividing by the nominal period would then inflate every rate. The
 * constructor primes the baseline, so the first {@link #sample()} already covers a full interval.</p>
 *
 * <p><b>Cost and threads.</b> One clock read and two counter sums per sample; nothing is written to
 * the scope, so probes are unaffected. A sampler belongs to the thread that reads it (the services
 * read on the main thread) and is not synchronized.</p>
 */
public final class RateSampler {

    private final PipelineMetrics metrics;

    private long lastNs;

    private long lastUnits;

    private long lastBytes;

    /**
     * Creates a sampler primed with the scope's current counters.
     *
     * @param metrics scope to read; must not be null
     * @throws IllegalArgumentException if {@code metrics} is null
     */
    public RateSampler(PipelineMetrics metrics) {
        if (metrics == null) {
            throw new IllegalArgumentException("metrics must not be null");
        }
        this.metrics = metrics;
        this.lastNs = metrics.nowNs();
        this.lastUnits = metrics.count(PipelineMetrics.Counter.ACCESS_UNITS);
        this.lastBytes = metrics.count(PipelineMetrics.Counter.ACCESS_UNIT_BYTES);
    }

    /**
     * Closes the current interval and returns its rate.
     *
     * <p>Returns {@link MetricsRate#EMPTY} when the scope is disabled or when the clock has not
     * advanced, because neither case has an interval to divide by. The baseline is re-anchored
     * either way, so the next sample recovers on its own.</p>
     *
     * @return the rate of the interval since the previous sample, never null
     */
    public MetricsRate sample() {
        if (!metrics.isEnabled()) {
            return MetricsRate.EMPTY;
        }
        final long nowNs = metrics.nowNs();
        final long units = metrics.count(PipelineMetrics.Counter.ACCESS_UNITS);
        final long bytes = metrics.count(PipelineMetrics.Counter.ACCESS_UNIT_BYTES);
        final long elapsedMs = (nowNs - lastNs) / 1_000_000L;
        final long deltaUnits = units - lastUnits;
        final long deltaBytes = bytes - lastBytes;
        lastNs = nowNs;
        lastUnits = units;
        lastBytes = bytes;
        if (elapsedMs <= 0L) {
            return MetricsRate.EMPTY;
        }
        return new MetricsRate(elapsedMs, deltaUnits, deltaBytes);
    }
}
