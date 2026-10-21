package com.obby.android.localscreenshare.core.metrics;

/**
 * Metric keys recorded by the server-side pipeline probes.
 *
 * <p>Every key names one stage of the server path: capture to encoder output, encoder output
 * cadence and size, join-point handling, fan-out and the per-client send queue. Latency values are
 * printed in milliseconds with one decimal, sizes and counts as integers.</p>
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

    private ServerMetricNames() {
    }
}
