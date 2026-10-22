package com.obby.android.localscreenshare.transport;

import com.obby.android.localscreenshare.core.net.MediaChannelOffer;
import com.obby.android.localscreenshare.core.net.MediaTransportKind;
import com.obby.android.localscreenshare.grpc.screenstream.MediaChannelEvent;
import com.obby.android.localscreenshare.grpc.screenstream.MediaChannelEventType;
import com.obby.android.localscreenshare.grpc.screenstream.MediaOffer;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link MediaChannelOfferMapper}.
 *
 * <p>The offer is the only description of a channel that crosses the wire, so a field that does not
 * survive the round trip is a field the client cannot use.
 */
public class MediaChannelOfferMapperTest {

    private static final byte[] CHANNEL_ID = bytes(16, 0x11);

    @Test
    public void roundTrip_keepsEveryField() {
        MediaChannelOffer offer = MediaChannelOffer.builder(CHANNEL_ID, MediaTransportKind.UDP)
                .host("10.0.0.5")
                .port(9100)
                .secure(true)
                .token(bytes(32, 0x22))
                .keyS2c(bytes(32, 0x33))
                .keyC2s(bytes(32, 0x44))
                .nonceSalt(bytes(4, 0x55))
                .mtu(1400)
                .holdMs(10)
                .build();

        MediaChannelOffer decoded = MediaChannelOfferMapper.toOffer(
                MediaChannelOfferMapper.toProtoOffer(offer));

        assertArrayEquals(CHANNEL_ID, decoded.getChannelId());
        assertEquals(MediaTransportKind.UDP, decoded.getTransport());
        assertEquals("10.0.0.5", decoded.getHost());
        assertEquals(9100, decoded.getPort());
        assertTrue(decoded.isSecure());
        assertArrayEquals(bytes(32, 0x22), decoded.getToken());
        assertArrayEquals(bytes(32, 0x33), decoded.getKeyS2c());
        assertArrayEquals(bytes(32, 0x44), decoded.getKeyC2s());
        assertArrayEquals(bytes(4, 0x55), decoded.getNonceSalt());
        assertEquals(1400, decoded.getMtu());
        assertEquals(10, decoded.getHoldMs());
        assertTrue(decoded.isEndpointPresent());
        assertEquals(offer.getChannelIdHex(), decoded.getChannelIdHex());
    }

    @Test
    public void offerWithoutAnEndpoint_isNotReachable() {
        MediaChannelOffer offer = MediaChannelOffer.builder(CHANNEL_ID, MediaTransportKind.TCP)
                .build();

        MediaChannelOffer decoded = MediaChannelOfferMapper.toOffer(
                MediaChannelOfferMapper.toProtoOffer(offer));

        assertFalse(decoded.isEndpointPresent());
        assertEquals("", decoded.getHost());
        assertEquals(0, decoded.getPort());
        assertEquals(0, decoded.getToken().length);
    }

    @Test
    public void unknownTransport_mapsToNullInsteadOfThrowing() {
        MediaOffer proto = MediaOffer.newBuilder()
                .setChannelId(com.google.protobuf.ByteString.copyFrom(CHANNEL_ID))
                .setTransport("quic")
                .build();

        assertNull(MediaChannelOfferMapper.toOffer(proto));
    }

    @Test
    public void missingChannelId_mapsToNull() {
        assertNull(MediaChannelOfferMapper.toOffer(null));
        assertNull(MediaChannelOfferMapper.toOffer(MediaOffer.getDefaultInstance()));
    }

    @Test
    public void event_carriesTheChannelTypeAndReason() {
        MediaChannelEvent event = MediaChannelOfferMapper.toProtoEvent(CHANNEL_ID,
                MediaChannelEventType.FAILED, "not_implemented");

        assertArrayEquals(CHANNEL_ID, event.getChannelId().toByteArray());
        assertEquals(MediaChannelEventType.FAILED, event.getType());
        assertEquals("not_implemented", event.getReason());
        assertEquals("", MediaChannelOfferMapper.toProtoEvent(CHANNEL_ID,
                MediaChannelEventType.CLOSED, null).getReason());
    }

    @Test
    public void matchesChannel_comparesTheWholeId() {
        MediaChannelEvent event = MediaChannelOfferMapper.toProtoEvent(CHANNEL_ID,
                MediaChannelEventType.FAILED, "x");

        assertTrue(MediaChannelOfferMapper.matchesChannel(event, hexOf(CHANNEL_ID)));
        assertFalse(MediaChannelOfferMapper.matchesChannel(event, hexOf(bytes(16, 0x12))));
        assertFalse(MediaChannelOfferMapper.matchesChannel(event, null));
        assertFalse(MediaChannelOfferMapper.matchesChannel(null, hexOf(CHANNEL_ID)));
    }

    private static byte[] bytes(int length, int value) {
        byte[] result = new byte[length];
        Arrays.fill(result, (byte) value);
        return result;
    }

    private static String hexOf(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(Character.forDigit((value >> 4) & 0x0f, 16));
            out.append(Character.forDigit(value & 0x0f, 16));
        }
        return out.toString();
    }
}
