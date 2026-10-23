package com.obby.android.localscreenshare.core.metrics;

/**
 * Metric keys recorded by the server-side pipeline probes.
 *
 * <p>Every key names one stage of the server path: capture to encoder output, encoder output
 * cadence and size, join-point handling, fan-out and the per-client send queue. Latency values are
 * printed in milliseconds with one decimal, sizes and counts as integers.</p>
 *
 * <p><b>Domain capacity.</b> The class is budgeted for the twenty server-side keys of the
 * performance batch: the thermal forecaster adds a pair and the backpressure coordinator the last
 * three, which fills that budget. A further server-side key must first split this class by domain
 * instead of growing it past twenty.</p>
 */
public final class ServerMetricNames {

    /**
     * Capture timestamp to retrievable encoder output, including encoder queueing.
     */
    public static final String ENC_CAP_TO_OUT = "enc.cap2out";

    /**
     * Difference between two consecutive encoder output timestamps.
     */
    public static final String ENC_OUT_INTERVAL = "enc.outInterval";

    /**
     * Encoded access-unit payload size.
     */
    public static final String ENC_AU_BYTES = "enc.auBytes";

    /**
     * Encoded access units per second over the measured interval.
     */
    public static final String ENC_AU_RATE = "enc.auRate";

    /**
     * Encoded payload bits per second over the measured interval.
     */
    public static final String ENC_BITRATE = "enc.bitrate";

    /**
     * Join-point requests that reached the encoder.
     */
    public static final String KF_REQUEST = "kf.request";

    /**
     * Join points actually forwarded to the sessions.
     */
    public static final String KF_JOINS = "kf.joins";

    /**
     * Attached client sessions at snapshot time.
     */
    public static final String TX_CLIENTS = "tx.clients";

    /**
     * Highest send-queue depth observed in the period.
     */
    public static final String TX_QUEUE_DEPTH = "tx.qDepth";

    /**
     * Payload bytes retained in the send queue at snapshot time.
     */
    public static final String TX_QUEUE_BYTES = "tx.qBytes";

    /**
     * Duration of one transport send call.
     */
    public static final String TX_SEND = "tx.send";

    /**
     * Time an access unit spent queued before it was polled.
     */
    public static final String TX_QUEUE_RESIDENCY = "tx.qResidency";

    /**
     * Access units successfully sent.
     */
    public static final String TX_SENT = "tx.sent";

    /**
     * Access units dropped by the send queue.
     */
    public static final String TX_DROP = "tx.drop";

    /**
     * Send-session resynchronization episodes.
     */
    public static final String TX_RESYNC = "tx.resync";

    /**
     * Thermal pressure in permille, predicted by the platform for the next ten seconds.
     *
     * <p>One thousand means the platform starts throttling and larger values mean the device is
     * already throttled, so the value is read for its trend and is never clamped. Zero means never
     * sampled, or a sample that was NaN or out of range; it is not printed.</p>
     */
    public static final String THERMAL_HEADROOM = "thermalHeadroom";

    /**
     * Thermal tier in force: one for none, two for the reduced tier, three for the minimal tier.
     *
     * <p>Zero means the headroom has never been sampled and is not printed; the key is therefore
     * absent from a line that did not sample rather than present with a "not throttled" value.</p>
     */
    public static final String THERMAL_TIER = "thermalTier";

    /**
     * Bitrate actions the backpressure coordinator applied successfully.
     */
    public static final String BP_APPLY = "bp.apply";

    /**
     * Backlog reports the coordinator did not act on.
     *
     * <p>The reasons are multiple clients, another codec, the master switch, an unknown descriptor,
     * an external write of the bitrate, a current bitrate that is not positive, a computed target
     * that is not positive, a lower target that is already at the floor, and an encoder failure. An
     * external write is counted when the bitrate read back differs from the last value this
     * coordinator applied, so the override is dropped instead of overwriting the user setting, a
     * thermal tier or a size change. A current bitrate of zero or less is counted before the
     * report is classified, so the encoder is not called; a target the policy computes as zero or
     * less is counted after the decision and is skipped the same way. A target already at the
     * floor is counted even though the encoder is not called. A failure outside the encoder call
     * - the master switch or a target getter - is counted as a coordinator skip under the same
     * log throttle.</p>
     *
     * <p>A report that only waits for the five-second interval or for a second clear report is not
     * a skip, because no decision was overridden.</p>
     */
    public static final String BP_SKIP = "bp.skip";

    /**
     * Target bitrate in force after the last successful backpressure action, in bits per second.
     */
    public static final String BP_BITRATE = "bp.bitrate";

    private ServerMetricNames() {
    }
}
