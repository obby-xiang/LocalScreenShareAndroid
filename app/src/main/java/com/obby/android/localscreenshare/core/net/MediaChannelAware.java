package com.obby.android.localscreenshare.core.net;

/**
 * A transport that knows which media channel it belongs to.
 *
 * <p>The send session reports the channel of its current transport in the per-session log line, and
 * it learns that label either when the session is created or when a channel swaps in. A swap
 * driven by the recovery path only receives the replacement transport, so the label has to travel
 * with the object. Implementations of a socket transport - the dedicated stream and, later, the
 * datagram channel - declare their kind here; a transport that does not implement this interface
 * (test doubles, wrappers) leaves the label of the session unchanged.</p>
 */
public interface MediaChannelAware {

    /**
     * Returns the media channel this transport carries.
     *
     * @return the channel of this transport; never null
     */
    MediaTransportKind getMediaTransportKind();
}
