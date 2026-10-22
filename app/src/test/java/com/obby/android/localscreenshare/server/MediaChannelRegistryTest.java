package com.obby.android.localscreenshare.server;

import com.obby.android.localscreenshare.core.net.MediaChannelTicket;
import com.obby.android.localscreenshare.core.net.MediaTransportKind;
import com.obby.android.localscreenshare.core.stream.MediaTransport;
import com.obby.android.localscreenshare.core.support.FakeTransport;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link MediaChannelRegistry}.
 *
 * <p>The registry is the only place that maps a channel id to the call waiting for it, so its
 * promises are tested directly: a ticket is found while outstanding, forgotten when revoked, and
 * handed over exactly once.</p>
 */
public class MediaChannelRegistryTest {

    @Test
    public void registeredTicket_isFoundByItsChannelId() {
        MediaChannelRegistry registry = new MediaChannelRegistry();
        MediaChannelTicket ticket = ticket(0x11);

        registry.register(ticket, transport -> {
        });

        assertEquals(1, registry.size());
        assertSame(ticket, registry.ticketFor(ticket.getChannelIdHex()));
        assertNull(registry.ticketFor("unknown"));
        assertNull(registry.ticketFor(null));
    }

    @Test
    public void revoke_forgetsTheOffer() {
        MediaChannelRegistry registry = new MediaChannelRegistry();
        MediaChannelTicket ticket = ticket(0x11);
        registry.register(ticket, transport -> {
        });

        registry.revoke(ticket.getChannelIdHex());
        registry.revoke(ticket.getChannelIdHex());
        registry.revoke(null);

        assertEquals(0, registry.size());
        assertNull(registry.ticketFor(ticket.getChannelIdHex()));
    }

    @Test
    public void establishedChannel_isHandedOverOnceAndRemoved() {
        MediaChannelRegistry registry = new MediaChannelRegistry();
        MediaChannelTicket ticket = ticket(0x11);
        List<MediaTransport> delivered = new ArrayList<>();
        registry.register(ticket, delivered::add);
        FakeTransport channel = new FakeTransport();

        registry.onChannelEstablished(ticket.getChannelIdHex(), channel);
        registry.onChannelEstablished(ticket.getChannelIdHex(), new FakeTransport());

        assertEquals(1, delivered.size());
        assertSame(channel, delivered.get(0));
        assertEquals(0, registry.size());
    }

    @Test
    public void establishedChannelWithoutAnOffer_isClosed() {
        MediaChannelRegistry registry = new MediaChannelRegistry();
        FakeTransport channel = new FakeTransport();

        registry.onChannelEstablished("unknown", channel);

        assertTrue(channel.isClosed());
        assertNotNull(registry);
    }

    @Test
    public void datagramKeys_travelWithTheOfferAndAreForgottenWithIt() {
        MediaChannelRegistry registry = new MediaChannelRegistry();
        MediaChannelTicket ticket = ticket(0x11);
        com.obby.android.localscreenshare.core.net.DatagramKeys keys =
                com.obby.android.localscreenshare.core.net.DatagramKeys.generate(
                        new java.security.SecureRandom());
        registry.register(ticket, keys, transport -> {
        });

        assertSame(keys, registry.udpKeysFor(ticket.getChannelIdHex()));
        assertNull(registry.udpKeysFor("unknown"));

        registry.revoke(ticket.getChannelIdHex());

        assertNull(registry.udpKeysFor(ticket.getChannelIdHex()));
    }

    @Test
    public void streamOffer_hasNoDatagramKeys() {
        MediaChannelRegistry registry = new MediaChannelRegistry();
        MediaChannelTicket ticket = ticket(0x11);
        registry.register(ticket, transport -> {
        });

        assertNull(registry.udpKeysFor(ticket.getChannelIdHex()));
    }

    @Test
    public void register_rejectsMissingArguments() {
        MediaChannelRegistry registry = new MediaChannelRegistry();

        try {
            registry.register(null, transport -> {
            });
            throw new AssertionError("expected an IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            registry.register(ticket(0x11), null);
            throw new AssertionError("expected an IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static MediaChannelTicket ticket(int seed) {
        byte[] channelId = new byte[MediaChannelTicket.CHANNEL_ID_BYTES];
        Arrays.fill(channelId, (byte) seed);
        byte[] token = new byte[MediaChannelTicket.TOKEN_BYTES];
        Arrays.fill(token, (byte) seed);
        return new MediaChannelTicket(channelId, token, MediaTransportKind.TCP, "device-1", 0L,
                MediaChannelTicket.DEFAULT_TTL_MS);
    }
}
