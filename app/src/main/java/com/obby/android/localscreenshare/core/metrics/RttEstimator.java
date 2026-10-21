package com.obby.android.localscreenshare.core.metrics;

import com.obby.android.localscreenshare.core.util.NanoClock;

/**
 * Estimates the application-level round trip from echoed probe messages.
 *
 * <p><b>Why it exists.</b> gRPC does not expose the HTTP/2 ping time to the application, so the
 * only way to measure the path is a probe the peer echoes. The estimator keeps the send time of
 * every outstanding probe in a fixed table, matches the echo by sequence, and reports the
 * distribution plus an exponential average for the overlay.</p>
 *
 * <p><b>Loss.</b> A probe that is not echoed within {@link #TIMEOUT_NS} counts as lost and is
 * dropped. Nothing else happens: the estimator never closes a session and never retries
 * aggressively, because a lost sample is congestion information, not a failure. The table holds
 * {@link #CAPACITY} outstanding probes; a new probe that would overwrite an older unacknowledged
 * one drops that one silently, which cannot happen at the intended one-per-second cadence.</p>
 *
 * <p><b>Threading.</b> Probe starts, echoes and timeout sweeps may run on different threads, so the
 * public methods synchronize. The cost is irrelevant at one probe per second and keeps the call
 * sites simple.</p>
 */
public final class RttEstimator {

    /**
     * Outstanding probes the table can track at once.
     */
    public static final int CAPACITY = 8;

    /**
     * Time after which an unanswered probe counts as lost, in nanoseconds.
     */
    public static final long TIMEOUT_NS = 2_000_000_000L;

    /**
     * Weight of the newest sample in the exponential average, one eighth.
     */
    private static final double EWMA_ALPHA = 0.125d;

    private final PipelineMetrics metrics;

    private final NanoClock clock;

    private final long[] sequenceSlots = new long[CAPACITY];

    private final long[] sentAtNs = new long[CAPACITY];

    private final boolean[] pending = new boolean[CAPACITY];

    private int nextSequence;

    private long ewmaNs;

    private boolean hasEwma;

    /**
     * Creates an estimator that reports into {@code metrics}.
     *
     * @param metrics metric scope of the receiving pipeline; required, may be
     *                {@link PipelineMetrics#NONE}
     * @param clock   clock used to stamp the probes; required
     * @throws IllegalArgumentException if an argument is null
     */
    public RttEstimator(PipelineMetrics metrics, NanoClock clock) {
        if (metrics == null) {
            throw new IllegalArgumentException("metrics must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Starts one probe and returns the sequence number to send.
     *
     * @return the sequence number carried by the next ping
     */
    public synchronized int beginProbe() {
        if (!metrics.isEnabled()) {
            return 0;
        }
        int seq = nextSequence++;
        int slot = Math.floorMod(seq, CAPACITY);
        sequenceSlots[slot] = seq;
        sentAtNs[slot] = clock.nowNs();
        pending[slot] = true;
        return seq;
    }

    /**
     * Consumes an echo.
     *
     * <p>An unknown, duplicated or stale sequence is ignored, so a reordered or delayed echo can
     * never invent a sample.</p>
     *
     * @param seq sequence number echoed by the peer
     * @return true when the echo matched an outstanding probe and produced a sample
     */
    public synchronized boolean onPong(int seq) {
        if (!metrics.isEnabled()) {
            return false;
        }
        int slot = Math.floorMod(seq, CAPACITY);
        if (!pending[slot] || sequenceSlots[slot] != seq) {
            return false;
        }
        long sent = sentAtNs[slot];
        pending[slot] = false;
        long now = clock.nowNs();
        if (now < sent) {
            return false;
        }
        long rtt = now - sent;
        metrics.record(PipelineMetrics.Window.RTT, rtt);
        metrics.increment(PipelineMetrics.Counter.RTT_SAMPLES);
        metrics.setGauge(PipelineMetrics.Gauge.RTT_KIND,
                PipelineMetrics.RTT_KIND_PING_PONG);
        ewmaNs = hasEwma ? Math.round(ewmaNs + EWMA_ALPHA * (rtt - ewmaNs)) : rtt;
        hasEwma = true;
        metrics.setGauge(PipelineMetrics.Gauge.RTT_EWMA, ewmaNs);
        return true;
    }

    /**
     * Counts every probe that stayed unanswered for longer than {@link #TIMEOUT_NS}.
     *
     * @return the number of probes counted as lost by this call
     */
    public synchronized int checkTimeouts() {
        if (!metrics.isEnabled()) {
            return 0;
        }
        long now = clock.nowNs();
        int lost = 0;
        for (int slot = 0; slot < CAPACITY; slot++) {
            if (pending[slot] && now - sentAtNs[slot] > TIMEOUT_NS) {
                pending[slot] = false;
                lost++;
            }
        }
        if (lost > 0) {
            metrics.add(PipelineMetrics.Counter.RTT_LOST, lost);
        }
        return lost;
    }

    /**
     * Returns the current exponential average in nanoseconds, or zero before the first sample.
     */
    public synchronized long getEwmaNs() {
        return hasEwma ? ewmaNs : 0L;
    }
}
