package com.obby.android.localscreenshare.transport;

import com.obby.android.localscreenshare.core.model.StreamFeedback;
import com.obby.android.localscreenshare.grpc.screenstream.Hello;
import com.obby.android.localscreenshare.grpc.screenstream.MediaChannelEvent;
import com.obby.android.localscreenshare.grpc.screenstream.Ping;
import com.obby.android.localscreenshare.grpc.screenstream.Ready;
import com.obby.android.localscreenshare.grpc.screenstream.RequestKeyframe;

/**
 * Callback sink for client-to-server control messages on a stream.
 *
 * <p>The listener is invoked on the thread that drives the inbound gRPC observer, which is
 * serialized by the transport; implementations must not block and must not call back into the
 * stream transport from a callback.</p>
 */
public interface ClientMessageListener {

    /**
     * A client announced itself with an optional identifier and capability set.
     */
    void onHello(Hello hello);

    /**
     * A client signalled that it is ready to receive media.
     */
    void onReady(Ready ready);

    /**
     * A client asked the server to emit a random-access point as soon as possible.
     */
    void onRequestKeyframe(RequestKeyframe requestKeyframe);

    /**
     * A client asked for a round-trip echo.
     *
     * <p>Implementations answer by echoing the sequence and the client uptime verbatim; they must
     * not add a server timestamp, because the measurement relies on the client comparing its own
     * clock only.</p>
     */
    void onPing(Ping ping);

    /**
     * A client answered an offer of a dedicated media channel.
     *
     * <p>The default implementation ignores the answer, which is what a listener written before the
     * negotiation existed does: a call that never offered a channel receives no answer, and one
     * that did simply sees its offer window elapse and keeps the control-plane carrier.</p>
     *
     * @param event answer carrying the channel id, its outcome and a short reason
     */
    default void onMediaChannelEvent(MediaChannelEvent event) {
    }

    /**
     * A client reported its decode-queue backlog.
     *
     * <p>The default implementation ignores the report, which is what a listener written before
     * backpressure existed does: an old server keeps sending at its configured bitrate, and the
     * viewer keeps catching up on its own. A report is only ever sent by an attached client.</p>
     *
     * @param feedback peaks of the client's last reporting window; never null
     */
    default void onFeedback(StreamFeedback feedback) {
    }
}
