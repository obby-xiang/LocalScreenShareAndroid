package com.obby.android.localscreenshare.server;

import com.obby.android.localscreenshare.core.net.MediaChannelTicket;
import com.obby.android.localscreenshare.transport.MediaChannelTicketAuthority;
import com.obby.android.localscreenshare.core.stream.MediaTransport;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Outstanding channel offers of one sharing session, indexed by channel id.
 *
 * <p>The registry is the only bridge between an offer and the socket that answers it: the server
 * listener asks for the ticket of a channel id, consumes the token, and hands the established
 * channel back through the same entry. An entry is removed before the channel is delivered, so a
 * repeated socket with a replayed token finds nothing to bind.</p>
 *
 * <p>Thread safe: offers are registered from the drain and control threads, and sockets arrive on
 * the listener's threads.</p>
 */
public final class MediaChannelRegistry implements MediaChannelTicketAuthority {

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /**
     * Registers the ticket of one offer.
     *
     * <p>Registering the same channel id again replaces the previous entry, which is what an offer
     * retry does.</p>
     *
     * @param ticket ticket issued with the offer; required
     * @param target receiver of the established channel; required
     * @throws IllegalArgumentException if an argument is null
     */
    public void register(MediaChannelTicket ticket, MediaChannelReadyTarget target) {
        if (ticket == null) {
            throw new IllegalArgumentException("ticket must not be null");
        }
        if (target == null) {
            throw new IllegalArgumentException("target must not be null");
        }
        entries.put(ticket.getChannelIdHex(), new Entry(ticket, target));
    }

    /**
     * Forgets an outstanding offer, so its token can no longer be consumed; idempotent.
     *
     * @param channelIdHex channel id in lowercase hexadecimal; may be null
     */
    public void revoke(String channelIdHex) {
        if (channelIdHex != null) {
            entries.remove(channelIdHex);
        }
    }

    /**
     * Returns the number of outstanding offers.
     */
    public int size() {
        return entries.size();
    }

    @Override
    public MediaChannelTicket ticketFor(String channelIdHex) {
        if (channelIdHex == null) {
            return null;
        }
        Entry entry = entries.get(channelIdHex);
        return entry == null ? null : entry.ticket;
    }

    @Override
    public void onChannelEstablished(String channelIdHex, MediaTransport transport) {
        Entry entry = channelIdHex == null ? null : entries.remove(channelIdHex);
        if (entry == null) {
            // The call gave up on this channel while the socket was connecting.
            transport.close();
            return;
        }
        entry.target.onMediaChannelReady(transport);
    }

    /**
     * One outstanding offer together with where its channel goes.
     */
    private static final class Entry {

        private final MediaChannelTicket ticket;

        private final MediaChannelReadyTarget target;

        private Entry(MediaChannelTicket ticket, MediaChannelReadyTarget target) {
            this.ticket = ticket;
            this.target = target;
        }
    }
}
