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

    private TcpMediaConstants() {
    }
}
