package com.obby.android.localscreenshare.core.metrics;

/**
 * Metric keys of the viewer's decode-queue catch-up.
 *
 * <p>The keys answer three questions a lagging viewer raises: how often the client gave up its
 * backlog ({@code rx.catchup}), how much it had to discard to do so ({@code rx.catchup.shed}), and
 * whether the recovery actually converged ({@code rx.catchup.lat}, {@code rx.converge.fail}).
 * They are kept in their own holder because the client pipeline holder is already at the size limit
 * of the naming conventions, and because these keys only appear when a catch-up happened.</p>
 *
 * <p>{@code rx.catchup.shed} is counted separately from {@code rx.shed}: {@code rx.shed} means "the
 * queue was full and the newest message was rejected", while a catch-up discards what is already
 * queued on purpose. A log capture that conflates the two cannot tell congestion from a deliberate
 * skip.</p>
 *
 * <p>The counters cover three receive paths since {@code CatchUpSampler} became the single entry
 * point: the gRPC stream, the dedicated media channel and a lost whole access unit on a datagram
 * channel. The last one reports {@code rx.catchup.why=ext} and never spends the consecutive
 * budget, so {@code rx.catchup} from this build cannot be compared directly with older builds.</p>
 */
public final class CatchUpMetricNames {

    /**
     * Catch-up episodes started by the viewer.
     */
    public static final String RX_CATCH_UP = "rx.catchup";

    /**
     * Messages discarded because a catch-up superseded them before they were decoded.
     */
    public static final String RX_CATCH_UP_SHED = "rx.catchup.shed";

    /**
     * Catch-up start to the accepted join point that ended it; the formatter appends
     * {@code .p50} and {@code .p95}.
     */
    public static final String RX_CATCH_UP_LATENCY = "rx.catchup.lat";

    /**
     * Signal that started the latest catch-up, printed as a symbol from this holder.
     */
    public static final String RX_CATCH_UP_REASON = "rx.catchup.why";

    /**
     * Catch-up attempts aborted because the stream did not converge.
     */
    public static final String RX_CONVERGE_FAIL = "rx.converge.fail";

    /**
     * The latest catch-up was started because an access unit waited too long before decoding.
     */
    public static final String REASON_QUEUE_WAIT = "qWait";

    /**
     * The latest catch-up was started because the decode queue reached its backlog depth.
     */
    public static final String REASON_QUEUE_DEPTH = "qDepth";

    /**
     * The latest catch-up was opened by a lost whole access unit instead of decode backlog.
     */
    public static final String REASON_EXTERNAL = "ext";

    private CatchUpMetricNames() {
    }
}
