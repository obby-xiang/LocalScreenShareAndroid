package com.obby.android.localscreenshare.core.metrics;

/**
 * Metric keys of the viewer's control-plane backlog reports.
 *
 * <p>The pair answers "did the viewer report at all": {@code fb.sent} counts reports that reached
 * the transport, {@code fb.skip} counts reports dropped because the stream was not connected or the
 * client had stopped. They are kept out of the server-side holder because they never appear on a
 * server line, and out of {@link ClientMetricNames} because that class is already at its naming
 * capacity.</p>
 *
 * <p>Both counters are cumulative for the lifetime of the scope and are omitted while zero, so a
 * viewer whose backlog never crossed a threshold adds no fields to its line.</p>
 */
public final class FeedbackMetricNames {

    /**
     * Backlog reports sent on the control plane.
     */
    public static final String FB_SENT = "fb.sent";

    /**
     * Backlog reports dropped because the viewer was not connected or had stopped.
     */
    public static final String FB_SKIP = "fb.skip";

    private FeedbackMetricNames() {
    }
}
