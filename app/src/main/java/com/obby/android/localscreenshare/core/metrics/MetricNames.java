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
     * Field carrying the cadence the line was requested at, in milliseconds.
     *
     * <p>It is framing only: counters on the line are cumulative for the life of their scope, and a
     * rate is measured over its own interval, so the field must not be used as a divisor.</p>
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

    /**
     * Prefix of the device-state log line, which is emitted next to the metric lines.
     */
    public static final String DEVICE_PREFIX = "lss.device";

    /**
     * Thermal status reported by the platform.
     */
    public static final String KEY_THERMAL = "thermal";

    /**
     * Battery temperature in degrees Celsius.
     */
    public static final String KEY_BATTERY_TEMPERATURE = "battTempC";

    /**
     * Used heap in megabytes, native and Java added together.
     */
    public static final String KEY_HEAP_MB = "heapMB";

    /**
     * Number of live threads in the process.
     */
    public static final String KEY_THREADS = "threads";

    /**
     * Value printed for a reading the platform did not provide.
     */
    public static final String VALUE_UNKNOWN = "n/a";

    private MetricNames() {
    }
}
