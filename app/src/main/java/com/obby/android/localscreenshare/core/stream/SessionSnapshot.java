package com.obby.android.localscreenshare.core.stream;

import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.model.ClientState;

/**
 * Immutable snapshot of one attached client send session.
 *
 * <p>Combines the session's counters ({@link ClientSendStats}) with its current state and the
 * measured 95th percentile send duration, so a caller can render one row per viewer without
 * touching the live session. Instances are produced under the session's own lock and are safe to
 * pass across threads.</p>
 */
public final class SessionSnapshot {

    private final ClientSessionKey key;
    private final ClientState state;
    private final ClientSendStats stats;
    private final long sendP95Ns;

    /**
     * Creates a snapshot.
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
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        if (stats == null) {
            throw new IllegalArgumentException("stats must not be null");
        }
        this.key = key;
        this.state = state;
        this.stats = stats;
        this.sendP95Ns = sendP95Ns;
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

    @Override
    public String toString() {
        return "SessionSnapshot{clientId=" + getClientId() + ", state=" + state
                + ", queuedUnits=" + stats.getQueuedUnits() + ", droppedUnits="
                + stats.getDroppedUnits() + ", resyncCount=" + stats.getResyncCount()
                + ", sendP95Ns=" + sendP95Ns + '}';
    }
}
