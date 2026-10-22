package com.obby.android.localscreenshare.core.stream;

import java.io.IOException;

/**
 * Recovery hook a send session consults before giving up on a failed transport.
 *
 * <p>Without it, one failed media channel would take the control-plane call down with it: a
 * transport failure detaches the session, the hub drops the mapping, and the handler driving the
 * drain loop ends with an error, so a recoverable media fault would kill a stream that is still
 * otherwise healthy. The hook lets the owner of the call decide what a failure means - fall back to
 * the control-plane carrier, or report the session as unrecoverable.</p>
 *
 * <p><b>Threading and ownership.</b> The hook is called on the thread whose send failed, outside
 * the session's state lock, and it may allocate and take other locks. The transport it returns is
 * owned by the session from then on; a returned transport is installed before the failed one is
 * closed, so the peer keeps receiving media with no gap caused by ordering. Throwing from the hook
 * is treated as returning null.</p>
 */
public interface MediaTransportRecovery {

    /**
     * Chooses a replacement for a transport that just failed.
     *
     * @param failed the transport whose send threw; never null
     * @param cause  the I/O failure the send reported; never null
     * @return a replacement transport, which the session installs after re-queueing the current
     *         descriptor, or null when the failure is unrecoverable and the session must detach
     */
    MediaTransport onTransportFailed(MediaTransport failed, IOException cause);
}
