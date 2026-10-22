package com.obby.android.localscreenshare.core.metrics;

import com.obby.android.localscreenshare.core.net.MediaTransportKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Renders a {@link MetricsSnapshot} as one structured log line or as the four debug-overlay
 * lines.
 *
 * <p>This is the only place in the metrics package that builds strings. It runs on an aggregation
 * thread, never on a probe, and it is a pure function of the snapshot: the same snapshot always
 * produces the same text, which is what makes the key set testable.</p>
 *
 * <p><b>Empty metrics are omitted.</b> A window without samples and a counter whose value is zero
 * are skipped in the log line, so a line stays comparable and short (well under 512 bytes) and a
 * disabled scope produces only the framing fields. The overlay keeps a fixed shape instead, so
 * its four lines do not jump while the user reads them.</p>
 *
 * <p><b>Units.</b> Latency values are printed in milliseconds with one decimal; frame rate with one
 * decimal; sizes, counts and bitrates are printed as integers; the round-trip kind is printed as a
 * symbol from {@link RttMetricNames}. A rate always comes from a {@link MetricsRate} measured over
 * an interval, never from a cumulative counter divided by a period.</p>
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

    // One send session owns only the send stage, so its line carries the last three rows plus the
    // session counters; the encoder rows belong to the process-wide scope and would be empty here.
    private static final WindowRow[] SESSION_ROWS = {
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
            new WindowRow(CatchUpMetricNames.RX_CATCH_UP_LATENCY,
                    PipelineMetrics.Window.CATCH_UP_LATENCY, Unit.NANOS, Stat.P50, Stat.P95),
            new WindowRow(ClientMetricNames.SWITCH_BLACKOUT, PipelineMetrics.Window.SWITCH_BLACKOUT,
                    Unit.NANOS, Stat.MAX),
            new WindowRow(RttMetricNames.RTT, PipelineMetrics.Window.RTT,
                    Unit.NANOS, Stat.P50),
            new WindowRow(RttMetricNames.RTT, PipelineMetrics.Window.RTT,
                    Unit.NANOS, Stat.P95),
            new WindowRow(ClientMetricNames.AV_OFFSET, PipelineMetrics.Window.AV_OFFSET,
                    Unit.NANOS, Stat.P50, Stat.P95),
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
        return toLogLine(role, snapshot, null, null);
    }

    /**
     * Renders one single-line structured log record with a session identifier.
     *
     * <p>The identifier is the tail of the sender's random per-run client id; it is not a device
     * identity, and it is what lets the two ends of one session be matched in a log capture.</p>
     *
     * @param role       pipeline the snapshot describes; must not be null
     * @param snapshot   snapshot to render; must not be null
     * @param sessionId  session identifier to append, or null to omit the field
     * @return one line starting with {@link MetricNames#PREFIX}, without a trailing newline
     * @throws IllegalArgumentException if an argument is null
     */
    public static String toLogLine(Role role, MetricsSnapshot snapshot, String sessionId) {
        return toLogLine(role, snapshot, sessionId, null);
    }

    /**
     * Renders one single-line structured log record with a session identifier and interval rates.
     *
     * <p>Rates are printed only when {@code rate} measured an interval: a rate derived from a
     * cumulative counter would grow without bound, and a fabricated zero would be indistinguishable
     * from a genuinely silent pipeline, so an absent interval omits the fields instead.</p>
     *
     * @param role       pipeline the snapshot describes; must not be null
     * @param snapshot   snapshot to render; must not be null
     * @param sessionId  session identifier to append, or null to omit the field
     * @param rate       interval rate of this pipeline, or null when it was not measured
     * @return one line starting with {@link MetricNames#PREFIX}, without a trailing newline
     * @throws IllegalArgumentException if a required argument is null
     */
    public static String toLogLine(Role role, MetricsSnapshot snapshot, String sessionId,
                                   MetricsRate rate) {
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
        if (sessionId != null) {
            out.append(' ').append(MetricNames.KEY_SESSION_ID).append('=').append(sessionId);
        }
        if (role == Role.SERVER) {
            appendServerFields(out, snapshot, rate);
        } else {
            appendClientFields(out, snapshot, rate);
        }
        return out.toString();
    }

    /**
     * Renders one send session of the server as a structured log record.
     *
     * <p>The session owns its own metric scope, so its send duration, queue residency and queue
     * depth never reach the process-wide line. This method prints them under the same framing and
     * keys, with the session identifier that matches the viewer's own line, which is what makes
     * "the server blocked on this viewer" readable from a log capture. The line also carries the
     * media channel, because two readings of the same key are not comparable across channels: the
     * control-plane carrier and a dedicated media channel have different backpressure.</p>
     *
     * @param snapshot  snapshot of one session's metric scope; must not be null
     * @param sessionId session identifier to append, or null to omit the field
     * @return one line starting with {@link MetricNames#PREFIX}, without a trailing newline
     * @throws IllegalArgumentException if {@code snapshot} is null
     */
    public static String toSessionLogLine(MetricsSnapshot snapshot, String sessionId) {
        return toSessionLogLine(snapshot, sessionId, MediaTransportKind.GRPC);
    }

    /**
     * Renders one send session with the media channel it is talking over.
     *
     * @param snapshot  snapshot of one session's metric scope; must not be null
     * @param sessionId session identifier to append, or null to omit the field
     * @param transport media channel of that session; must not be null
     * @return one line starting with {@link MetricNames#PREFIX}, without a trailing newline
     * @throws IllegalArgumentException if a required argument is null
     */
    public static String toSessionLogLine(MetricsSnapshot snapshot, String sessionId,
                                          MediaTransportKind transport) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        if (transport == null) {
            throw new IllegalArgumentException("transport must not be null");
        }
        StringBuilder out = new StringBuilder(200);
        out.append(MetricNames.PREFIX).append(' ')
                .append(MetricNames.KEY_ROLE).append("=server")
                .append(' ').append(MetricNames.KEY_PERIOD).append('=')
                .append(snapshot.getPeriodMs());
        if (sessionId != null) {
            out.append(' ').append(MetricNames.KEY_SESSION_ID).append('=').append(sessionId);
        }
        out.append(' ').append(MetricNames.KEY_CHANNEL).append('=').append(transport.getLabel());
        appendRows(out, snapshot, SESSION_ROWS);
        appendCounters(out, snapshot, ServerMetricNames.TX_SENT,
                PipelineMetrics.Counter.SENT_UNITS, null);
        appendCounters(out, snapshot, ServerMetricNames.TX_DROP,
                PipelineMetrics.Counter.DROPPED_UNITS, null);
        appendCounters(out, snapshot, ServerMetricNames.TX_RESYNC,
                PipelineMetrics.Counter.RESYNCS, null);
        appendCounters(out, snapshot, MediaMetricNames.TX_CHANNEL_SWITCH,
                PipelineMetrics.Counter.MEDIA_CHANNEL_SWITCHES, null);
        return out.toString();
    }

    /**
     * Renders one device-state log record.
     *
     * <p>Emitted every ten seconds next to the metric lines. It answers "was the device hot or out
     * of memory" and deliberately carries no attribution: a hot device is context for a slow
     * pipeline, never the measured cause of it.</p>
     *
     * @param role    which side reported the reading; must not be null
     * @param profile reading to render; must not be null
     * @return one line starting with {@link MetricNames#DEVICE_PREFIX}
     * @throws IllegalArgumentException if an argument is null
     */
    public static String toDeviceLine(Role role, DeviceProfile profile) {
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        if (profile == null) {
            throw new IllegalArgumentException("profile must not be null");
        }
        StringBuilder out = new StringBuilder(96);
        out.append(MetricNames.DEVICE_PREFIX).append(' ')
                .append(MetricNames.KEY_ROLE).append('=')
                .append(role == Role.SERVER ? "server" : "client");
        out.append(' ').append(MetricNames.KEY_THERMAL).append('=');
        appendOptional(out, profile.getThermalStatus());
        out.append(' ').append(MetricNames.KEY_BATTERY_TEMPERATURE).append('=');
        if (profile.getBatteryTemperatureDeciC() == DeviceProfile.UNKNOWN) {
            out.append(MetricNames.VALUE_UNKNOWN);
        } else {
            appendTenths(out, profile.getBatteryTemperatureDeciC() / 10d);
        }
        out.append(' ').append(MetricNames.KEY_HEAP_MB).append('=');
        if (profile.getNativeHeapBytes() == DeviceProfile.UNKNOWN
                && profile.getJavaHeapBytes() == DeviceProfile.UNKNOWN) {
            out.append(MetricNames.VALUE_UNKNOWN);
        } else {
            long bytes = Math.max(0L, profile.getNativeHeapBytes())
                    + Math.max(0L, profile.getJavaHeapBytes());
            appendTenths(out, bytes / (1024d * 1024d));
        }
        out.append(' ').append(MetricNames.KEY_THREADS).append('=');
        appendOptional(out, profile.getThreadCount());
        return out.toString();
    }

    /**
     * Appends a value or the unknown marker.
     */
    private static void appendOptional(StringBuilder out, long value) {
        if (value == DeviceProfile.UNKNOWN) {
            out.append(MetricNames.VALUE_UNKNOWN);
        } else {
            out.append(value);
        }
    }

    /**
     * Renders the four debug-overlay lines.
     *
     * <p>The shape is fixed: frame rate, bitrate and round trip on the first line; queue depth,
     * decode latency and lateness on the second; relative delay, stalls, local drops, catch-ups and
     * key-frame requests on the third; the audio offset distribution and the audio counters on the
     * fourth. Missing values print as {@code n/a} instead of disappearing, so the overlay does not
     * reflow while a user watches it.</p>
     *
     * @param snapshot snapshot to render; must not be null
     * @return an immutable list of exactly four lines
     * @throws IllegalArgumentException if {@code snapshot} is null
     */
    public static List<String> toHud(MetricsSnapshot snapshot) {
        return toHud(snapshot, null);
    }

    /**
     * Renders the four debug-overlay lines with interval rates on the first line.
     *
     * <p>The overlay is refreshed every second while the log line runs every five, so the two
     * readers pass their own {@link MetricsRate}; a rate that has no interval yet prints
     * {@code n/a} rather than a zero the user would read as "the stream is dead".</p>
     *
     * @param snapshot snapshot to render; must not be null
     * @param rate     interval rate of the receiving pipeline, or null when it was not measured
     * @return an immutable list of exactly four lines
     * @throws IllegalArgumentException if {@code snapshot} is null
     */
    public static List<String> toHud(MetricsSnapshot snapshot, MetricsRate rate) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        List<String> lines = new ArrayList<>(4);

        StringBuilder first = new StringBuilder(64);
        if (rate != null && rate.hasInterval()) {
            appendTenths(first, rate.getFramesPerSecond());
            first.append(" fps | ");
            appendTenths(first, rate.getBitsPerSecond() / 1_000_000d);
            first.append(" Mbps | ");
        } else {
            first.append("n/a fps | n/a Mbps | ");
        }
        LatencyWindow.Stats rtt = snapshot.getWindow(PipelineMetrics.Window.RTT);
        if (rtt.hasSamples()) {
            first.append("RTT ");
            appendMillis(first, rtt.getP50());
            first.append(" ms");
        } else {
            first.append("RTT n/a");
        }
        lines.add(first.toString());

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
        delay.append(" | cu ").append(snapshot.getCounter(PipelineMetrics.Counter.CATCH_UP_EPISODES));
        delay.append(" | kf ").append(snapshot.getCounter(PipelineMetrics.Counter.KEYFRAME_REQUESTS));
        lines.add(delay.toString());

        lines.add(audioHudLine(snapshot));

        return Collections.unmodifiableList(lines);
    }

    /**
     * Renders the fourth overlay line: the audio offset distribution and the audio counters.
     *
     * <p>The line is short on purpose, because the overlay shrinks its text and truncates a line
     * that still overflows the narrowest window the viewer offers, rather than wrapping it.
     * {@code av} is the absolute audio/video offset against the shared
     * session clock, {@code af} the accepted audio frames, {@code ad} the frames the player dropped
     * and {@code as} the starvations it filled with silence; a viewer that receives no audio still
     * sees the fixed shape with {@code n/a} and zero counters.</p>
     */
    private static String audioHudLine(MetricsSnapshot snapshot) {
        StringBuilder audio = new StringBuilder(64);
        audio.append("av ");
        LatencyWindow.Stats offset = snapshot.getWindow(PipelineMetrics.Window.AV_OFFSET);
        if (offset.hasSamples()) {
            appendMillis(audio, offset.getP50());
            audio.append(" ms (p95 ");
            appendMillis(audio, offset.getP95());
            audio.append(')');
        } else {
            audio.append("n/a");
        }
        audio.append(" | af ").append(snapshot.getCounter(PipelineMetrics.Counter.AUDIO_FRAMES));
        audio.append(" | ad ").append(snapshot.getCounter(PipelineMetrics.Counter.AUDIO_DROPPED));
        audio.append(" | as ").append(snapshot.getCounter(PipelineMetrics.Counter.AUDIO_STARVED));
        return audio.toString();
    }

    /**
     * Appends server-side windows, derived interval rates and counters.
     *
     * <p>Send-session counters are not printed here: they belong to one session's scope and are
     * rendered by {@link #toSessionLogLine(MetricsSnapshot, String)}.</p>
     */
    private static void appendServerFields(StringBuilder out, MetricsSnapshot snapshot,
                                           MetricsRate rate) {
        appendRows(out, snapshot, SERVER_ROWS);
        if (rate != null && rate.hasInterval()) {
            out.append(' ').append(ServerMetricNames.ENC_AU_RATE).append('=');
            appendTenths(out, rate.getFramesPerSecond());
            out.append(' ').append(ServerMetricNames.ENC_BITRATE).append('=')
                    .append(Math.round(rate.getBitsPerSecond()));
        }
        appendCounters(out, snapshot, ServerMetricNames.TX_CLIENTS, null,
                PipelineMetrics.Gauge.PEER_COUNT);
        appendCounters(out, snapshot, ServerMetricNames.KF_REQUEST,
                PipelineMetrics.Counter.JOIN_REQUESTS, null);
        appendCounters(out, snapshot, ServerMetricNames.KF_JOINS,
                PipelineMetrics.Counter.JOIN_POINTS, null);
        appendCounters(out, snapshot, MediaMetricNames.TX_FRAGMENT,
                PipelineMetrics.Counter.FRAGMENTS_SENT, null);
    }

    /**
     * Appends client-side windows, interval rates, counters, gauges and round-trip fields.
     */
    private static void appendClientFields(StringBuilder out, MetricsSnapshot snapshot,
                                            MetricsRate rate) {
        appendRows(out, snapshot, CLIENT_ROWS);
        if (rate != null && rate.hasInterval()) {
            out.append(' ').append(ClientMetricNames.RX_AU_RATE).append('=');
            appendTenths(out, rate.getFramesPerSecond());
            out.append(' ').append(ClientMetricNames.RX_BITRATE).append('=')
                    .append(Math.round(rate.getBitsPerSecond()));
        }
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
        appendCounters(out, snapshot, CatchUpMetricNames.RX_CATCH_UP,
                PipelineMetrics.Counter.CATCH_UP_EPISODES, null);
        appendCounters(out, snapshot, CatchUpMetricNames.RX_CATCH_UP_SHED,
                PipelineMetrics.Counter.CATCH_UP_SHEDS, null);
        appendCounters(out, snapshot, CatchUpMetricNames.RX_CONVERGE_FAIL,
                PipelineMetrics.Counter.CONVERGENCE_FAILURES, null);
        appendCatchUpReason(out, snapshot);
        appendRttFields(out, snapshot);
        appendAudioFields(out, snapshot);
        appendMediaChannelFields(out, snapshot);
    }

    /**
     * Appends the datagram counters of the same receive session.
     *
     * <p>The block is printed only once a datagram arrived: a viewer on the control-plane carrier
     * has no datagram tier, and printing zeros would make its line look like a datagram channel
     * that lost nothing - the two are not comparable, which is exactly what the omission avoids.
     * The loss numbers are the ones the plan asks the weak-network tier to be judged by, so they
     * stay on the same line as the arrival and decode health they explain.</p>
     */
    private static void appendMediaChannelFields(StringBuilder out, MetricsSnapshot snapshot) {
        final long received = snapshot.getCounter(PipelineMetrics.Counter.DATAGRAMS_RECEIVED);
        if (received == 0L) {
            return;
        }
        out.append(' ').append(MediaMetricNames.UDP_RECEIVED).append('=').append(received);
        appendCounters(out, snapshot, MediaMetricNames.UDP_LOST,
                PipelineMetrics.Counter.DATAGRAMS_LOST, null);
        appendCounters(out, snapshot, MediaMetricNames.UDP_DUPLICATE,
                PipelineMetrics.Counter.DATAGRAMS_DUPLICATE, null);
        appendCounters(out, snapshot, MediaMetricNames.UDP_REORDERED,
                PipelineMetrics.Counter.DATAGRAMS_REORDERED, null);
        appendCounters(out, snapshot, MediaMetricNames.UDP_AU_INCOMPLETE,
                PipelineMetrics.Counter.ACCESS_UNITS_INCOMPLETE, null);
        appendCounters(out, snapshot, MediaMetricNames.UDP_AUTH_FAILURE,
                PipelineMetrics.Counter.MEDIA_AUTH_FAILURES, null);
    }

    /**
     * Appends the audio track counters of the same receive session.
     *
     * <p>They are the counter half of the audio block whose offset distribution
     * {@link ClientMetricNames#AV_OFFSET} prints: frames tell whether the client is decoding at all,
     * drops and starvations tell whether the playback path is keeping up, and the two drift counters
     * tell whether the guard had to move the timeline. Zeros are printed as well once the track is
     * receiving: a zero starvation count is the answer to "is the buffer holding", and it used to be
     * invisible because the formatter skipped zero values - which is exactly what made the device
     * report for this bug unreadable. A viewer that never receives audio keeps the line it had before
     * the audio track existed.</p>
     */
    private static void appendAudioFields(StringBuilder out, MetricsSnapshot snapshot) {
        final long frames = snapshot.getCounter(PipelineMetrics.Counter.AUDIO_FRAMES);
        if (frames == 0L) {
            return;
        }
        out.append(' ').append(ClientMetricNames.AUDIO_FRAMES).append('=').append(frames);
        appendZeroableCounter(out, snapshot, ClientMetricNames.AUDIO_DROPPED,
                PipelineMetrics.Counter.AUDIO_DROPPED);
        appendZeroableCounter(out, snapshot, ClientMetricNames.AUDIO_STARVED,
                PipelineMetrics.Counter.AUDIO_STARVED);
        appendZeroableCounter(out, snapshot, ClientMetricNames.AUDIO_DRIFT_INSERT,
                PipelineMetrics.Counter.AUDIO_DRIFT_INSERT);
        appendZeroableCounter(out, snapshot, ClientMetricNames.AUDIO_DRIFT_DROP,
                PipelineMetrics.Counter.AUDIO_DRIFT_DROP);
    }

    /**
     * Appends one counter field even when its value is zero.
     */
    private static void appendZeroableCounter(StringBuilder out, MetricsSnapshot snapshot,
                                              String key, PipelineMetrics.Counter counter) {
        out.append(' ').append(key).append('=').append(snapshot.getCounter(counter));
    }

    /**
     * Appends the signal that started the latest catch-up, when one happened.
     *
     * <p>The field is omitted before the first episode, so a viewer that never fell behind produces
     * the same line it did before the catch-up existed.</p>
     */
    private static void appendCatchUpReason(StringBuilder out, MetricsSnapshot snapshot) {
        long reason = snapshot.getGauge(PipelineMetrics.Gauge.CATCH_UP_REASON);
        if (reason == 0L) {
            return;
        }
        out.append(' ').append(CatchUpMetricNames.RX_CATCH_UP_REASON).append('=')
                .append(reason == PipelineMetrics.CATCH_UP_REASON_QUEUE_DEPTH
                        ? CatchUpMetricNames.REASON_QUEUE_DEPTH
                        : CatchUpMetricNames.REASON_QUEUE_WAIT);
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
