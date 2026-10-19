package com.obby.android.localscreenshare.server;

import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.stream.ClientCapabilities;
import com.obby.android.localscreenshare.core.stream.JoinDecision;
import com.obby.android.localscreenshare.core.stream.MediaTransport;
import com.obby.android.localscreenshare.core.stream.StreamHub;
import com.obby.android.localscreenshare.core.stream.StreamRuntime;
import com.obby.android.localscreenshare.grpc.screenstream.ClientMessage;
import com.obby.android.localscreenshare.grpc.screenstream.Hello;
import com.obby.android.localscreenshare.transport.ScreenStreamProtoMapper;

import java.io.IOException;
import java.util.Collections;
import java.util.UUID;

/**
 * Orchestrates one client's media session on the server side of a stream call.
 *
 * <p>The handler owns the join, keyframe-request and drain lifecycle for a single client. It never
 * starts threads itself: {@link #drainOnce()} is driven by the owner (a per-client drain thread in
 * {@link LssServer}), so the handler stays synchronous and unit-testable. A join is attempted on
 * the first HELLO; when the active runtime has not published a descriptor yet the join is retried
 * on subsequent drain calls until it succeeds, and a rejected or fallback-requiring client is
 * closed. An empty capability set is treated as the H.264 compatibility baseline.</p>
 */
public final class StreamCallHandler {

    private final StreamHub hub;

    private final StreamRuntime runtime;

    private final MediaTransport transport;

    private ClientSessionKey key;

    private ClientCapabilities pendingCapabilities;

    private String pendingClientId;

    private boolean pendingJoin;

    private boolean attached;

    private boolean closed;

    /**
     * Creates a handler for one client.
     *
     * @throws IllegalArgumentException if any argument is null
     */
    public StreamCallHandler(StreamHub hub, StreamRuntime runtime, MediaTransport transport) {
        if (hub == null) {
            throw new IllegalArgumentException("hub must not be null");
        }
        if (runtime == null) {
            throw new IllegalArgumentException("runtime must not be null");
        }
        if (transport == null) {
            throw new IllegalArgumentException("transport must not be null");
        }
        this.hub = hub;
        this.runtime = runtime;
        this.transport = transport;
    }

    /** Handles one client control message; a second HELLO after joining is ignored. */
    public void onClientMessage(ClientMessage message) {
        if (closed || message == null) {
            return;
        }
        switch (message.getPayloadCase()) {
            case HELLO:
                onHello(message.getHello());
                break;
            case READY:
                // The drain loop retries a pending join; no explicit work here.
                break;
            case REQUEST_KEYFRAME:
                runtime.requestJoinPoint();
                break;
            case PAYLOAD_NOT_SET:
            default:
                break;
        }
    }

    /**
     * Drains at most one descriptor or access unit, attempting a pending join first.
     *
     * @return true when something was sent, false when the client is not attached yet or there was
     *         nothing to send
     * @throws IOException if the transport failed, after the session detached
     */
    public boolean drainOnce() throws IOException {
        if (closed) {
            return false;
        }
        if (!attached) {
            tryJoin();
        }
        if (!attached) {
            return false;
        }
        return hub.drainOne(key);
    }

    /** Returns whether the handler has been closed and its session detached. */
    public boolean isClosed() {
        return closed;
    }

    /** Detaches the client's session and closes its transport; idempotent. */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (key != null) {
            hub.detach(key);
            key = null;
        }
        transport.close();
    }

    private void onHello(Hello hello) {
        if (attached || pendingJoin) {
            return;
        }
        String clientId = hello.getClientId();
        if (clientId == null || clientId.trim().isEmpty()) {
            clientId = "client-" + UUID.randomUUID();
        }
        pendingClientId = clientId;
        pendingCapabilities = ScreenStreamProtoMapper.toClientCapabilities(hello.getCapabilities());
        if (pendingCapabilities.getCodecIds().isEmpty()) {
            // Old clients that declare nothing are assumed to support the H.264 baseline.
            pendingCapabilities = new ClientCapabilities(
                    Collections.singleton("h264"));
        }
        pendingJoin = true;
        tryJoin();
    }

    private void tryJoin() {
        if (!pendingJoin || attached || closed) {
            return;
        }
        ClientSessionKey candidate = new ClientSessionKey(runtime.getStreamKey(), pendingClientId);
        JoinDecision decision = hub.join(candidate, pendingCapabilities, transport);
        switch (decision.getStatus()) {
            case ACCEPTED:
                key = candidate;
                attached = true;
                pendingJoin = false;
                break;
            case NOT_READY:
                // Keep the join pending; the drain loop retries once a descriptor is published.
                break;
            case REJECTED:
            case REQUIRES_EXPLICIT_FALLBACK:
                pendingJoin = false;
                close();
                break;
            default:
                break;
        }
    }
}
