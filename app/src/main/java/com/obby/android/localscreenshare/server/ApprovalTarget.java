package com.obby.android.localscreenshare.server;

/**
 * Output end for the one message a parked call sends before its user answers.
 *
 * <p>A narrow callback rather than a wider transport contract, for the same reason
 * {@link PongTarget} is one: the media transport interface stays unchanged, and a transport that
 * cannot carry the notice simply leaves the peer waiting until the approval window elapses.</p>
 */
@FunctionalInterface
public interface ApprovalTarget {

    /**
     * Sends one "this request is waiting for approval" notice to the client.
     *
     * @throws RuntimeException when the transport failed; the caller closes the call
     */
    void sendApprovalPending();
}
