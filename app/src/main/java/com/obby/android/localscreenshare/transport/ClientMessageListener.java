package com.obby.android.localscreenshare.transport;

import com.obby.android.localscreenshare.grpc.screenstream.Hello;
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

    /** A client announced itself with an optional identifier and capability set. */
    void onHello(Hello hello);

    /** A client signalled that it is ready to receive media. */
    void onReady(Ready ready);

    /** A client asked the server to emit a random-access point as soon as possible. */
    void onRequestKeyframe(RequestKeyframe requestKeyframe);
}
