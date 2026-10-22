package com.obby.android.localscreenshare.server;

import com.obby.android.localscreenshare.core.net.MediaChannelOffer;
import com.obby.android.localscreenshare.core.net.MediaChannelTicket;
import com.obby.android.localscreenshare.core.net.MediaTransportKind;
import com.obby.android.localscreenshare.core.net.MediaTransportPolicy;
import com.obby.android.localscreenshare.core.support.MutableClock;
import com.obby.android.localscreenshare.transport.TcpMediaConstants;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link MediaChannelNegotiator}.
 *
 * <p>The negotiator is the stateful half of the decision: it issues the ticket, publishes the
 * offer and measures the window. All three are asserted against an injected clock, so no test
 * sleeps.
 */
public class MediaChannelNegotiatorTest {

    @Test
    public void firstKind_followsThePolicyAndTheDeclaration() {
        MediaChannelNegotiator negotiator = negotiator(MediaTransportPolicy.Mode.TCP, true,
                new MutableClock());

        assertEquals(MediaTransportKind.TCP, negotiator.firstKind(offered("tcp")));
        assertEquals(MediaTransportKind.GRPC, negotiator.firstKind(Collections.emptySet()));
        assertEquals(MediaTransportKind.GRPC, negotiator.firstKind(offered("udp")));
    }

    @Test
    public void offer_issuesATicketAndPublishesTheOffer() {
        MutableClock clock = new MutableClock(500L);
        List<MediaChannelOffer> offers = new ArrayList<>();
        MediaChannelNegotiator negotiator = negotiator(MediaTransportPolicy.Mode.TCP, true, clock,
                offers);

        assertTrue(negotiator.offer(MediaTransportKind.TCP, "device-1"));

        MediaChannelTicket ticket = negotiator.getTicket();
        assertNotNull(ticket);
        assertEquals(MediaTransportKind.TCP, negotiator.getOfferedKind());
        assertEquals(ticket.getChannelIdHex(), negotiator.getChannelIdHex());
        assertEquals(1, offers.size());
        assertEquals(MediaTransportKind.TCP, offers.get(0).getTransport());
        assertEquals(ticket.getChannelIdHex(), offers.get(0).getChannelIdHex());
        assertEquals(500L, ticket.getCreatedAtMs());
        assertEquals("10.0.0.5", offers.get(0).getHost());
        assertEquals(9100, offers.get(0).getPort());
        assertTrue(offers.get(0).isSecure());
        assertEquals(32, offers.get(0).getToken().length);
        assertTrue(offers.get(0).isEndpointPresent());
    }

    @Test
    public void offer_isSkippedWhenNoEndpointIsReachable() {
        List<MediaChannelOffer> offers = new ArrayList<>();
        MediaChannelNegotiator negotiator = new MediaChannelNegotiator(
                MediaTransportPolicy.Mode.TCP, true, offers::add, new MutableClock(),
                MediaChannelEndpoint.unavailable());

        assertFalse(negotiator.offer(MediaTransportKind.TCP, null));

        assertTrue(offers.isEmpty());
        assertNull(negotiator.getTicket());
    }

    @Test
    public void offer_windowExpiresOnTheStreamTimeout() {
        MutableClock clock = new MutableClock(1_000L);
        MediaChannelNegotiator negotiator = negotiator(MediaTransportPolicy.Mode.TCP, true, clock);
        assertTrue(negotiator.offer(MediaTransportKind.TCP, null));

        clock.advance(TcpMediaConstants.OFFER_TIMEOUT_MS - 1L);
        assertFalse(negotiator.isOfferExpired());

        clock.advance(1L);
        assertTrue(negotiator.isOfferExpired());
    }

    @Test
    public void offer_withoutAnOutstandingOffer_isNotExpired() {
        MediaChannelNegotiator negotiator = negotiator(MediaTransportPolicy.Mode.TCP, true,
                new MutableClock());

        assertFalse(negotiator.isOfferExpired());
    }

    @Test
    public void offer_ofTheCarrierSendsNothing() {
        List<MediaChannelOffer> offers = new ArrayList<>();
        MediaChannelNegotiator negotiator = negotiator(MediaTransportPolicy.Mode.AUTO, true,
                new MutableClock(), offers);

        assertFalse(negotiator.offer(MediaTransportKind.GRPC, null));

        assertTrue(offers.isEmpty());
        assertNull(negotiator.getTicket());
    }

    @Test
    public void offer_withAFailingTargetLeavesNothingOutstanding() {
        MediaChannelNegotiator negotiator = new MediaChannelNegotiator(
                MediaTransportPolicy.Mode.TCP, true, offer -> {
                    throw new IllegalStateException("transport closed");
                }, new MutableClock());

        assertFalse(negotiator.offer(MediaTransportKind.TCP, null));

        assertNull(negotiator.getTicket());
        assertNull(negotiator.getChannelIdHex());
    }

    @Test
    public void revoke_dropsTheOutstandingTicket() {
        MediaChannelNegotiator negotiator = negotiator(MediaTransportPolicy.Mode.TCP, true,
                new MutableClock());
        negotiator.offer(MediaTransportKind.TCP, null);

        negotiator.revoke();
        negotiator.revoke();

        assertNull(negotiator.getTicket());
        assertNull(negotiator.getOfferedKind());
        assertNull(negotiator.getChannelIdHex());
    }

    @Test
    public void nextKind_skipsTheFailedChannel() {
        MediaChannelNegotiator negotiator = negotiator(MediaTransportPolicy.Mode.AUTO, true,
                new MutableClock());
        Set<String> declared = offered("tcp", "udp");

        assertEquals(MediaTransportKind.UDP, negotiator.firstKind(declared));
        assertEquals(MediaTransportKind.TCP, negotiator.nextKind(declared,
                EnumSet.of(MediaTransportKind.UDP)));
        assertEquals(MediaTransportKind.GRPC, negotiator.nextKind(declared,
                EnumSet.of(MediaTransportKind.UDP, MediaTransportKind.TCP)));
    }

    @Test
    public void autoMode_prefersTheDedicatedStreamOverTheCarrier() {
        MediaChannelNegotiator negotiator = negotiator(MediaTransportPolicy.Mode.AUTO, false,
                new MutableClock());

        assertEquals(MediaTransportKind.TCP, negotiator.firstKind(offered("tcp", "udp")));
        assertEquals(MediaTransportKind.GRPC, negotiator.firstKind(offered("udp")));
    }

    private static MediaChannelNegotiator negotiator(MediaTransportPolicy.Mode mode,
                                                     boolean udpEnabled, MutableClock clock) {
        return negotiator(mode, udpEnabled, clock, new ArrayList<>());
    }

    private static MediaChannelNegotiator negotiator(MediaTransportPolicy.Mode mode,
                                                     boolean udpEnabled, MutableClock clock,
                                                     List<MediaChannelOffer> offers) {
        return new MediaChannelNegotiator(mode, udpEnabled, offers::add, clock, endpoint());
    }

    private static MediaChannelEndpoint endpoint() {
        return new MediaChannelEndpoint("10.0.0.5", 9100, true);
    }

    private static Set<String> offered(String... names) {
        return new LinkedHashSet<>(Arrays.asList(names));
    }
}
