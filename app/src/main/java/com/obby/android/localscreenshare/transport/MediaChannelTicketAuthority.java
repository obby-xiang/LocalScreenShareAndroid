package com.obby.android.localscreenshare.transport;

import com.obby.android.localscreenshare.core.net.MediaChannelTicket;
import com.obby.android.localscreenshare.core.stream.MediaTransport;

/**
 * Lookup the media channel server uses to bind a new socket to the call that offered it.
 *
 * <p>The server never issues a channel itself: a call offers one, and the ticket of that offer is
 * the only thing that can authenticate the socket. Keeping the lookup behind this interface lets
 * the socket code stay in {@code transport} while the registry of outstanding offers lives with
 * the calls in {@code server}.</p>
 */
public interface MediaChannelTicketAuthority {

    /**
     * Returns the outstanding ticket of a channel.
     *
     * @param channelIdHex channel id in lowercase hexadecimal; may be null
     * @return the ticket, or null when the id is unknown, expired or already bound
     */
    MediaChannelTicket ticketFor(String channelIdHex);

    /**
     * Hands an established channel to the call that offered it.
     *
     * <p>Called once per bound socket, on the thread that completed the handshake. A channel whose
     * id is no longer outstanding is dropped instead: the call moved on while the socket was
     * connecting.</p>
     *
     * @param channelIdHex channel id in lowercase hexadecimal; never null
     * @param transport    established channel; never null
     */
    void onChannelEstablished(String channelIdHex, MediaTransport transport);
}
