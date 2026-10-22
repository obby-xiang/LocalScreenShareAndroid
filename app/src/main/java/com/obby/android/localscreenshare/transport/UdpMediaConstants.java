package com.obby.android.localscreenshare.transport;

/**
 * Parameters of the datagram media channel.
 *
 * <p>Only the negotiation parameters live here so far; the datagram layout and its bounds arrive
 * with the channel itself, and they belong to this same domain so that no cross-domain constant
 * class has to grow.</p>
 */
public final class UdpMediaConstants {

    /**
     * How long the server waits for the client's answer before it offers the next candidate.
     *
     * <p>Shorter than the stream channel's window on purpose: binding datagrams fails fast when the
     * peer is not reachable, and a slow answer costs a visible pause in the negotiation.</p>
     */
    public static final long OFFER_TIMEOUT_MS = 1_000L;

    /**
     * How long the client waits for the first datagram of a bound channel, in milliseconds.
     *
     * <p>A datagram channel has no acknowledgement of its own: the first datagram that arrives is
     * the proof that the bind reached the server, so a silent socket means the bind was lost and
     * the call falls back.</p>
     */
    public static final long BIND_TIMEOUT_MS = 300L;

    /**
     * Datagram size the client expects when the offer names none, in bytes.
     */
    public static final int DEFAULT_MTU = 1400;

    /**
     * Headroom of the receive buffer beyond the offered datagram size, in bytes.
     */
    public static final int RECEIVE_SLACK_BYTES = 512;

    private UdpMediaConstants() {
    }
}
