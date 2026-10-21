package com.obby.android.localscreenshare.core.metrics;

/**
 * Frame and byte rates measured over one closed interval.
 *
 * <p><b>A rate is a delta, not a total.</b> {@code PipelineMetrics} counters only grow, so dividing
 * one by a nominal period ratio reports the average since the scope was created rather than the rate
 * of the current period: on a five-second cadence that reads eight frames per second after one
 * period, sixteen after two, and never comes down. A {@link RateSampler} therefore closes an
 * interval by subtracting two readings, and carries the measured duration of that interval here
 * instead of letting a reader guess it.</p>
 *
 * <p><b>Units.</b> {@link #getPeriodMs()} is the measured interval, {@link #getUnits()} the access
 * units that arrived or left inside it and {@link #getBytes()} the payload bytes they carried. A
 * rate with a zero period has no interval yet, which {@link #hasInterval()} reports explicitly so a
 * formatter can print {@code n/a} instead of a fabricated zero.</p>
 *
 * <p>Immutable and allocation-only on the aggregation thread; probes never create one.</p>
 */
public final class MetricsRate {

    /**
     * Rate of an interval that has not been measured yet: zero duration, zero units and zero bytes.
     */
    public static final MetricsRate EMPTY = new MetricsRate(0L, 0L, 0L);

    private static final long MILLIS_PER_SECOND = 1000L;

    private static final long BITS_PER_BYTE = 8L;

    private final long periodMs;

    private final long units;

    private final long bytes;

    /**
     * Creates one rate reading.
     *
     * @param periodMs measured interval in milliseconds; zero when no interval was measured
     * @param units    access units counted inside the interval
     * @param bytes    payload bytes counted inside the interval
     * @throws IllegalArgumentException if any value is negative
     */
    public MetricsRate(long periodMs, long units, long bytes) {
        if (periodMs < 0L || units < 0L || bytes < 0L) {
            throw new IllegalArgumentException("rate values must not be negative");
        }
        this.periodMs = periodMs;
        this.units = units;
        this.bytes = bytes;
    }

    /**
     * Returns the measured interval in milliseconds; zero when no interval was measured.
     */
    public long getPeriodMs() {
        return periodMs;
    }

    /**
     * Returns the access units counted inside the interval.
     */
    public long getUnits() {
        return units;
    }

    /**
     * Returns the payload bytes counted inside the interval.
     */
    public long getBytes() {
        return bytes;
    }

    /**
     * Returns whether the interval has a positive duration, that is the rates below are meaningful.
     */
    public boolean hasInterval() {
        return periodMs > 0L;
    }

    /**
     * Returns the access-unit rate in frames per second, or zero without an interval.
     */
    public double getFramesPerSecond() {
        if (!hasInterval()) {
            return 0d;
        }
        return units * (double) MILLIS_PER_SECOND / periodMs;
    }

    /**
     * Returns the payload rate in bits per second, or zero without an interval.
     */
    public double getBitsPerSecond() {
        if (!hasInterval()) {
            return 0d;
        }
        return bytes * (double) (MILLIS_PER_SECOND * BITS_PER_BYTE) / periodMs;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MetricsRate)) {
            return false;
        }
        MetricsRate that = (MetricsRate) other;
        return periodMs == that.periodMs && units == that.units && bytes == that.bytes;
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(periodMs);
        result = 31 * result + Long.hashCode(units);
        result = 31 * result + Long.hashCode(bytes);
        return result;
    }

    @Override
    public String toString() {
        return "MetricsRate{periodMs=" + periodMs + ", units=" + units + ", bytes=" + bytes + '}';
    }
}
