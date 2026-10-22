package com.obby.android.localscreenshare.core.stream;

import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.model.ClientState;
import com.obby.android.localscreenshare.core.net.MediaTransportKind;

/**
 * Immutable snapshot of one attached client send session.
 *
 * <p>Combines the session's counters ({@link ClientSendStats}) with its current state, the
 * measured 95th percentile send duration and the media channel its frames leave on, so a caller can
 * render one row per viewer without touching the live session. Instances are produced under the
 * session's own lock and are safe to pass across threads.</p>
 */
public final class SessionSnapshot {

    private final ClientSessionKey key;
    private final ClientState state;
    private final ClientSendStats stats;
    private final long sendP95Ns;
    private final MediaTransportKind transport;

    /**
     * Creates a snapshot of a session that sends over the control-plane carrier.
     *
     * @param key       client and stream identity; required
     * @param state     current session state; required
     * @param stats     session counters; required
     * @param sendP95Ns 95th percentile of one transport send call in nanoseconds, zero when the
     *                  session was not measured
     * @throws IllegalArgumentException if any argument is null
     */
    public SessionSnapshot(ClientSessionKey key, ClientState state, ClientSendStats stats,
                           long sendP95Ns) {
        this(key, state, stats, sendP95Ns, MediaTransportKind.GRPC);
    }

    /**
     * Creates a snapshot that reports the media channel its frames leave on.
     *
     * @param key       client and stream identity; required
     * @param state     current session state; required
     * @param stats     session counters; required
     * @param sendP95Ns 95th percentile of one transport send call in nanoseconds, zero when the
     *                  session was not measured
     * @param transport media channel of the session; required
     * @throws IllegalArgumentException if any argument is null
     */
    public SessionSnapshot(ClientSessionKey key, ClientState state, ClientSendStats stats,
                           long sendP95Ns, MediaTransportKind transport) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        if (stats == null) {
            throw new IllegalArgumentException("stats must not be null");
        }
        if (transport == null) {
            throw new IllegalArgumentException("transport must not be null");
        }
        this.key = key;
        this.state = state;
        this.stats = stats;
        this.sendP95Ns = sendP95Ns;
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
     * Returns the session state at snapshot time.
     */
    public ClientState getState() {
        return state;
    }

    /**
     * Returns the session counters at snapshot time.
     */
    public ClientSendStats getStats() {
        return stats;
    }

    /**
     * Returns the measured 95th percentile of one transport send call, in nanoseconds; zero when
     * the session was not measured.
     */
    public long getSendP95Ns() {
        return sendP95Ns;
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
        return "SessionSnapshot{clientId=" + getClientId() + ", state=" + state
                + ", queuedUnits=" + stats.getQueuedUnits() + ", droppedUnits="
                + stats.getDroppedUnits() + ", resyncCount=" + stats.getResyncCount()
                + ", sendP95Ns=" + sendP95Ns + ", channel=" + getChannel() + '}';
    }
}
