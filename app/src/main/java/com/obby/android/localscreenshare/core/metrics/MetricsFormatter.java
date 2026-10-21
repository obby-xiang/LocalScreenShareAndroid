package com.obby.android.localscreenshare.core.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Renders a {@link MetricsSnapshot} as one structured log line or as the three debug-overlay
 * lines.
 *
 * <p>This is the only place in the metrics package that builds strings. It runs on an aggregation
 * thread, never on a probe, and it is a pure function of the snapshot: the same snapshot always
 * produces the same text, which is what makes the key set testable.</p>
 *
 * <p><b>Empty metrics are omitted.</b> A window without samples and a counter whose value is zero
 * are skipped in the log line, so a line stays comparable and short (well under 512 bytes) and a
 * disabled scope produces only the framing fields. The overlay keeps a fixed shape instead, so
 * its three lines do not jump while the user reads them.</p>
 *
 * <p><b>Units.</b> Latency values are printed in milliseconds with one decimal; sizes, counts and
 * rates are printed as integers; the round-trip kind is printed as a symbol from
 * {@link RttMetricNames}.</p>
 */
public final class MetricsFormatter {

    /**
     * Which pipeline a snapshot describes; the role selects both the key set and their order.
     */
    public enum Role {

        /**
         * Server-side capture, encode, fan-out and send metrics.
         */
        SERVER,

        /**
         * Client-side receive, decode and present metrics.
         */
        CLIENT
    }

    /**
     * Which summary of a window a row prints.
     */
    private enum Stat {

        /**
         * Median sample.
         */
        P50(".p50"),

        /**
         * 95th percentile sample.
         */
        P95(".p95"),

        /**
         * 99th percentile sample.
         */
        P99(".p99"),

        /**
         * Largest sample.
         */
        MAX(".max");

        private final String suffix;

        Stat(String suffix) {
            this.suffix = suffix;
        }
    }

    /**
     * Unit of a printed value; it decides the conversion, never the storage.
     */
    private enum Unit {

        /**
         * Nanoseconds, printed as milliseconds with one decimal.
         */
        NANOS,

        /**
         * Bytes, printed as an integer.
         */
        BYTES,

        /**
         * Count, printed as an integer.
         */
        COUNT
    }

    /**
     * One window row of the log line.
     */
    private static final class WindowRow {

        private final String key;

        private final PipelineMetrics.Window window;

        private final Unit unit;

        private final Stat[] stats;

        private WindowRow(String key, PipelineMetrics.Window window, Unit unit, Stat... stats) {
            this.key = key;
            this.window = window;
            this.unit = unit;
            this.stats = stats;
        }
    }

    private static final WindowRow[] SERVER_ROWS = {
            new WindowRow(ServerMetricNames.ENC_CAP_TO_OUT, PipelineMetrics.Window.CAP_TO_OUT,
                    Unit.NANOS, Stat.P50, Stat.P95, Stat.MAX),
            new WindowRow(ServerMetricNames.ENC_OUT_INTERVAL, PipelineMetrics.Window.OUT_INTERVAL,
                    Unit.NANOS, Stat.P50, Stat.P95),
            new WindowRow(ServerMetricNames.ENC_AU_BYTES, PipelineMetrics.Window.AU_BYTES,
                    Unit.BYTES, Stat.P95, Stat.MAX),
            new WindowRow(ServerMetricNames.TX_SEND, PipelineMetrics.Window.SEND,
                    Unit.NANOS, Stat.P50, Stat.P95, Stat.MAX),
            new WindowRow(ServerMetricNames.TX_QUEUE_RESIDENCY, PipelineMetrics.Window.QUEUE_RESIDENCY,
                    Unit.NANOS, Stat.P95, Stat.MAX),
            new WindowRow(ServerMetricNames.TX_QUEUE_DEPTH, PipelineMetrics.Window.QUEUE_DEPTH,
                    Unit.COUNT, Stat.MAX),
    };

    private static final WindowRow[] CLIENT_ROWS = {
            new WindowRow(ClientMetricNames.RX_ARRIVAL, PipelineMetrics.Window.ARRIVAL,
                    Unit.NANOS, Stat.P50, Stat.P95, Stat.P99),
            new WindowRow(ClientMetricNames.RX_ROWD, PipelineMetrics.Window.ROWD,
                    Unit.NANOS, Stat.P50, Stat.P95, Stat.MAX),
            new WindowRow(ClientMetricNames.RX_QUEUE_WAIT, PipelineMetrics.Window.QUEUE_WAIT,
                    Unit.NANOS, Stat.P50, Stat.P95),
            new WindowRow(ClientMetricNames.DEC_LATENCY, PipelineMetrics.Window.DECODE_LATENCY,
                    Unit.NANOS, Stat.P50, Stat.P95, Stat.MAX),
            new WindowRow(ClientMetricNames.PRES_INTERVAL, PipelineMetrics.Window.PRESENT_INTERVAL,
                    Unit.NANOS, Stat.P50, Stat.P95),
            new WindowRow(ClientMetricNames.PRES_LATE, PipelineMetrics.Window.PRESENT_LATE,
                    Unit.NANOS, Stat.P95, Stat.MAX),
            new WindowRow(ClientMetricNames.RX_STALL_MAX, PipelineMetrics.Window.STALL_DURATION,
                    Unit.NANOS, Stat.MAX),
            new WindowRow(ClientMetricNames.FREEZE_MAX, PipelineMetrics.Window.PRESENT_INTERVAL,
                    Unit.NANOS, Stat.MAX),
            new WindowRow(ClientMetricNames.JOIN_LATENCY, PipelineMetrics.Window.JOIN_LATENCY,
                    Unit.NANOS, Stat.P95),
            new WindowRow(ClientMetricNames.SWITCH_BLACKOUT, PipelineMetrics.Window.SWITCH_BLACKOUT,
                    Unit.NANOS, Stat.MAX),
            new WindowRow(RttMetricNames.RTT, PipelineMetrics.Window.RTT,
                    Unit.NANOS, Stat.P50),
            new WindowRow(RttMetricNames.RTT, PipelineMetrics.Window.RTT,
                    Unit.NANOS, Stat.P95),
    };

    private MetricsFormatter() {
    }

    /**
     * Renders one single-line structured log record.
     *
     * @param role     pipeline the snapshot describes; must not be null
     * @param snapshot snapshot to render; must not be null
     * @return one line starting with {@link MetricNames#PREFIX}, without a trailing newline
     * @throws IllegalArgumentException if an argument is null
     */
    public static String toLogLine(Role role, MetricsSnapshot snapshot) {
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        StringBuilder out = new StringBuilder(320);
        out.append(MetricNames.PREFIX).append(' ')
                .append(MetricNames.KEY_ROLE).append('=')
                .append(role == Role.SERVER ? "server" : "client")
                .append(' ').append(MetricNames.KEY_PERIOD).append('=')
                .append(snapshot.getPeriodMs());
        if (role == Role.SERVER) {
            appendServerFields(out, snapshot);
        } else {
            appendClientFields(out, snapshot);
        }
        return out.toString();
    }

    /**
     * Renders the three debug-overlay lines.
     *
     * <p>The shape is fixed: frame rate, bitrate and round trip on the first line; queue depth,
     * decode latency and lateness on the second; relative delay, stalls, local drops and key-frame
     * requests on the third. Missing values print as {@code n/a} instead of disappearing, so the
     * overlay does not reflow while a user watches it.</p>
     *
     * @param snapshot snapshot to render; must not be null
     * @return an immutable list of exactly three lines
     * @throws IllegalArgumentException if {@code snapshot} is null
     */
    public static List<String> toHud(MetricsSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        List<String> lines = new ArrayList<>(3);

        StringBuilder rate = new StringBuilder(64);
        appendTenths(rate, snapshot.perSecond(PipelineMetrics.Counter.ACCESS_UNITS));
        rate.append(" fps | ");
        appendTenths(rate, snapshot.bitrateBps() / 1_000_000d);
        rate.append(" Mbps | ");
        LatencyWindow.Stats rtt = snapshot.getWindow(PipelineMetrics.Window.RTT);
        if (rtt.hasSamples()) {
            rate.append("RTT ");
            appendMillis(rate, rtt.getP50());
            rate.append(" ms");
        } else {
            rate.append("RTT n/a");
        }
        lines.add(rate.toString());

        StringBuilder decode = new StringBuilder(64);
        decode.append("q ").append(snapshot.getWindow(PipelineMetrics.Window.QUEUE_DEPTH).getMax());
        decode.append(" | dec ");
        LatencyWindow.Stats decodeLatency =
                snapshot.getWindow(PipelineMetrics.Window.DECODE_LATENCY);
        if (decodeLatency.hasSamples()) {
            appendMillis(decode, decodeLatency.getP50());
            decode.append(" ms (p95 ");
            appendMillis(decode, decodeLatency.getP95());
            decode.append(')');
        } else {
            decode.append("n/a");
        }
        decode.append(" | late ");
        appendMillis(decode, snapshot.getWindow(PipelineMetrics.Window.PRESENT_LATE).getMax());
        lines.add(decode.toString());

        StringBuilder delay = new StringBuilder(64);
        delay.append("rowd +");
        appendMillis(delay, snapshot.getWindow(PipelineMetrics.Window.ROWD).getP95());
        delay.append(" | stall ").append(snapshot.getCounter(PipelineMetrics.Counter.STALLS));
        delay.append(" | drop ").append(snapshot.getCounter(PipelineMetrics.Counter.PRESENT_DROPS));
        delay.append(" | kf ").append(snapshot.getCounter(PipelineMetrics.Counter.KEYFRAME_REQUESTS));
        lines.add(delay.toString());

        return Collections.unmodifiableList(lines);
    }

    /**
     * Appends server-side windows, derived rates and counters.
     */
    private static void appendServerFields(StringBuilder out, MetricsSnapshot snapshot) {
        appendRows(out, snapshot, SERVER_ROWS);
        out.append(' ').append(ServerMetricNames.ENC_AU_RATE).append('=');
        appendTenths(out, snapshot.perSecond(PipelineMetrics.Counter.ACCESS_UNITS));
        out.append(' ').append(ServerMetricNames.ENC_BITRATE).append('=')
                .append(Math.round(snapshot.bitrateBps()));
        appendCounters(out, snapshot, ServerMetricNames.TX_CLIENTS, null,
                PipelineMetrics.Gauge.PEER_COUNT);
        appendCounters(out, snapshot, ServerMetricNames.KF_REQUEST,
                PipelineMetrics.Counter.JOIN_REQUESTS, null);
        appendCounters(out, snapshot, ServerMetricNames.KF_JOINS,
                PipelineMetrics.Counter.JOIN_POINTS, null);
        appendCounters(out, snapshot, ServerMetricNames.TX_SENT,
                PipelineMetrics.Counter.SENT_UNITS, null);
        appendCounters(out, snapshot, ServerMetricNames.TX_DROP,
                PipelineMetrics.Counter.DROPPED_UNITS, null);
        appendCounters(out, snapshot, ServerMetricNames.TX_RESYNC,
                PipelineMetrics.Counter.RESYNCS, null);
    }

    /**
     * Appends client-side windows, counters, gauges and round-trip fields.
     */
    private static void appendClientFields(StringBuilder out, MetricsSnapshot snapshot) {
        appendRows(out, snapshot, CLIENT_ROWS);
        appendCounters(out, snapshot, ClientMetricNames.RX_STALL,
                PipelineMetrics.Counter.STALLS, null);
        appendCounters(out, snapshot, ClientMetricNames.RX_SEQ_GAP,
                PipelineMetrics.Counter.SEQUENCE_GAPS, null);
        appendCounters(out, snapshot, ClientMetricNames.RX_SHED,
                PipelineMetrics.Counter.ARRIVAL_SHEDS, null);
        appendCounters(out, snapshot, ClientMetricNames.DEC_DEADLINE_MISS,
                PipelineMetrics.Counter.DEADLINE_MISSES, null);
        appendCounters(out, snapshot, ClientMetricNames.DEC_MERGED,
                PipelineMetrics.Counter.DECODE_MERGED, null);
        appendCounters(out, snapshot, ClientMetricNames.DEC_SHED,
                PipelineMetrics.Counter.DECODE_SHEDS, null);
        appendCounters(out, snapshot, ClientMetricNames.PRES_DROP,
                PipelineMetrics.Counter.PRESENT_DROPS, null);
        appendCounters(out, snapshot, ClientMetricNames.FREEZE,
                PipelineMetrics.Counter.FREEZES, null);
        appendCounters(out, snapshot, ClientMetricNames.KEYFRAME_SENT,
                PipelineMetrics.Counter.KEYFRAME_REQUESTS, null);
        appendRttFields(out, snapshot);
    }

    /**
     * Appends the round-trip average, loss count and sample source.
     */
    private static void appendRttFields(StringBuilder out, MetricsSnapshot snapshot) {
        long ewma = snapshot.getGauge(PipelineMetrics.Gauge.RTT_EWMA);
        if (ewma > 0L) {
            out.append(' ').append(RttMetricNames.RTT_EWMA).append('=');
            appendMillis(out, ewma);
        }
        long lost = snapshot.getCounter(PipelineMetrics.Counter.RTT_LOST);
        if (lost > 0L) {
            out.append(' ').append(RttMetricNames.RTT_LOST).append('=').append(lost);
        }
        long samples = snapshot.getCounter(PipelineMetrics.Counter.RTT_SAMPLES);
        if (samples > 0L) {
            out.append(' ').append(RttMetricNames.RTT_KIND).append('=')
                    .append(snapshot.getGauge(PipelineMetrics.Gauge.RTT_KIND)
                            == PipelineMetrics.RTT_KIND_KEYFRAME_UPPER
                            ? RttMetricNames.KIND_KEYFRAME_UPPER
                            : RttMetricNames.KIND_PING_PONG);
        }
    }

    /**
     * Appends every window row that has samples.
     */
    private static void appendRows(StringBuilder out, MetricsSnapshot snapshot,
                                   WindowRow[] rows) {
        for (WindowRow row : rows) {
            LatencyWindow.Stats stats = snapshot.getWindow(row.window);
            if (!stats.hasSamples()) {
                continue;
            }
            for (Stat stat : row.stats) {
                out.append(' ').append(row.key).append(stat.suffix).append('=');
                appendValue(out, statValue(stats, stat), row.unit);
            }
        }
    }

    /**
     * Appends one counter or gauge field when its value is not zero.
     */
    private static void appendCounters(StringBuilder out, MetricsSnapshot snapshot, String key,
                                       PipelineMetrics.Counter counter,
                                       PipelineMetrics.Gauge gauge) {
        long value = counter != null
                ? snapshot.getCounter(counter)
                : snapshot.getGauge(gauge);
        if (value != 0L) {
            out.append(' ').append(key).append('=').append(value);
        }
    }

    /**
     * Returns the sample a stat refers to.
     */
    private static long statValue(LatencyWindow.Stats stats, Stat stat) {
        switch (stat) {
            case P50:
                return stats.getP50();
            case P95:
                return stats.getP95();
            case P99:
                return stats.getP99();
            case MAX:
            default:
                return stats.getMax();
        }
    }

    /**
     * Appends one value in its printed unit.
     */
    private static void appendValue(StringBuilder out, long value, Unit unit) {
        switch (unit) {
            case NANOS:
                appendMillis(out, value);
                break;
            case BYTES:
            case COUNT:
            default:
                out.append(value);
                break;
        }
    }

    /**
     * Appends nanoseconds as milliseconds with exactly one decimal, without allocating.
     */
    private static void appendMillis(StringBuilder out, long nanos) {
        long value = Math.max(0L, nanos);
        out.append(value / 1_000_000L).append('.')
                .append((value % 1_000_000L) / 100_000L);
    }

    /**
     * Appends a floating value with exactly one decimal, without allocating.
     */
    private static void appendTenths(StringBuilder out, double value) {
        long tenths = Math.round(value * 10d);
        out.append(tenths / 10L).append('.').append(Math.abs(tenths % 10L));
    }
}
