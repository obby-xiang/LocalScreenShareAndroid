package com.obby.android.localscreenshare.core.stream;

import com.obby.android.localscreenshare.core.metrics.MetricsSnapshot;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.net.MediaTransportKind;

/**
 * Immutable snapshot of one send session's metric scope.
 *
 * <p>Every {@code ClientSendSession} records its send duration, queue residency and queue depth
 * into a scope of its own, so those samples never appear in the process-wide snapshot. This value
 * pairs such a scope's snapshot with the session identity and its media channel, which is what lets
 * the server print one send-stage line per viewer, tagged with the channel it is talking over,
 * while reading - never registering - the per-session scope on the probe path.</p>
 *
 * <p>Instances are produced on the aggregation thread and are safe to pass across threads.</p>
 */
public final class SessionMetricsSnapshot {

    private final ClientSessionKey key;

    private final MetricsSnapshot metrics;

    private final MediaTransportKind transport;

    /**
     * Creates a snapshot of a session that sends over the control-plane carrier.
     *
     * @param key     client and stream identity; required
     * @param metrics snapshot of that session's metric scope; required
     * @throws IllegalArgumentException if any argument is null
     */
    public SessionMetricsSnapshot(ClientSessionKey key, MetricsSnapshot metrics) {
        this(key, metrics, MediaTransportKind.GRPC);
    }

    /**
     * Creates a snapshot that also reports the media channel of the session.
     *
     * @param key       client and stream identity; required
     * @param metrics   snapshot of that session's metric scope; required
     * @param transport media channel of the session; required
     * @throws IllegalArgumentException if any argument is null
     */
    public SessionMetricsSnapshot(ClientSessionKey key, MetricsSnapshot metrics,
                                  MediaTransportKind transport) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (metrics == null) {
            throw new IllegalArgumentException("metrics must not be null");
        }
        if (transport == null) {
            throw new IllegalArgumentException("transport must not be null");
        }
        this.key = key;
        this.metrics = metrics;
        this.transport = transport;
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

    /**
     * Returns the media channel this session sends over.
     */
    public MediaTransportKind getTransport() {
        return transport;
    }

    /**
     * Returns the log label of the media channel, for example {@code grpc}.
     */
    public String getChannel() {
        return transport.getLabel();
    }

    @Override
    public String toString() {
        return "SessionMetricsSnapshot{key=" + key + ", channel=" + getChannel()
                + ", metrics=" + metrics + '}';
    }
}
