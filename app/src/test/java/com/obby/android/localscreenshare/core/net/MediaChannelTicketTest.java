package com.obby.android.localscreenshare.core.net;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link MediaChannelTicket}.
 *
 * <p>The ticket is the only thing that binds a channel socket to the call that offered it, so its
 * two promises are tested directly: the credential works exactly once, and it stops working when
 * the offer window elapses.
 */
public class MediaChannelTicketTest {

    private static final long NOW_MS = 1_000L;

    @Test
    public void consume_acceptsTheIssuedTokenOnce() {
        MediaChannelTicket ticket = ticket();

        assertTrue(ticket.consume(ticket.getToken(), NOW_MS));
        assertTrue(ticket.isConsumed());
        assertFalse(ticket.consume(ticket.getToken(), NOW_MS));
    }

    @Test
    public void consume_rejectsAWrongTokenWithoutConsumingTheTicket() {
        MediaChannelTicket ticket = ticket();

        assertFalse(ticket.consume(bytes(MediaChannelTicket.TOKEN_BYTES, 0x7f), NOW_MS));

        assertFalse(ticket.isConsumed());
        assertTrue(ticket.consume(ticket.getToken(), NOW_MS));
    }

    @Test
    public void consume_rejectsATokenOfTheWrongLength() {
        MediaChannelTicket ticket = ticket();

        assertFalse(ticket.consume(new byte[0], NOW_MS));
        assertFalse(ticket.consume(null, NOW_MS));
        assertFalse(ticket.consume(new byte[MediaChannelTicket.TOKEN_BYTES - 1], NOW_MS));
        assertTrue(ticket.consume(ticket.getToken(), NOW_MS));
    }

    @Test
    public void consume_rejectsAfterTheTtl() {
        MediaChannelTicket ticket = ticket();

        assertFalse(ticket.consume(ticket.getToken(), NOW_MS + MediaChannelTicket.DEFAULT_TTL_MS));

        assertFalse(ticket.isConsumed());
    }

    @Test
    public void isExpired_startsExactlyAtTheTtl() {
        MediaChannelTicket ticket = ticket();

        assertFalse(ticket.isExpired(NOW_MS));
        assertFalse(ticket.isExpired(NOW_MS + MediaChannelTicket.DEFAULT_TTL_MS - 1L));
        assertTrue(ticket.isExpired(NOW_MS + MediaChannelTicket.DEFAULT_TTL_MS));
        assertFalse(ticket.consume(ticket.getToken(), NOW_MS + MediaChannelTicket.DEFAULT_TTL_MS));
    }

    @Test
    public void getters_copyTheCredentials() {
        MediaChannelTicket ticket = ticket();

        byte[] channelId = ticket.getChannelId();
        byte[] token = ticket.getToken();
        channelId[0] = 0;
        token[0] = 0;

        assertArrayEquals(bytes(MediaChannelTicket.CHANNEL_ID_BYTES, 0x11), ticket.getChannelId());
        assertTrue(ticket.consume(bytes(MediaChannelTicket.TOKEN_BYTES, 0x22), NOW_MS));
        assertTrue(ticket.getChannelIdHex().matches("[0-9a-f]{32}"));
    }

    @Test
    public void issue_generatesDistinctIdsAndTokens() {
        MediaChannelTicket first = MediaChannelTicket.issue(MediaTransportKind.TCP, "device-1",
                NOW_MS);
        MediaChannelTicket second = MediaChannelTicket.issue(MediaTransportKind.TCP, "device-1",
                NOW_MS);

        assertNotEquals(first.getChannelIdHex(), second.getChannelIdHex());
        assertFalse(Arrays.equals(first.getToken(), second.getToken()));
        assertEquals("device-1", first.getPeerDeviceId());
        assertEquals(MediaTransportKind.TCP, first.getTransport());
    }

    @Test
    public void constructor_rejectsWrongLengthsAndMissingTransport() {
        assertThrows(IllegalArgumentException.class,
                () -> new MediaChannelTicket(new byte[3], bytes(32, 1), MediaTransportKind.TCP,
                        null, NOW_MS, 10L));
        assertThrows(IllegalArgumentException.class,
                () -> new MediaChannelTicket(bytes(16, 1), new byte[3], MediaTransportKind.TCP,
                        null, NOW_MS, 10L));
        assertThrows(IllegalArgumentException.class,
                () -> new MediaChannelTicket(bytes(16, 1), bytes(32, 1), null, null, NOW_MS, 10L));
        assertThrows(IllegalArgumentException.class,
                () -> new MediaChannelTicket(bytes(16, 1), bytes(32, 1), MediaTransportKind.TCP,
                        null, NOW_MS, -1L));
    }

    private static MediaChannelTicket ticket() {
        return new MediaChannelTicket(bytes(MediaChannelTicket.CHANNEL_ID_BYTES, 0x11),
                bytes(MediaChannelTicket.TOKEN_BYTES, 0x22), MediaTransportKind.TCP, "device-1",
                NOW_MS, MediaChannelTicket.DEFAULT_TTL_MS);
    }

    private static byte[] bytes(int length, int value) {
        byte[] result = new byte[length];
        Arrays.fill(result, (byte) value);
        return result;
    }
}
