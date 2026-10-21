package com.obby.android.localscreenshare.core.metrics;

/**
 * Derives the receive-side indicators from the arrival timestamps of incoming access units.
 *
 * <p><b>What it measures.</b> The tracker turns a raw {@code (epoch, sequence, pts, arrival)}
 * stream into the four values that answer "did the network deliver frames on time":
 * {@code rx.arr} (the interval between two arrivals), {@code rx.rowd} (the relative one-way delay
 * against the fastest arrival of a sliding window), {@code rx.stall} (arrival gaps far above the
 * recent cadence) and {@code rx.seqGap} (missing sequences on the wire).</p>
 *
 * <p><b>Why ROWD needs no clock synchronisation.</b> The producer's timestamps and the local
 * monotonic clock share an unknown but constant offset. Subtracting the smallest observed offset
 * over a thirty-second window cancels the offset and the fixed pipeline cost, so what remains is
 * "how much longer than the fastest frame did this one take" - queueing and jitter growth. The
 * minimum window is re-anchored when it expires and when a jump of more than five seconds
 * indicates an epoch change or a clock discontinuity, so one outlier cannot pin the baseline
 * forever.</p>
 *
 * <p><b>Stall definition.</b> A gap counts as a stall when it exceeds
 * {@code max(3 x mean of the last thirty intervals, 150 ms)}, the same shape WebRTC uses for a
 * freeze, so the count can be compared with an external tool.</p>
 *
 * <p><b>Cost.</b> One clock-free call per access unit: no allocation, no lock, a fixed ring of
 * thirty intervals, and a single metric write per recorded value. A disabled scope makes the whole
 * call a single volatile read.</p>
 */
public final class ArrivalTracker {

    /**
     * Marker for "no previous value".
     */
    private static final long UNSET = Long.MIN_VALUE;

    /**
     * Marker for "no previous config epoch".
     */
    private static final int UNSET_EPOCH = Integer.MIN_VALUE;

    /**
     * Number of intervals the stall threshold is averaged over.
     */
    private static final int INTERVAL_WINDOW = 30;

    /**
     * Floor of the stall threshold, in nanoseconds; a cadence-independent lower bound so a burst of
     * frames cannot make an ordinary gap look like a stall.
     */
    private static final long STALL_FLOOR_NS = 150_000_000L;

    /**
     * Lifetime of the relative-delay minimum, in nanoseconds.
     */
    private static final long ROWD_WINDOW_NS = 30_000_000_000L;

    /**
     * Jitter above which the relative-delay baseline is re-anchored instead of recorded, because
     * the offset between the two clocks is no longer constant.
     */
    private static final long ROWD_DISCONTINUITY_NS = 5_000_000_000L;

    private final PipelineMetrics metrics;

    private final long[] intervals = new long[INTERVAL_WINDOW];

    private int intervalCount;

    private int intervalCursor;

    private long lastArrivalNs = UNSET;

    private long lastObservedNs = UNSET;

    private long minObservedNs = UNSET;

    private long minObservedAtNs = UNSET;

    private long lastSequence = UNSET;

    private int lastEpoch = UNSET_EPOCH;

    /**
     * Creates a tracker that reports into {@code metrics}.
     *
     * @param metrics metric scope of the receiving pipeline; required, may be
     *                {@link PipelineMetrics#NONE}
     * @throws IllegalArgumentException if {@code metrics} is null
     */
    public ArrivalTracker(PipelineMetrics metrics) {
        if (metrics == null) {
            throw new IllegalArgumentException("metrics must not be null");
        }
        this.metrics = metrics;
    }

    /**
     * Feeds one received access unit.
     *
     * <p>Must be called on the single thread that receives units for the session, in wire order.
     * The first unit after construction or after a discontinuity only establishes the anchors; it
     * records no interval and no relative delay, because neither has a previous value yet.</p>
     *
     * @param configEpoch configuration epoch of the unit
     * @param sequence    sequence of the unit within its epoch
     * @param ptsUs       presentation timestamp reported by the producer, in microseconds
     * @param arrivalNs   local monotonic arrival time, in nanoseconds
     */
    public void feed(int configEpoch, long sequence, long ptsUs, long arrivalNs) {
        if (!metrics.isEnabled()) {
            return;
        }

        if (lastArrivalNs != UNSET && arrivalNs >= lastArrivalNs) {
            final long interval = arrivalNs - lastArrivalNs;
            final long threshold = getStallThresholdNs();
            metrics.record(PipelineMetrics.Window.ARRIVAL, interval);
            if (interval > threshold) {
                metrics.increment(PipelineMetrics.Counter.STALLS);
                metrics.record(PipelineMetrics.Window.STALL_DURATION, interval);
            }
            addInterval(interval);
        }

        if (lastEpoch == configEpoch && lastSequence != UNSET && sequence > lastSequence + 1L) {
            metrics.add(PipelineMetrics.Counter.SEQUENCE_GAPS, sequence - lastSequence - 1L);
        }

        recordRelativeDelay(arrivalNs - ptsUs * 1000L, arrivalNs);

        lastArrivalNs = arrivalNs;
        lastSequence = sequence;
        lastEpoch = configEpoch;
    }

    /**
     * Returns the current stall threshold: three times the mean of the last thirty intervals,
     * floored at 150 ms.
     */
    public long getStallThresholdNs() {
        if (intervalCount == 0) {
            return STALL_FLOOR_NS;
        }
        long sum = 0L;
        for (int i = 0; i < intervalCount; i++) {
            sum += intervals[i];
        }
        return Math.max(STALL_FLOOR_NS, 3L * (sum / intervalCount));
    }

    /**
     * Records the relative one-way delay, re-anchoring the baseline when it expired or when the
     * offset between the two clocks jumped.
     */
    private void recordRelativeDelay(long observedNs, long arrivalNs) {
        final boolean jump = lastObservedNs != UNSET
                && Math.abs(observedNs - lastObservedNs) > ROWD_DISCONTINUITY_NS;
        final boolean expired = minObservedAtNs != UNSET
                && arrivalNs - minObservedAtNs > ROWD_WINDOW_NS;
        if (minObservedNs == UNSET || observedNs < minObservedNs || expired || jump) {
            minObservedNs = observedNs;
            minObservedAtNs = arrivalNs;
            lastObservedNs = observedNs;
            return;
        }
        metrics.record(PipelineMetrics.Window.ROWD, observedNs - minObservedNs);
        lastObservedNs = observedNs;
    }

    /**
     * Adds one interval to the rolling average.
     */
    private void addInterval(long interval) {
        intervals[intervalCursor] = interval;
        intervalCursor = (intervalCursor + 1) % INTERVAL_WINDOW;
        if (intervalCount < INTERVAL_WINDOW) {
            intervalCount++;
        }
    }
}
