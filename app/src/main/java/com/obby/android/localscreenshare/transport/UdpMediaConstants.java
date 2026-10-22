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

    private UdpMediaConstants() {
    }
}
