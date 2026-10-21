package com.obby.android.localscreenshare.core.metrics;

/**
 * Metric keys recorded by the client-side pipeline probes.
 *
 * <p>The keys answer two questions separately: whether frames arrived on time from the network
 * ({@code rx.*}) and whether the client managed to decode and present them on time
 * ({@code dec.*}, {@code pres.*}). Freeze counting follows the WebRTC definition so the numbers
 * can be compared with an external tool.</p>
 */
public final class ClientMetricNames {

    /**
     * Interval between two consecutive received access units.
     */
    public static final String RX_ARRIVAL = "rx.arr";

    /**
     * Received access units per second over the measured interval.
     */
    public static final String RX_AU_RATE = "rx.auRate";

    /**
     * Received payload bits per second over the measured interval.
     */
    public static final String RX_BITRATE = "rx.bitrate";

    /**
     * Relative one-way delay against the window minimum.
     */
    public static final String RX_ROWD = "rx.rowd";

    /**
     * Number of arrival gaps that exceeded the stall threshold.
     */
    public static final String RX_STALL = "rx.stall";

    /**
     * Longest arrival gap in the period.
     */
    public static final String RX_STALL_MAX = "rx.stall";

    /**
     * Non-contiguous access-unit sequences.
     */
    public static final String RX_SEQ_GAP = "rx.seqGap";

    /**
     * Highest decode-queue depth observed in the period.
     */
    public static final String RX_QUEUE_DEPTH = "rx.qDepth";

    /**
     * Time a message waited between decode-queue submission and decode start.
     */
    public static final String RX_QUEUE_WAIT = "rx.qWait";

    /**
     * Messages dropped because the decode queue was full.
     */
    public static final String RX_SHED = "rx.shed";

    /**
     * Decoder input submission to retrievable decoder output.
     */
    public static final String DEC_LATENCY = "dec.lat";

    /**
     * Frames whose output became available after their presentation target.
     */
    public static final String DEC_DEADLINE_MISS = "dec.deadlineMiss";

    /**
     * Decoded outputs discarded because a newer one was already available.
     */
    public static final String DEC_MERGED = "dec.merged";

    /**
     * Frames dropped because the decoder input budget was exhausted.
     */
    public static final String DEC_SHED = "dec.shed";

    /**
     * Interval between two consecutive presented frames.
     */
    public static final String PRES_INTERVAL = "pres.interval";

    /**
     * Lateness of a presented frame against its own target time.
     */
    public static final String PRES_LATE = "pres.late";

    /**
     * Frames the presentation pacer decided to drop.
     */
    public static final String PRES_DROP = "pres.drop";

    /**
     * Presentation freezes counted with the WebRTC freeze definition.
     */
    public static final String FREEZE = "freeze";

    /**
     * Longest presentation interval in the period.
     */
    public static final String FREEZE_MAX = "freeze";

    /**
     * Key-frame request to first join point arrival.
     */
    public static final String JOIN_LATENCY = "join.latency";

    /**
     * New descriptor arrival to first presented frame.
     */
    public static final String SWITCH_BLACKOUT = "switch.blackout";

    /**
     * Key-frame requests issued by the client.
     */
    public static final String KEYFRAME_SENT = "kf.sent";

    private ClientMetricNames() {
    }
}
