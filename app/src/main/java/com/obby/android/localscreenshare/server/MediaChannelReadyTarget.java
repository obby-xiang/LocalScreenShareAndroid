package com.obby.android.localscreenshare.server;

import com.obby.android.localscreenshare.core.stream.MediaTransport;

/**
 * Receives the channel a call negotiated.
 *
 * <p>Called on the thread that completed the channel handshake, which is not the drain thread of
 * the call; an implementation must therefore only hand the transport over and never touch the
 * session from there.</p>
 */
public interface MediaChannelReadyTarget {

    /**
     * A channel was bound to this call.
     *
     * @param transport established channel; never null
     */
    void onMediaChannelReady(MediaTransport transport);
}
