package com.obby.android.localscreenshare.client;

import com.obby.android.localscreenshare.core.net.MediaChannelLog;
import com.obby.android.localscreenshare.core.net.MediaChannelOffer;
import com.obby.android.localscreenshare.core.net.MediaTransportKind;
import com.obby.android.localscreenshare.grpc.screenstream.MediaChannelEvent;
import com.obby.android.localscreenshare.grpc.screenstream.MediaChannelEventType;
import com.obby.android.localscreenshare.grpc.screenstream.MediaClose;
import com.obby.android.localscreenshare.grpc.screenstream.MediaOffer;
import com.obby.android.localscreenshare.transport.MediaChannelOfferMapper;
import com.obby.android.localscreenshare.core.net.MediaChannelStatusListener;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Client side of the media channel negotiation: opens an offered channel and reports its outcome.
 *
 * <p>An offer arrives on the control stream and is answered exactly once: {@code READY} when the
 * channel is bound, {@code FAILED} when it cannot be opened, {@code CLOSED} when it was up and then
 * ended. The server decides what to do with the answer; this class only owns the facts of one
 * channel, so a repeated offer, a late answer and a second failure are all no-ops.</p>
 *
 * <p><b>Threading.</b> {@link #onOffer} and {@link #onMediaClose} run on the gRPC callback thread,
 * the channel's own outcome arrives on the configured executor, and the answer leaves through the
 * sink, which the client marshals to the main thread. State is guarded by the coordinator's monitor,
 * so no callback can interleave with another.</p>
 *
 * <p><b>Channels this build cannot open.</b> A transport other than the dedicated stream, an offer
 * with no endpoint and an installation without an identity all answer {@code FAILED} immediately
 * with a distinct reason, so the server's log says why the call stayed on its carrier.</p>
 */
public final class MediaChannelCoordinator {

    /**
     * Reason sent when the offered transport is not implemented by this build.
     */
    public static final String REASON_UNSUPPORTED = "unsupported_transport";

    /**
     * Reason sent when the offer names no reachable endpoint.
     */
    public static final String REASON_NO_ENDPOINT = "no_endpoint";

    /**
     * Reason sent when this installation cannot present an identity for the channel.
     */
    public static final String REASON_NO_IDENTITY = "no_identity";

    /**
     * Sends one answer to the server; implemented by the client, which marshals to the main thread.
     */
    public interface AnswerSink {

        /**
         * Sends one answer; called from any thread.
         *
         * @param event answer to send; never null
         */
        void send(MediaChannelEvent event);
    }

    /**
     * Builds the channel of one offer.
     */
    public interface ChannelFactory {

        /**
         * Creates the channel of {@code offer} without starting it.
         *
         * @param offer  offer to answer; never null
         * @param status receiver of the channel outcome; never null
         * @return the channel, or null when this installation cannot build one
         */
        MediaChannelLink create(MediaChannelOffer offer, MediaChannelStatusListener status);
    }

    private final AnswerSink answerSink;

    private final Executor executor;

    private final ChannelFactory channelFactory;

    // Diagnostic sink of this negotiation; NONE when the caller does not install one.
    private final MediaChannelLog log;

    // Owner callback of "this channel lost a unit and needs a join point"; never null.
    private final Runnable joinPointRequester;

    private final Map<String, MediaChannelLink> links = new HashMap<>();

    private final Set<String> closed = new HashSet<>();

    private String openChannelId;

    private boolean readySent;

    private boolean failedSent;

    private boolean closedSent;

    private String lastFailureReason;

    /**
     * Creates a coordinator.
     *
     * @param answerSink     output for the answers; required
     * @param executor       executor that runs each channel until it ends; required
     * @param channelFactory builder of the channels; required
     * @throws IllegalArgumentException if an argument is null
     */
    public MediaChannelCoordinator(AnswerSink answerSink, Executor executor,
                                   ChannelFactory channelFactory) {
        this(answerSink, executor, channelFactory, MediaChannelLog.NONE);
    }

    /**
     * Creates a coordinator that reports its answers through {@code log}.
     *
     * @param answerSink     output for the answers; required
     * @param executor       executor that runs each channel until it ends; required
     * @param channelFactory builder of the channels; required
     * @param log            diagnostic sink; required, may be {@link MediaChannelLog#NONE}
     * @throws IllegalArgumentException if an argument is null
     */
    public MediaChannelCoordinator(AnswerSink answerSink, Executor executor,
                                   ChannelFactory channelFactory, MediaChannelLog log) {
        this(answerSink, executor, channelFactory, log, () -> {
        });
    }

    /**
     * Creates a coordinator that forwards a channel's join-point request to its owner.
     *
     * <p>A datagram channel loses whole access units, and the channel itself cannot ask the server
     * for a fresh random-access point: it reports the need, and the owner turns it into a request
     * on the control stream. Without this callback the report dies at the coordinator, which is
     * exactly what a device capture showed - losses every second, almost no key-frame requests.</p>
     *
     * @param answerSink          output for the answers; required
     * @param executor            executor that runs each channel until it ends; required
     * @param channelFactory      builder of the channels; required
     * @param log                 diagnostic sink; required, may be {@link MediaChannelLog#NONE}
     * @param joinPointRequester  owner callback of a join-point request; required, may be a no-op
     * @throws IllegalArgumentException if an argument is null
     */
    public MediaChannelCoordinator(AnswerSink answerSink, Executor executor,
                                   ChannelFactory channelFactory, MediaChannelLog log,
                                   Runnable joinPointRequester) {
        if (answerSink == null) {
            throw new IllegalArgumentException("answerSink must not be null");
        }
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        }
        if (channelFactory == null) {
            throw new IllegalArgumentException("channelFactory must not be null");
        }
        if (log == null) {
            throw new IllegalArgumentException("log must not be null");
        }
        if (joinPointRequester == null) {
            throw new IllegalArgumentException("joinPointRequester must not be null");
        }
        this.answerSink = answerSink;
        this.executor = executor;
        this.channelFactory = channelFactory;
        this.log = log;
        this.joinPointRequester = joinPointRequester;
    }

    /**
     * Handles one offer; the answer is sent once the outcome is known.
     *
     * @param offer offer received on the control stream; may be null
     */
    public void onOffer(MediaOffer offer) {
        final String channelIdHex;
        final MediaChannelOffer parsed;
        synchronized (this) {
            if (offer == null || offer.getChannelId().isEmpty()) {
                return;
            }
            channelIdHex = hexOf(offer.getChannelId().toByteArray());
            if (closed.contains(channelIdHex) || channelIdHex.equals(openChannelId)) {
                // A repeated offer, or one for a channel the call already dropped.
                return;
            }
            parsed = MediaChannelOfferMapper.toOffer(offer);
            openChannelId = channelIdHex;
            readySent = false;
            failedSent = false;
            closedSent = false;
            lastFailureReason = null;
        }
        if (parsed == null || !isChannelSupported(parsed.getTransport())) {
            answerFailed(channelIdHex, REASON_UNSUPPORTED);
            return;
        }
        if (!parsed.isEndpointPresent()) {
            answerFailed(channelIdHex, REASON_NO_ENDPOINT);
            return;
        }
        try {
            executor.execute(() -> open(channelIdHex, parsed));
        } catch (RejectedExecutionException e) {
            answerFailed(channelIdHex, REASON_NO_IDENTITY);
        }
    }

    /**
     * Applies the server's notice that a channel is gone.
     *
     * <p>The channel is closed and its id remembered, so a late offer for it is ignored. No answer
     * is sent: the server already knows.</p>
     *
     * @param close notice received on the control stream; may be null
     */
    public void onMediaClose(MediaClose close) {
        if (close == null || close.getChannelId().isEmpty()) {
            return;
        }
        final String channelIdHex = hexOf(close.getChannelId().toByteArray());
        final MediaChannelLink link;
        synchronized (this) {
            closed.add(channelIdHex);
            closedSent = true;
            link = links.remove(channelIdHex);
            if (channelIdHex.equals(openChannelId)) {
                openChannelId = null;
            }
        }
        if (link != null) {
            link.close();
        }
    }

    /**
     * Forgets every channel, for a new call on the same client instance.
     */
    public void reset() {
        final Map<String, MediaChannelLink> current;
        synchronized (this) {
            current = new HashMap<>(links);
            links.clear();
            closed.clear();
            openChannelId = null;
            readySent = false;
            failedSent = false;
            closedSent = false;
            lastFailureReason = null;
        }
        for (MediaChannelLink link : current.values()) {
            link.close();
        }
    }

    /**
     * Returns the channel currently held, in lowercase hexadecimal, or null when none is.
     */
    public synchronized String getOpenChannelIdHex() {
        return openChannelId;
    }

    /**
     * Returns why the last offer was refused, or null when none was.
     */
    public synchronized String getLastFailureReason() {
        return lastFailureReason;
    }

    /**
     * Returns the transport labels a client of this build declares in its HELLO.
     *
     * <p>Both are declared: the stream channel is implemented and the datagram tier is not, and
     * declaring the second lets the server walk its fallback chain instead of guessing what this
     * build can do. An offer for the datagram tier is refused with a reason, which is exactly the
     * signal the server needs.</p>
     */
    public static String[] declaredTransports() {
        return new String[]{
                MediaTransportKind.TCP.getLabel(),
                MediaTransportKind.UDP.getLabel(),
        };
    }

    /**
     * Returns whether this build can open the given transport.
     *
     * <p>The control plane is never offered - the call already runs on it - so it is the one
     * unrecognised answer here; every unknown value is refused with a reason instead of being
     * ignored, which is what lets the server move on quickly.</p>
     */
    private static boolean isChannelSupported(MediaTransportKind transport) {
        return transport == MediaTransportKind.TCP || transport == MediaTransportKind.UDP;
    }

    private void open(String channelIdHex, MediaChannelOffer offer) {
        final MediaChannelLink link;
        try {
            link = channelFactory.create(offer, new MediaChannelStatusListener() {
                @Override
                public void onChannelReady() {
                    answerReady(channelIdHex);
                }

                @Override
                public void onChannelFailed(String reason) {
                    answerFailedAfterOpen(channelIdHex, reason);
                }

                @Override
                public void onJoinPointNeeded() {
                    joinPointRequester.run();
                }
            });
        } catch (RuntimeException e) {
            answerFailed(channelIdHex, REASON_NO_IDENTITY);
            return;
        }
        if (link == null) {
            answerFailed(channelIdHex, REASON_NO_IDENTITY);
            return;
        }
        synchronized (this) {
            if (!channelIdHex.equals(openChannelId) || closed.contains(channelIdHex)) {
                link.close();
                return;
            }
            links.put(channelIdHex, link);
        }
        link.start();
        synchronized (this) {
            links.remove(channelIdHex);
        }
    }

    private void answerReady(String channelIdHex) {
        synchronized (this) {
            if (!channelIdHex.equals(openChannelId) || readySent || failedSent || closedSent) {
                return;
            }
            readySent = true;
        }
        // One line per channel answer: the device report could not tell why a negotiation ended
        // because neither end logged its reason.
        log.info("answer: READY, channel = " + channelIdHex);
        answerSink.send(MediaChannelOfferMapper.toProtoEvent(
                MediaChannelOfferMapper.toOfferChannelId(channelIdHex),
                MediaChannelEventType.READY, ""));
    }

    private void answerFailedAfterOpen(String channelIdHex, String reason) {
        final boolean wasReady;
        synchronized (this) {
            if (!channelIdHex.equals(openChannelId) || closedSent || failedSent) {
                return;
            }
            lastFailureReason = reason;
            wasReady = readySent;
            if (wasReady) {
                closedSent = true;
            } else {
                failedSent = true;
            }
        }
        log.warn("answer: " + (wasReady ? "CLOSED" : "FAILED") + ", channel = " + channelIdHex
                + ", reason = " + reason);
        answerSink.send(MediaChannelOfferMapper.toProtoEvent(
                MediaChannelOfferMapper.toOfferChannelId(channelIdHex),
                wasReady ? MediaChannelEventType.CLOSED : MediaChannelEventType.FAILED,
                reason == null ? "" : reason));
    }

    private void answerFailed(String channelIdHex, String reason) {
        synchronized (this) {
            if (!channelIdHex.equals(openChannelId) || readySent || failedSent || closedSent) {
                return;
            }
            failedSent = true;
            lastFailureReason = reason;
        }
        log.warn("answer: FAILED, channel = " + channelIdHex + ", reason = " + reason);
        answerSink.send(MediaChannelOfferMapper.toProtoEvent(
                MediaChannelOfferMapper.toOfferChannelId(channelIdHex),
                MediaChannelEventType.FAILED, reason));
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
