package com.obby.android.localscreenshare.core.metrics;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Fixed-capacity ring buffer of {@code long} samples with lock-free writes and nearest-rank
 * percentiles.
 *
 * <p><b>Hot path.</b> {@link #record(long)} performs one atomic increment and one volatile array
 * store: no allocation, no lock and no formatting. The buffer never grows; once {@code capacity}
 * samples have been recorded the oldest slot is overwritten, so a snapshot always describes the
 * most recent samples. Concurrent writers are tolerated, but their samples may interleave.</p>
 *
 * <p><b>Snapshot.</b> {@link #snapshot()} copies the live samples, sorts the copy and derives
 * {@code p50/p95/p99/max/count}. Copying and sorting allocate, so they belong on an aggregation
 * thread and never on a probe. A reader concurrent with a writer can observe a mixture of old and
 * new samples, but never a torn {@code long}, because the samples live in an
 * {@link AtomicLongArray}.</p>
 *
 * <p><b>Percentiles.</b> Nearest rank with integer arithmetic: {@code p} is the sample at sorted
 * index {@code ceil(p / 100 * n) - 1}, clamped to {@code [0, n - 1]}. For {@code n = 1} every
 * percentile is the single sample; for {@code n = 100} the 95th percentile is index 94. The
 * definition is fixed here so tests can assert exact values.</p>
 */
public final class LatencyWindow {

    /**
     * Capacity used by long-window scopes (a five-second snapshot at sixty frames per second).
     */
    public static final int DEFAULT_CAPACITY = 512;

    private final AtomicLongArray samples;

    // capacity - 1; the capacity is rounded up to a power of two so the wrap can be a mask.
    private final int mask;

    private final AtomicLong cursor = new AtomicLong();

    /**
     * Creates a window with the default capacity.
     */
    public LatencyWindow() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Creates a window that keeps the most recent {@code capacity} samples.
     *
     * @param capacity number of samples to retain; must be positive
     * @throws IllegalArgumentException if {@code capacity} is not positive
     */
    public LatencyWindow(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        int size = 1;
        while (size < capacity) {
            size <<= 1;
        }
        this.samples = new AtomicLongArray(size);
        this.mask = size - 1;
    }

    /**
     * Records one sample, overwriting the oldest one when the window is full.
     *
     * <p>Allocation free and lock free; safe to call from the probe path.</p>
     *
     * @param sample value to record; its unit is defined by the metric, never converted here
     */
    public void record(long sample) {
        samples.set((int) (cursor.getAndIncrement() & mask), sample);
    }

    /**
     * Copies, sorts and summarizes the live samples.
     *
     * <p>Allocates a {@code long[]} and one {@link Stats} instance, so it must not be called from a
     * probe.</p>
     *
     * @return the percentile summary, or {@link Stats#EMPTY} when nothing was recorded
     */
    public Stats snapshot() {
        long written = cursor.get();
        int size = (int) Math.min(written, mask + 1L);
        if (size <= 0) {
            return Stats.EMPTY;
        }
        long[] copy = new long[size];
        long start = written - size;
        for (int i = 0; i < size; i++) {
            copy[i] = samples.get((int) ((start + i) & mask));
        }
        Arrays.sort(copy);
        return new Stats(percentile(copy, 50), percentile(copy, 95), percentile(copy, 99),
                copy[size - 1], size);
    }

    /**
     * Drops every sample; used when a scope is reset for a new config epoch.
     */
    public void reset() {
        cursor.set(0L);
    }

    /**
     * Returns how many samples were recorded since the last {@link #reset()}, including samples
     * already overwritten by newer ones.
     */
    public long getRecordedCount() {
        return cursor.get();
    }

    /**
     * Returns the sample at the nearest rank of {@code percent}.
     */
    private static long percentile(long[] sorted, int percent) {
        int rank = (percent * sorted.length + 99) / 100;
        if (rank < 1) {
            rank = 1;
        }
        return sorted[rank - 1];
    }

    /**
     * Immutable percentile summary of one window.
     */
    public static final class Stats {

        /**
         * Summary of a window without samples; every percentile and the maximum are zero.
         */
        public static final Stats EMPTY = new Stats(0L, 0L, 0L, 0L, 0);

        private final long p50;

        private final long p95;

        private final long p99;

        private final long max;

        private final int count;

        private Stats(long p50, long p95, long p99, long max, int count) {
            this.p50 = p50;
            this.p95 = p95;
            this.p99 = p99;
            this.max = max;
            this.count = count;
        }

        /**
         * Returns the median sample.
         */
        public long getP50() {
            return p50;
        }

        /**
         * Returns the 95th percentile sample.
         */
        public long getP95() {
            return p95;
        }

        /**
         * Returns the 99th percentile sample.
         */
        public long getP99() {
            return p99;
        }

        /**
         * Returns the largest sample.
         */
        public long getMax() {
            return max;
        }

        /**
         * Returns how many samples the summary covers.
         */
        public int getCount() {
            return count;
        }

        /**
         * Returns whether the summary covers at least one sample.
         */
        public boolean hasSamples() {
            return count > 0;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Stats)) {
                return false;
            }
            Stats that = (Stats) other;
            return p50 == that.p50 && p95 == that.p95 && p99 == that.p99
                    && max == that.max && count == that.count;
        }

        @Override
        public int hashCode() {
            int result = Long.hashCode(p50);
            result = 31 * result + Long.hashCode(p95);
            result = 31 * result + Long.hashCode(p99);
            result = 31 * result + Long.hashCode(max);
            result = 31 * result + count;
            return result;
        }

        @Override
        public String toString() {
            return "Stats{p50=" + p50 + ", p95=" + p95 + ", p99=" + p99 + ", max=" + max
                    + ", count=" + count + '}';
        }
    }
}
