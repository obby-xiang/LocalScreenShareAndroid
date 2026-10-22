package com.obby.android.localscreenshare.client;

import com.obby.android.localscreenshare.core.net.MediaTransportKind;
import com.obby.android.localscreenshare.grpc.screenstream.MediaChannelEvent;
import com.obby.android.localscreenshare.grpc.screenstream.MediaChannelEventType;
import com.obby.android.localscreenshare.grpc.screenstream.MediaClose;
import com.obby.android.localscreenshare.grpc.screenstream.MediaOffer;
import com.obby.android.localscreenshare.transport.MediaChannelOfferMapper;

import java.util.HashSet;
import java.util.Set;

/**
 * Client side of the media channel negotiation: answers offers and tracks the channel it owns.
 *
 * <p>The coordinator is a pure state machine driven by the control stream, so it can be tested
 * without a socket and reasoned about without a thread. It owns exactly three facts: which channel
 * id it is currently answering, which ids it already answered, and which ids were closed. That is
 * enough to keep the answer side idempotent - a repeated offer never produces a second answer -
 * and to ignore a late offer for a channel the call has already given up.</p>
 *
 * <p><b>This build refuses every channel.</b> No media channel is implemented yet, so every offer
 * is answered with {@link MediaChannelEventType#FAILED} and the reason {@code not_implemented}; the
 * server then falls back to the control-plane carrier, which is already carrying the media. That is
 * the whole point of landing the negotiation before the channel: the state machine, the timeout and
 * the fallback path are exercised in production from the first batch, and the channel only has to
 * replace the answer.</p>
 *
 * <p>Not thread safe by design: the owning client drives it on one thread.</p>
 */
public final class MediaChannelCoordinator {

    /**
     * Reason sent with every refusal in this build.
     */
    public static final String REASON_NOT_IMPLEMENTED = "not_implemented";

    private final Set<String> answered = new HashSet<>();

    private final Set<String> closed = new HashSet<>();

    private String openChannelId;

    private String lastFailureReason;

    /**
     * Handles one offer and returns the answer to send, if any.
     *
     * <p>An offer without a channel id, a repeated offer for a channel that was already answered
     * and an offer for a channel that was closed afterwards all produce no answer, so the server
     * sees at most one answer per channel and never a duplicate failure.</p>
     *
     * @param offer offer received on the control stream; may be null
     * @return the answer to send, or null when the offer is ignored
     */
    public MediaChannelEvent onOffer(MediaOffer offer) {
        if (offer == null || offer.getChannelId().isEmpty()) {
            return null;
        }
        byte[] channelId = offer.getChannelId().toByteArray();
        String key = hexOf(channelId);
        if (answered.contains(key) || closed.contains(key)) {
            return null;
        }
        answered.add(key);
        openChannelId = key;
        lastFailureReason = REASON_NOT_IMPLEMENTED;
        return MediaChannelOfferMapper.toProtoEvent(channelId, MediaChannelEventType.FAILED,
                REASON_NOT_IMPLEMENTED);
    }

    /**
     * Applies the server's notice that a channel is gone.
     *
     * <p>Idempotent: closing an unknown or already closed channel changes nothing, and closing the
     * channel the coordinator currently holds releases it, so a still-running channel task can
     * check {@link #getOpenChannelIdHex()} and stop.</p>
     *
     * @param close notice received on the control stream; may be null
     */
    public void onMediaClose(MediaClose close) {
        if (close == null || close.getChannelId().isEmpty()) {
            return;
        }
        String key = hexOf(close.getChannelId().toByteArray());
        closed.add(key);
        if (key.equals(openChannelId)) {
            openChannelId = null;
        }
    }

    /**
     * Forgets everything, for a new call on the same client instance.
     */
    public void reset() {
        answered.clear();
        closed.clear();
        openChannelId = null;
        lastFailureReason = null;
    }

    /**
     * Returns the channel currently held, in lowercase hexadecimal, or null when none is.
     */
    public String getOpenChannelIdHex() {
        return openChannelId;
    }

    /**
     * Returns why the last offer was refused, or null before the first one.
     */
    public String getLastFailureReason() {
        return lastFailureReason;
    }

    /**
     * Returns the transport label a client of this build declares in its HELLO.
     *
     * <p>Both channels are declared even though neither is implemented yet: the server's policy
     * then offers them in order, the coordinator refuses each, and the fallback path is exercised
     * end to end before a channel exists. A build that gains the channels only has to answer READY
     * instead; the declaration does not change.</p>
     */
    public static String[] declaredTransports() {
        return new String[]{
                MediaTransportKind.TCP.getLabel(),
                MediaTransportKind.UDP.getLabel(),
        };
    }

    private static String hexOf(byte[] channelId) {
        StringBuilder hex = new StringBuilder(channelId.length * 2);
        for (byte value : channelId) {
            hex.append(Character.forDigit((value >> 4) & 0x0f, 16));
            hex.append(Character.forDigit(value & 0x0f, 16));
        }
        return hex.toString();
    }
}
