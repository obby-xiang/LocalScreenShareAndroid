package com.obby.android.localscreenshare.transport;

/**
 * Parameters of the dedicated stream media channel.
 *
 * <p>Only the negotiation parameters live here so far; the frame format and its bounds arrive with
 * the channel itself, and they belong to this same domain so that no cross-domain constant class
 * has to grow.</p>
 */
public final class TcpMediaConstants {

    /**
     * How long the server waits for the client's answer before it offers the next candidate.
     */
    public static final long OFFER_TIMEOUT_MS = 2_000L;

    /**
     * How long a channel socket may take to connect before the client gives up on it.
     */
    public static final long CONNECT_TIMEOUT_MS = 2_000L;

    /**
     * How long the greeting exchange of a new channel may take, on both ends.
     */
    public static final long HANDSHAKE_TIMEOUT_MS = 3_000L;

    /**
     * How long a frame write may stay incomplete before the channel counts as failed.
     */
    public static final long WRITE_STALL_MS = 5_000L;

    /**
     * Cadence of the write-stall watchdog.
     */
    public static final long WATCHDOG_INTERVAL_MS = 1_000L;

    private TcpMediaConstants() {
    }
}
