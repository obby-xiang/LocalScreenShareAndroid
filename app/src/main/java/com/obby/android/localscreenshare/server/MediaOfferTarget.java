package com.obby.android.localscreenshare.server;

import com.obby.android.localscreenshare.core.net.MediaChannelOffer;

/**
 * Output end for the one message that starts a media channel negotiation.
 *
 * <p>A narrow callback rather than a wider transport contract, for the same reason
 * {@link PongTarget} is one: the media transport interface stays unchanged, and a transport that
 * cannot carry the offer simply leaves the call on the control-plane carrier once the offer window
 * elapses.</p>
 *
 * <p>Implementations must be thread-safe: the offer is written from the thread that drove the join
 * or settled a failure, while media sends leave from the per-client drain thread.</p>
 */
@FunctionalInterface
public interface MediaOfferTarget {

    /**
     * Target that drops every offer, used when the call cannot negotiate a channel.
     */
    MediaOfferTarget NO_OP = offer -> {
    };

    /**
     * Sends one channel offer to the client.
     *
     * <p>Must not throw: a lost offer only costs the negotiation window, and the caller falls back
     * to the carrier exactly as it would after a timeout.</p>
     *
     * @param offer channel to offer; never null
     */
    void sendOffer(MediaChannelOffer offer);
}
