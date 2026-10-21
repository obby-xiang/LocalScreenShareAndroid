package com.obby.android.localscreenshare.core.metrics;

/**
 * Metric keys for the application-level round-trip measurement.
 *
 * <p>The round trip is the only metric that needs a protocol message: gRPC does not expose the
 * HTTP/2 ping time to the application. {@link #KIND} states where a sample came from, because the
 * fallback source measures a strict upper bound instead of a path round trip.</p>
 */
public final class RttMetricNames {

    /**
     * Base key of the round-trip distribution; the formatter appends {@code .p50} and
     * {@code .p95}.
     */
    public static final String RTT = "rtt";

    /**
     * Exponentially weighted moving average of the round-trip samples.
     */
    public static final String RTT_EWMA = "rtt.ewma";

    /**
     * Round-trip samples that timed out.
     */
    public static final String RTT_LOST = "rtt.lost";

    /**
     * Source of the round-trip samples.
     */
    public static final String RTT_KIND = "rtt.kind";

    /**
     * Samples came from a echoed ping message.
     */
    public static final String KIND_PING_PONG = "ping_pong";

    /**
     * Samples came from a key-frame request to join point round trip, which includes encoding
     * latency and is therefore an upper bound.
     */
    public static final String KIND_KEYFRAME_UPPER = "keyframe_upper";

    private RttMetricNames() {
    }
}
