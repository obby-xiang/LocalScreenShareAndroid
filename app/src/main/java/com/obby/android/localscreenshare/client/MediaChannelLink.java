package com.obby.android.localscreenshare.client;

/**
 * One opened media channel, as the coordinator sees it.
 *
 * <p>The coordinator only needs to start a channel and to close it; everything else - sockets,
 * greeting, frames - belongs to the implementation. Keeping that seam narrow lets the negotiation
 * state machine be tested without a socket, while the production implementation stays a single
 * class that owns the transport details.</p>
 */
public interface MediaChannelLink {

    /**
     * Runs the channel until it ends; blocks the calling thread.
     *
     * <p>The outcome is reported through the listener the channel was created with, never by a
     * return value, so a failure and a normal end look the same to the caller.</p>
     */
    void start();

    /**
     * Closes the channel from the outside; idempotent and safe from any thread.
     */
    void close();
}
