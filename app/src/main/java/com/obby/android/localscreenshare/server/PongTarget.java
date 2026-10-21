package com.obby.android.localscreenshare.server;

/**
 * Output end for the application-level round-trip echo.
 *
 * <p>The stream call handler needs exactly one capability from its transport: answering a ping with
 * a pong. Expressing it as this narrow callback keeps {@link
 * com.obby.android.localscreenshare.core.stream.MediaTransport} unchanged, so every existing fake
 * and transport implementation keeps compiling, while the production transport can still serialize
 * the echo behind the same lock it uses for media.</p>
 *
 * <p>Implementations must be thread-safe: the echo runs on the gRPC callback thread that delivered
 * the ping, while media sends run on the per-client drain thread.</p>
 */
public interface PongTarget {

    /**
     * Target that drops every echo, used when the caller does not measure round trips.
     */
    PongTarget NO_OP = (seq, clientUptimeMs) -> {
    };

    /**
     * Sends one echo.
     *
     * <p>Must not throw: a lost echo only costs one sample, and the caller must never be disturbed
     * by a transport that is already closing.</p>
     *
     * @param seq            sequence number the client assigned to the ping
     * @param clientUptimeMs client uptime the ping carried, echoed verbatim
     */
    void sendPong(int seq, long clientUptimeMs);
}
