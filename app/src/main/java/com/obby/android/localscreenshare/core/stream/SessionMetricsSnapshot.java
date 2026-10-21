package com.obby.android.localscreenshare.core.stream;

import com.obby.android.localscreenshare.core.metrics.MetricsSnapshot;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;

/**
 * Immutable snapshot of one send session's metric scope.
 *
 * <p>Every {@code ClientSendSession} records its send duration, queue residency and queue depth
 * into a scope of its own, so those samples never appear in the process-wide snapshot. This value
 * pairs such a scope's snapshot with the session identity, which is what lets the server print one
 * send-stage line per viewer while reading, never registering, the per-session scope on the probe
 * path.</p>
 *
 * <p>Instances are produced on the aggregation thread and are safe to pass across threads.</p>
 */
public final class SessionMetricsSnapshot {

    private final ClientSessionKey key;

    private final MetricsSnapshot metrics;

    /**
     * Creates a snapshot.
     *
     * @param key     client and stream identity; required
     * @param metrics snapshot of that session's metric scope; required
     * @throws IllegalArgumentException if any argument is null
     */
    public SessionMetricsSnapshot(ClientSessionKey key, MetricsSnapshot metrics) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (metrics == null) {
            throw new IllegalArgumentException("metrics must not be null");
        }
        this.key = key;
        this.metrics = metrics;
    }

    /**
     * Returns the client and stream identity of the session.
     */
    public ClientSessionKey getKey() {
        return key;
    }

    /**
     * Returns the client id part of the session key.
     */
    public String getClientId() {
        return key.getClientId();
    }

    /**
     * Returns the client-id tail used to correlate this line with the viewer's own metric line.
     */
    public String getClientIdTail() {
        return key.getClientIdTail();
    }

    /**
     * Returns the snapshot of this session's metric scope.
     */
    public MetricsSnapshot getMetrics() {
        return metrics;
    }

    @Override
    public String toString() {
        return "SessionMetricsSnapshot{key=" + key + ", metrics=" + metrics + '}';
    }
}
