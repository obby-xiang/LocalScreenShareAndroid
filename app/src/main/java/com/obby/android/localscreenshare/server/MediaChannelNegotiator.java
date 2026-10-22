package com.obby.android.localscreenshare.server;

import com.obby.android.localscreenshare.core.net.MediaChannelOffer;
import com.obby.android.localscreenshare.core.net.MediaChannelTicket;
import com.obby.android.localscreenshare.core.net.MediaTransportKind;
import com.obby.android.localscreenshare.core.net.MediaTransportPolicy;
import com.obby.android.localscreenshare.core.util.TimeSource;
import com.obby.android.localscreenshare.transport.TcpMediaConstants;
import com.obby.android.localscreenshare.transport.UdpMediaConstants;

import java.util.Set;

/**
 * Server side of the media channel negotiation: decides the channel, issues its ticket and waits
 * for the answer.
 *
 * <p>One negotiator belongs to one call. It wraps the pure {@link MediaTransportPolicy} with the
 * state a decision needs - the ticket that is currently outstanding, the window it is valid for and
 * the candidate that was offered - so the call handler can drive the negotiation without knowing
 * how a channel is chosen or how long an answer may take.</p>
 *
 * <p><b>One channel at a time.</b> Issuing a ticket revokes the previous one, because the call has
 * exactly one media path: an answer for a channel that is no longer outstanding is stale by
 * construction and is rejected by id. {@link #revoke()} drops the outstanding ticket when the call
 * ends, so a leaked token stops being usable the moment the call is gone.</p>
 *
 * <p><b>Ownership.</b> A negotiator belongs to exactly one call and is not thread safe: the call
 * handler drives every method under its own monitor, so the outstanding ticket can never be read
 * while it is being replaced. A test uses it from one thread.</p>
 *
 * <p><b>Time.</b> The clock is injected: the offer window is measured on the same monotonic source
 * the drain loop uses, and the ticket lifetime is measured against it too, so both can be tested
 * without sleeping.</p>
 */
public final class MediaChannelNegotiator {

    private final MediaTransportPolicy.Mode mode;

    private final boolean udpEnabled;

    private final MediaOfferTarget offerTarget;

    private final TimeSource timeSource;

    private final MediaChannelEndpoint endpoint;

    private MediaChannelTicket ticket;

    private MediaTransportKind offeredKind;

    private long offerDeadlineMs;

    /**
     * Creates a negotiator for one call that cannot reach a channel endpoint.
     *
     * @param mode        deployment preference from the hidden setting; null resolves to
     *                    {@link MediaTransportPolicy.Mode#AUTO}
     * @param udpEnabled  whether the experimental datagram tier may be offered
     * @param offerTarget output for the offer messages; required
     * @param timeSource  clock used for the offer window and the ticket lifetime; required
     * @throws IllegalArgumentException if a required argument is null
     */
    public MediaChannelNegotiator(MediaTransportPolicy.Mode mode, boolean udpEnabled,
                                  MediaOfferTarget offerTarget, TimeSource timeSource) {
        this(mode, udpEnabled, offerTarget, timeSource, MediaChannelEndpoint.unavailable());
    }

    /**
     * Creates a negotiator for one call.
     *
     * @param mode        deployment preference from the hidden setting; null resolves to
     *                    {@link MediaTransportPolicy.Mode#AUTO}
     * @param udpEnabled  whether the experimental datagram tier may be offered
     * @param offerTarget output for the offer messages; required
     * @param timeSource  clock used for the offer window and the ticket lifetime; required
     * @param endpoint    where the channel of this session can be reached; required
     * @throws IllegalArgumentException if a required argument is null
     */
    public MediaChannelNegotiator(MediaTransportPolicy.Mode mode, boolean udpEnabled,
                                  MediaOfferTarget offerTarget, TimeSource timeSource,
                                  MediaChannelEndpoint endpoint) {
        if (offerTarget == null) {
            throw new IllegalArgumentException("offerTarget must not be null");
        }
        if (timeSource == null) {
            throw new IllegalArgumentException("timeSource must not be null");
        }
        if (endpoint == null) {
            throw new IllegalArgumentException("endpoint must not be null");
        }
        this.mode = mode == null ? MediaTransportPolicy.Mode.AUTO : mode;
        this.udpEnabled = udpEnabled;
        this.offerTarget = offerTarget;
        this.timeSource = timeSource;
        this.endpoint = endpoint;
    }

    /**
     * Returns the deployment preference this negotiator applies.
     */
    public MediaTransportPolicy.Mode getMode() {
        return mode;
    }

    /**
     * Chooses the next channel to offer.
     *
     * @param clientOffered transport names the client declared; empty selects the control plane
     * @param alreadyFailed transports that already failed in this call
     * @return the channel to offer, or {@link MediaTransportKind#GRPC} when the call stays on the
     *         control-plane carrier
     */
    public MediaTransportKind nextKind(Set<String> clientOffered,
                                       Set<MediaTransportKind> alreadyFailed) {
        return MediaTransportPolicy.next(null, clientOffered,
                MediaTransportPolicy.available(mode), udpEnabled, alreadyFailed);
    }

    /**
     * Chooses the first channel of the call.
     *
     * @param clientOffered transport names the client declared; empty selects the control plane
     * @return the channel to offer, or {@link MediaTransportKind#GRPC}
     */
    public MediaTransportKind firstKind(Set<String> clientOffered) {
        return MediaTransportPolicy.first(clientOffered, MediaTransportPolicy.available(mode),
                udpEnabled);
    }

    /**
     * Issues a ticket for {@code kind} and sends the offer.
     *
     * <p>The control plane is never offered - it is already carrying the call - so asking for it
     * only revokes the outstanding ticket. A failing offer target is treated as a failed offer: the
     * ticket is revoked, nothing is outstanding, and the caller falls back.</p>
     *
     * @param kind         channel to offer; {@link MediaTransportKind#GRPC} means "stay"
     * @param peerDeviceId identity proven for this call, or null when it has none
     * @return true when an offer was sent and the answer window is now open
     */
    public boolean offer(MediaTransportKind kind, String peerDeviceId) {
        revoke();
        if (kind == null || kind == MediaTransportKind.GRPC || !endpoint.isAvailable()) {
            return false;
        }
        long nowMs = timeSource.nowMs();
        MediaChannelTicket issued = MediaChannelTicket.issue(kind, peerDeviceId, nowMs);
        MediaChannelOffer offer = MediaChannelOffer
                .builder(issued.getChannelId(), kind)
                .host(endpoint.getHost())
                .port(endpoint.getPort())
                .secure(endpoint.isSecure())
                .token(issued.getToken())
                .build();
        try {
            offerTarget.sendOffer(offer);
        } catch (RuntimeException e) {
            return false;
        }
        ticket = issued;
        offeredKind = kind;
        offerDeadlineMs = nowMs + offerTimeoutMs(kind);
        return true;
    }

    /**
     * Returns whether the outstanding offer exceeded its window.
     *
     * <p>False when nothing is outstanding, so a caller can poll this on every drain pass.</p>
     */
    public boolean isOfferExpired() {
        MediaChannelTicket current = ticket;
        return current != null && timeSource.nowMs() >= offerDeadlineMs;
    }

    /**
     * Returns the channel that is currently offered, or null when nothing is outstanding.
     */
    public MediaTransportKind getOfferedKind() {
        return offeredKind;
    }

    /**
     * Returns the id of the outstanding channel in lowercase hexadecimal, or null when nothing is
     * outstanding. An answer for any other channel is stale.
     */
    public String getChannelIdHex() {
        MediaChannelTicket current = ticket;
        return current == null ? null : current.getChannelIdHex();
    }

    /**
     * Returns the outstanding ticket, or null; intended for inspection and tests.
     */
    public MediaChannelTicket getTicket() {
        return ticket;
    }

    /**
     * Drops the outstanding ticket so its token can never be consumed; idempotent.
     */
    public void revoke() {
        ticket = null;
        offeredKind = null;
        offerDeadlineMs = 0L;
    }

    /**
     * Returns the answer window of a channel, in milliseconds.
     */
    private static long offerTimeoutMs(MediaTransportKind kind) {
        return kind == MediaTransportKind.UDP
                ? UdpMediaConstants.OFFER_TIMEOUT_MS : TcpMediaConstants.OFFER_TIMEOUT_MS;
    }
}
