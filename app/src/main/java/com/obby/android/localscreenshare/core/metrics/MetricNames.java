package com.obby.android.localscreenshare.core.metrics;

/**
 * Metric keys shared by both roles of the structured log line.
 *
 * <p>Keys are grouped by domain into separate holders so that no single class exceeds the
 * twenty-value limit of the naming conventions: this class holds the line framing and the
 * estimated keys, {@link ServerMetricNames} the server pipeline keys,
 * {@link ClientMetricNames} the client pipeline keys and {@link RttMetricNames} the round-trip
 * keys. A key is a stable external contract: changing one makes historical log lines
 * incomparable, so it needs the same care as a protocol field.</p>
 *
 * <p>Keys prefixed with {@code approx.} are estimates, not measurements, and must never be quoted
 * as facts: {@link #APPROX_BACKLOG} and {@link #APPROX_FLOW_WINDOW} are derived from counters on
 * both sides or from a queue size, not read from the transport.</p>
 */
public final class MetricNames {

    /**
     * Prefix of every structured metric log line.
     */
    public static final String PREFIX = "lss.metrics";

    /**
     * Role field, either {@code server} or {@code client}.
     */
    public static final String KEY_ROLE = "role";

    /**
     * Field carrying the period the counters accumulated over, in milliseconds.
     */
    public static final String KEY_PERIOD = "period";

    /**
     * Field carrying the number of attached peers, without any peer address.
     */
    public static final String KEY_PEERS = "peers";

    /**
     * Field carrying the last eight characters of the session's random client id.
     */
    public static final String KEY_SESSION_ID = "sid";

    /**
     * Estimated access units retained in the transport between both endpoints.
     */
    public static final String APPROX_BACKLOG = "approx.tx.backlog";

    /**
     * Estimated share of the flow-control window occupied by the local send queue.
     */
    public static final String APPROX_FLOW_WINDOW = "approx.tx.flowWindow";

    private MetricNames() {
    }
}
