package com.obby.android.localscreenshare.transport;

import com.obby.android.localscreenshare.core.net.MediaChannelOffer;
import com.obby.android.localscreenshare.core.net.MediaTransportKind;
import com.obby.android.localscreenshare.grpc.screenstream.MediaChannelEvent;
import com.obby.android.localscreenshare.grpc.screenstream.MediaChannelEventType;
import com.obby.android.localscreenshare.grpc.screenstream.MediaOffer;

/**
 * Stateless mapper between the negotiation model and the wire protocol.
 *
 * <p>Negotiation is a pure exchange of values: the server offers a channel, the client answers with
 * an event, and the control stream carries both. Keeping the mapping here means the policy, the
 * ticket and the client state machine never import a generated protocol class, and the wire names
 * of the transports appear in exactly one place.</p>
 *
 * <p>A wire offer whose transport name this build does not implement maps to null instead of
 * throwing: the receiver then answers a failure, which is what a peer needs to hear, rather than
 * failing the whole call over an unsupported field.</p>
 */
public final class MediaChannelOfferMapper {

    private MediaChannelOfferMapper() {
    }

    /**
     * Maps an offer to its wire form.
     *
     * @param offer offer to map; must not be null
     * @return the wire message
     * @throws IllegalArgumentException if {@code offer} is null
     */
    public static MediaOffer toProtoOffer(MediaChannelOffer offer) {
        if (offer == null) {
            throw new IllegalArgumentException("offer must not be null");
        }
        return MediaOffer.newBuilder()
                .setChannelId(com.google.protobuf.ByteString.copyFrom(offer.getChannelId()))
                .setTransport(offer.getTransport().getLabel())
                .setHost(offer.getHost())
                .setPort(offer.getPort())
                .setSecure(offer.isSecure())
                .setToken(com.google.protobuf.ByteString.copyFrom(offer.getToken()))
                .setKeyS2C(com.google.protobuf.ByteString.copyFrom(offer.getKeyS2c()))
                .setKeyC2S(com.google.protobuf.ByteString.copyFrom(offer.getKeyC2s()))
                .setNonceSalt(com.google.protobuf.ByteString.copyFrom(offer.getNonceSalt()))
                .setMtu(offer.getMtu())
                .setHoldMs(offer.getHoldMs())
                .build();
    }

    /**
     * Maps a wire offer to the model.
     *
     * @param proto received offer; may be null
     * @return the offer, or null when the wire message is absent, carries no channel id or names a
     *         transport this build does not implement
     */
    public static MediaChannelOffer toOffer(MediaOffer proto) {
        if (proto == null || proto.getChannelId().isEmpty()) {
            return null;
        }
        MediaTransportKind transport = MediaTransportKind.fromLabel(proto.getTransport());
        if (transport == null) {
            return null;
        }
        return MediaChannelOffer
                .builder(proto.getChannelId().toByteArray(), transport)
                .host(proto.getHost())
                .port(proto.getPort())
                .secure(proto.getSecure())
                .token(proto.getToken().toByteArray())
                .keyS2c(proto.getKeyS2C().toByteArray())
                .keyC2s(proto.getKeyC2S().toByteArray())
                .nonceSalt(proto.getNonceSalt().toByteArray())
                .mtu(proto.getMtu())
                .holdMs(proto.getHoldMs())
                .build();
    }

    /**
     * Maps the answer to an offer to its wire form.
     *
     * @param channelId id of the channel the answer refers to; must not be null
     * @param type      answer type; must not be null
     * @param reason    short English description for logs; null is stored as an empty string
     * @return the wire message
     * @throws IllegalArgumentException if a required argument is null
     */
    public static MediaChannelEvent toProtoEvent(byte[] channelId, MediaChannelEventType type,
                                                String reason) {
        if (channelId == null) {
            throw new IllegalArgumentException("channelId must not be null");
        }
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        return MediaChannelEvent.newBuilder()
                .setChannelId(com.google.protobuf.ByteString.copyFrom(channelId))
                .setType(type)
                .setReason(reason == null ? "" : reason)
                .build();
    }

    /**
     * Returns whether an answer refers to the channel identified by {@code channelIdHex}.
     *
     * @param event          answer to inspect; may be null
     * @param channelIdHex   channel id in lowercase hexadecimal; may be null
     * @return true when the answer carries that very channel
     */
    public static boolean matchesChannel(MediaChannelEvent event, String channelIdHex) {
        if (event == null || channelIdHex == null || event.getChannelId().isEmpty()) {
            return false;
        }
        byte[] channelId = event.getChannelId().toByteArray();
        StringBuilder hex = new StringBuilder(channelId.length * 2);
        for (byte value : channelId) {
            hex.append(Character.forDigit((value >> 4) & 0x0f, 16));
            hex.append(Character.forDigit(value & 0x0f, 16));
        }
        return channelIdHex.equals(hex.toString());
    }
}
