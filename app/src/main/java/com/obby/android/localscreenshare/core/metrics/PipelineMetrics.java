package com.obby.android.localscreenshare.core.metrics;

import com.obby.android.localscreenshare.core.util.NanoClock;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Metric holder for one measurement scope: one encoder, one client send session, one client
 * receive session.
 *
 * <p><b>Scopes, not singletons.</b> A process-wide registry would mix a server's encoder with
 * every client session, so each scope owns its own instance. {@link #NONE} is the shared disabled
 * instance: every recording method returns on its first check, so a pipeline that is not measured
 * pays one volatile read per probe and allocates nothing.</p>
 *
 * <p><b>Hot path contract.</b> {@link #record(Window, long)} and the counter methods are
 * allocation free and lock free: a window is created on first use, counters are
 * {@link LongAdder} instances created eagerly, and gauges are a fixed array. A probe may call
 * {@link #nowNs()} once instead of reading {@link System#nanoTime()} directly, which keeps the
 * clock injectable for tests.</p>
 *
 * <p><b>Reading.</b> {@link #snapshot(long)} copies every used window into an immutable
 * {@link MetricsSnapshot}; percentiles are computed there, never on a probe. A scope whose
 * {@link #isEnabled()} is false always reports empty windows and zero counters.</p>
 *
 * <p><b>Units.</b> Latency windows store nanoseconds, {@link Window#AU_BYTES} stores payload bytes
 * and {@link Window#QUEUE_DEPTH} stores queued units. Counters store plain monotonic counts, except
 * {@link Counter#ACCESS_UNIT_BYTES}, which accumulates payload bytes so a reader can derive an
 * interval bitrate by subtracting two readings.</p>
 */
public final class PipelineMetrics {

    /**
     * Shared disabled instance; recording on it does nothing.
     */
    public static final PipelineMetrics NONE = new PipelineMetrics(false, NanoClock.MONOTONIC,
            LatencyWindow.DEFAULT_CAPACITY);

    /**
     * {@link Gauge#RTT_KIND} value for samples taken from an echoed ping message.
     */
    public static final long RTT_KIND_PING_PONG = 0L;

    /**
     * {@link Gauge#RTT_KIND} value for samples taken from a key-frame request round trip, which
     * includes encoding latency and is therefore an upper bound rather than a path round trip.
     */
    public static final long RTT_KIND_KEYFRAME_UPPER = 1L;

    /**
     * Sample windows, one per measured latency or size distribution.
     */
    public enum Window {

        /**
         * Capture timestamp to retrievable encoder output, in nanoseconds.
         */
        CAP_TO_OUT,

        /**
         * Difference between two consecutive encoder output timestamps, in nanoseconds.
         */
        OUT_INTERVAL,

        /**
         * Encoded access-unit payload size, in bytes.
         */
        AU_BYTES,

        /**
         * Sending queue depth observed by the drain loop, in units.
         */
        QUEUE_DEPTH,

        /**
         * Duration of one transport send call, in nanoseconds.
         */
        SEND,

        /**
         * Time an access unit spent queued before it was polled, in nanoseconds.
         */
        QUEUE_RESIDENCY,

        /**
         * Interval between two consecutive received access units, in nanoseconds.
         */
        ARRIVAL,

        /**
         * Relative one-way delay of a received access unit against the window minimum, in
         * nanoseconds.
         */
        ROWD,

        /**
         * Time a message waited between decode-queue submission and decode start, in nanoseconds.
         */
        QUEUE_WAIT,

        /**
         * Decoder input submission to retrievable decoder output, in nanoseconds.
         */
        DECODE_LATENCY,

        /**
         * Interval between two consecutive presented frames, in nanoseconds.
         */
        PRESENT_INTERVAL,

        /**
         * Lateness of a presented frame against its own target time, in nanoseconds.
         */
        PRESENT_LATE,

        /**
         * Duration of an arrival gap that exceeded the stall threshold, in nanoseconds.
         */
        STALL_DURATION,

        /**
         * Key-frame request to first join point arrival, in nanoseconds.
         */
        JOIN_LATENCY,

        /**
         * New descriptor arrival to first presented frame, in nanoseconds.
         */
        SWITCH_BLACKOUT,

        /**
         * Application-level round-trip time, in nanoseconds.
         */
        RTT,

        /**
         * Catch-up start to the accepted join point that ended it, in nanoseconds.
         */
        CATCH_UP_LATENCY,

        /**
         * Absolute audio/video offset observed by the audio drift guard, in nanoseconds.
         */
        AV_OFFSET,

        /**
         * Interval between two consecutive fragments of the same access unit, in nanoseconds.
         *
         * <p>Only fragments of one unit are compared: the interval across two units contains the
         * frame interval and says nothing about how long a fragment of one unit may be held back.
         * This distribution is the calibration input for the hold-up of the reassembler.</p>
         */
        FRAGMENT_GAP
    }

    /**
     * Monotonic counters, one per measured event or accumulation.
     */
    public enum Counter {

        /**
         * Access units produced or received, depending on the scope.
         */
        ACCESS_UNITS,

        /**
         * Payload bytes carried by {@link #ACCESS_UNITS}.
         */
        ACCESS_UNIT_BYTES,

        /**
         * Join points forwarded by the runtime.
         */
        JOIN_POINTS,

        /**
         * Join-point requests that reached the encoder.
         */
        JOIN_REQUESTS,

        /**
         * Access units successfully sent by a send session.
         */
        SENT_UNITS,

        /**
         * Access units dropped by a send queue.
         */
        DROPPED_UNITS,

        /**
         * Send-session resynchronization episodes.
         */
        RESYNCS,

        /**
         * Messages dropped because the decode queue was full.
         */
        ARRIVAL_SHEDS,

        /**
         * Arrival gaps that exceeded the stall threshold.
         */
        STALLS,

        /**
         * Non-contiguous access-unit sequences.
         */
        SEQUENCE_GAPS,

        /**
         * Frames dropped because the decoder input budget was exhausted.
         */
        DECODE_SHEDS,

        /**
         * Decoded outputs discarded because a newer one was already available.
         */
        DECODE_MERGED,

        /**
         * Frames the presentation pacer decided to drop.
         */
        PRESENT_DROPS,

        /**
         * Frames whose output became available after their presentation target.
         */
        DEADLINE_MISSES,

        /**
         * Presentation freezes, following the WebRTC freeze definition.
         */
        FREEZES,

        /**
         * Key-frame requests issued by the client.
         */
        KEYFRAME_REQUESTS,

        /**
         * Round-trip samples received.
         */
        RTT_SAMPLES,

        /**
         * Round-trip samples that timed out.
         */
        RTT_LOST,

        /**
         * Catch-up episodes started by the viewer.
         */
        CATCH_UP_EPISODES,

        /**
         * Messages discarded because a catch-up superseded them before they were decoded.
         */
        CATCH_UP_SHEDS,

        /**
         * Catch-up attempts aborted because the stream did not converge.
         */
        CONVERGENCE_FAILURES,

        /**
         * Audio frames received and accepted by the client audio session.
         */
        AUDIO_FRAMES,

        /**
         * Audio frames dropped by the arrival queue, the transport or the jitter buffer.
         */
        AUDIO_DROPPED,

        /**
         * Audio playback starvations: the output ran dry and had to be padded with silence or
         * restarted.
         *
         * <p>One count per episode, which is the rate the device report reads as "the buffer never
         * fills". Separate from {@link #AUDIO_DRIFT_INSERT}: a starvation is the sink running dry, a
         * drift insert is the guard delaying the audio on purpose. They used to share one counter,
         * which made "the buffer never fills" and "the guard keeps padding" indistinguishable in the
         * log.</p>
         */
        AUDIO_STARVED,

        /**
         * Silence frames the drift guard inserted because the audio ran ahead of the timeline.
         */
        AUDIO_DRIFT_INSERT,

        /**
         * Buffered audio frames the drift guard dropped because the audio ran behind the timeline.
         */
        AUDIO_DRIFT_DROP,

        /**
         * Datagrams received on a media channel.
         */
        DATAGRAMS_RECEIVED,

        /**
         * Datagram sequence numbers the receiver never saw, as reported by its reorder window.
         */
        DATAGRAMS_LOST,

        /**
         * Datagrams the receiver already had.
         */
        DATAGRAMS_DUPLICATE,

        /**
         * Datagrams that arrived out of order but were still usable.
         */
        DATAGRAMS_REORDERED,

        /**
         * Access units abandoned because at least one fragment never arrived.
         */
        ACCESS_UNITS_INCOMPLETE,

        /**
         * Times a send session moved to a different media channel.
         */
        MEDIA_CHANNEL_SWITCHES,

        /**
         * Datagrams sent by a media channel, one per fragment of an access unit.
         */
        FRAGMENTS_SENT,

        /**
         * Datagrams refused by authentication.
         */
        MEDIA_AUTH_FAILURES,

        /**
         * Key-frame requests raised because a datagram channel lost a whole access unit.
         *
         * <p>Counted where the receiver decides to ask, before the outbound throttle, so a request
         * the throttle drops is still visible as "loss wanted a key frame" - which is what tells a
         * field capture whether the loss-to-IDR chain fired at all.</p>
         */
        UDP_JOIN_POINT_REQUESTS,

        /**
         * Fragments that arrived after their access unit was delivered or abandoned.
         *
         * <p>The receiver compares the reassembler's late count around each datagram, so the counter
         * measures fragments the reassembler itself refused - it does not re-parse the datagram. Both
         * endings are covered: a delivered unit and an abandoned one can no longer produce a frame,
         * and the reassembler remembers both.</p>
         */
        DATAGRAMS_LATE,

        /**
         * Access units abandoned because no fragment arrived within the hold-up.
         *
         * <p>One count per abandoned unit, a subset of {@link #ACCESS_UNITS_INCOMPLETE}: the total
         * stays the loss reading that the device report compares against, while this counter tells
         * a timeout from an eviction or a protocol violation. On a connection that loses no
         * fragment this counter staying at zero is the direct evidence that the hold-up no longer
         * kills paced units.</p>
         */
        ACCESS_UNITS_TIMED_OUT,

        /**
         * Access units discarded whole because a newer unit was delivered first.
         *
         * <p>The healthy path of a pace that outran assembly, and the opposite reading to
         * {@link #ACCESS_UNITS_TIMED_OUT}: a superseded unit was overtaken, a timed-out one was
         * still arriving. Also a subset of {@link #ACCESS_UNITS_INCOMPLETE}, so the total stays the
         * loss reading while the reason tells which defect produced it.</p>
         */
        ACCESS_UNITS_SUPERSEDED
    }

    /**
     * Instantaneous values that are not a distribution.
     */
    public enum Gauge {

        /**
         * Exponentially weighted moving average of the round-trip samples, in nanoseconds.
         */
        RTT_EWMA,

        /**
         * Source of the round-trip samples; see {@link RttMetricNames}.
         */
        RTT_KIND,

        /**
         * Number of attached peers at snapshot time.
         */
        PEER_COUNT,

        /**
         * One when the capture-to-output probe disabled itself after repeated implausible
         * samples, otherwise zero.
         */
        CAP_TO_OUT_SUPPRESSED,

        /**
         * Signal that started the latest catch-up; see {@link #CATCH_UP_REASON_QUEUE_WAIT},
         * {@link #CATCH_UP_REASON_QUEUE_DEPTH} and {@link #CATCH_UP_REASON_EXTERNAL}. Zero before
         * the first episode.
         */
        CATCH_UP_REASON,

        /**
         * Hold-up the reassembler currently applies to an unfinished access unit, in milliseconds.
         *
         * <p>Written by the receiver when the reassembler's window changes - it starts at the
         * warm-up value and follows the measured fragment intervals of the units in flight - so a log
         * capture records the threshold the gap readings next to it were taken under. It is a bound
         * of the window, not a promise about latency: a unit is still delivered the moment its last
         * fragment lands.</p>
         */
        MEDIA_HOLD_UP_MS
    }

    /**
     * {@link Gauge#CATCH_UP_REASON} value: an access unit waited longer than the backlog threshold
     * between submission and decode start.
     */
    public static final long CATCH_UP_REASON_QUEUE_WAIT = 1L;

    /**
     * {@link Gauge#CATCH_UP_REASON} value: the decode queue reached its backlog depth.
     */
    public static final long CATCH_UP_REASON_QUEUE_DEPTH = 2L;

    /**
     * {@link Gauge#CATCH_UP_REASON} value: a lost whole access unit opened the episode, so the
     * signal is a network fault rather than decode backlog.
     */
    public static final long CATCH_UP_REASON_EXTERNAL = 3L;

    private final boolean enabled;

    private final NanoClock clock;

    private final int windowCapacity;

    // Null elements are created on first use, so a scope only pays for the metrics it records.
    private final AtomicReferenceArray<LatencyWindow> windows =
            new AtomicReferenceArray<>(Window.values().length);

    private final LongAdder[] counters = new LongAdder[Counter.values().length];

    private final AtomicLongArray gauges = new AtomicLongArray(Gauge.values().length);

    private PipelineMetrics(boolean enabled, NanoClock clock, int windowCapacity) {
        this.enabled = enabled;
        this.clock = clock;
        this.windowCapacity = windowCapacity;
        for (int i = 0; i < counters.length; i++) {
            counters[i] = new LongAdder();
        }
    }

    /**
     * Creates an enabled scope with the default window capacity.
     *
     * @param clock clock used by {@link #nowNs()}; must not be null
     * @throws IllegalArgumentException if {@code clock} is null
     */
    public static PipelineMetrics create(NanoClock clock) {
        return create(clock, LatencyWindow.DEFAULT_CAPACITY);
    }

    /**
     * Creates an enabled scope with an explicit window capacity.
     *
     * <p>A dedicated snapshot cadence uses a smaller window: one second at sixty frames per
     * second fits in 128 samples, while five seconds keeps the default of 512.</p>
     *
     * @param clock           clock used by {@link #nowNs()}; must not be null
     * @param windowCapacity  samples retained per window; must be positive
     * @throws IllegalArgumentException if {@code clock} is null or the capacity is not positive
     */
    public static PipelineMetrics create(NanoClock clock, int windowCapacity) {
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        if (windowCapacity <= 0) {
            throw new IllegalArgumentException("windowCapacity must be positive");
        }
        return new PipelineMetrics(true, clock, windowCapacity);
    }

    /**
     * Creates an equivalent empty scope for another session of the same pipeline.
     *
     * <p>Used when one configured scope acts as a template: every client send session needs its
     * own windows, because queue depth and send duration belong to one viewer, while the clock
     * and the window capacity stay identical. A disabled template produces {@link #NONE}, so a
     * pipeline that is not measured never allocates per-session state.</p>
     *
     * @return a new empty scope, or {@link #NONE} when this scope is disabled
     */
    public PipelineMetrics newScope() {
        return enabled ? new PipelineMetrics(true, clock, windowCapacity) : NONE;
    }

    /**
     * Returns whether this scope records anything.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the injected clock's current value, so a probe reads the clock once per timestamp.
     */
    public long nowNs() {
        return clock.nowNs();
    }

    /**
     * Returns the clock used by this scope.
     */
    public NanoClock getClock() {
        return clock;
    }

    /**
     * Records one sample into a window, creating that window on first use.
     *
     * @param window window to write; must not be null
     * @param sample value in the unit documented by the window
     */
    public void record(Window window, long sample) {
        if (!enabled) {
            return;
        }
        LatencyWindow target = windows.get(window.ordinal());
        if (target == null) {
            target = createWindow(window);
        }
        target.record(sample);
    }

    /**
     * Increments one counter by one.
     */
    public void increment(Counter counter) {
        if (!enabled) {
            return;
        }
        counters[counter.ordinal()].increment();
    }

    /**
     * Adds {@code delta} to one counter.
     */
    public void add(Counter counter, long delta) {
        if (!enabled) {
            return;
        }
        counters[counter.ordinal()].add(delta);
    }

    /**
     * Sets an instantaneous value.
     */
    public void setGauge(Gauge gauge, long value) {
        if (!enabled) {
            return;
        }
        gauges.set(gauge.ordinal(), value);
    }

    /**
     * Returns how many samples a window recorded since the scope was created.
     */
    public long getRecordedCount(Window window) {
        LatencyWindow target = enabled ? windows.get(window.ordinal()) : null;
        return target == null ? 0L : target.getRecordedCount();
    }

    /**
     * Returns a window's current summary; {@link LatencyWindow.Stats#EMPTY} when unused.
     */
    public LatencyWindow.Stats stats(Window window) {
        LatencyWindow target = enabled ? windows.get(window.ordinal()) : null;
        return target == null ? LatencyWindow.Stats.EMPTY : target.snapshot();
    }

    /**
     * Returns a counter's current value; zero when the scope is disabled.
     */
    public long count(Counter counter) {
        return enabled ? counters[counter.ordinal()].sum() : 0L;
    }

    /**
     * Returns a gauge's current value; zero when the scope is disabled.
     */
    public long getGauge(Gauge gauge) {
        return enabled ? gauges.get(gauge.ordinal()) : 0L;
    }

    /**
     * Copies every window, counter and gauge into an immutable snapshot.
     *
     * <p>Allocates; intended for the aggregation thread. Counters are copied as they are, that is
     * cumulative for the lifetime of this scope; {@code periodMs} is only the cadence the line
     * framing prints and the window span the percentiles describe, never a divisor for counters.
     * Interval rates are derived by the reader through a {@link RateSampler}.</p>
     *
     * @param periodMs cadence this snapshot was requested at, printed in the line framing
     * @return an immutable snapshot, never null
     */
    public MetricsSnapshot snapshot(long periodMs) {
        LatencyWindow.Stats[] windowStats = new LatencyWindow.Stats[Window.values().length];
        long[] counterValues = new long[Counter.values().length];
        long[] gaugeValues = new long[Gauge.values().length];
        if (enabled) {
            for (int i = 0; i < windowStats.length; i++) {
                LatencyWindow target = windows.get(i);
                windowStats[i] = target == null ? LatencyWindow.Stats.EMPTY : target.snapshot();
            }
            for (int i = 0; i < counterValues.length; i++) {
                counterValues[i] = counters[i].sum();
            }
            for (int i = 0; i < gaugeValues.length; i++) {
                gaugeValues[i] = gauges.get(i);
            }
        } else {
            for (int i = 0; i < windowStats.length; i++) {
                windowStats[i] = LatencyWindow.Stats.EMPTY;
            }
        }
        return new MetricsSnapshot(periodMs, windowStats, counterValues, gaugeValues);
    }

    /**
     * Creates a window once per kind; concurrent first users race benignly on the array slot.
     */
    private LatencyWindow createWindow(Window window) {
        int index = window.ordinal();
        LatencyWindow created = new LatencyWindow(windowCapacity);
        if (windows.compareAndSet(index, null, created)) {
            return created;
        }
        return windows.get(index);
    }
}
