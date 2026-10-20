package com.obby.android.localscreenshare.server;

import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.stream.ClientCapabilities;
import com.obby.android.localscreenshare.core.stream.ClientSendSession;
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
 * {@link ScreenShareServer}), while client control messages arrive on the gRPC callback thread.
 * Every state-mutating method is therefore synchronized so the join and detach transitions stay
 * atomic; only the transport send itself runs outside the monitor, so a slow client cannot block
 * control-message handling. A join is attempted on the first HELLO; when the active runtime has not
 * published a descriptor yet the join is retried on subsequent drain calls until it succeeds, and a
 * rejected or fallback-requiring client is closed. An empty capability set is treated as the H.264
 * compatibility baseline.</p>
 *
 * <p><b>Takeover.</b> A join for an already-attached key replaces the old session, so this handler
 * may lose its session to a newer connection without being closed. The drain then detects that the
 * key maps to another session, reports the takeover through the injected {@link StreamCallListener}
 * and closes itself, and the hub terminates the replaced transport with an explicit status so the
 * old client stops waiting for media. {@link #close()} detaches only the session this handler
 * attached, so an old call ending never detaches its replacement.</p>
 */
public final class StreamCallHandler {

    private final StreamHub hub;

    private final StreamRuntime runtime;

    private final MediaTransport transport;

    private final StreamCallListener listener;

    private ClientSessionKey key;

    private ClientSendSession attachedSession;

    private ClientCapabilities pendingCapabilities;

    private String pendingClientId;

    // Identity the client declared in its HELLO; the session key stays client_id.
    private String deviceId;

    private String fingerprint;

    private boolean pendingJoin;

    private boolean attached;

    private boolean closed;

    // Reason the join was rejected, or null when the client attached or never attempted to join.
    private String joinFailure;

    /**
     * Creates a handler for one client that reports no events to a listener.
     *
     * @throws IllegalArgumentException if any argument is null
     */
    public StreamCallHandler(StreamHub hub, StreamRuntime runtime, MediaTransport transport) {
        this(hub, runtime, transport, StreamCallListener.NO_OP);
    }

    /**
     * Creates a handler for one client.
     *
     * @throws IllegalArgumentException if any argument is null
     */
    public StreamCallHandler(StreamHub hub, StreamRuntime runtime, MediaTransport transport,
                             StreamCallListener listener) {
        if (hub == null) {
            throw new IllegalArgumentException("hub must not be null");
        }
        if (runtime == null) {
            throw new IllegalArgumentException("runtime must not be null");
        }
        if (transport == null) {
            throw new IllegalArgumentException("transport must not be null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        this.hub = hub;
        this.runtime = runtime;
        this.transport = transport;
        this.listener = listener;
    }

    /**
     * Handles one client control message; a second HELLO after joining is ignored.
     */
    public synchronized void onClientMessage(ClientMessage message) {
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
        final ClientSessionKey sessionKey;
        final ClientSendSession session;
        synchronized (this) {
            if (closed) {
                return false;
            }
            if (!attached) {
                tryJoin();
            }
            if (!attached) {
                return false;
            }
            sessionKey = key;
            session = attachedSession;
        }
        // A takeover replaces the mapping with a newer session: this call no longer owns the
        // stream, so stop instead of driving the replacement's queue from this drain thread.
        final ClientSendSession current = hub.getSession(sessionKey);
        if (current != session) {
            if (current != null) {
                listener.onSessionTakenOver(sessionKey, transportId());
            }
            close();
            return false;
        }
        // Drain outside the monitor: the transport send may block on a slow client, and a
        // concurrent close() detaches the session first, so this becomes a no-op or fails cleanly.
        return hub.drainOne(sessionKey);
    }

    /**
     * Returns whether the handler has been closed and its session detached.
     */
    public synchronized boolean isClosed() {
        return closed;
    }

    /**
     * Returns the device id the client declared in its HELLO, or null when it declared none.
     *
     * <p>The id binds the media session to the discovery entry the same peer announced: the
     * session key remains {@code client_id}, because one device may reconnect with a new session,
     * but a server can now attribute the session to a known device instead of an opaque
     * per-connection value.</p>
     */
    public synchronized String getDeviceId() {
        return deviceId;
    }

    /**
     * Returns the fingerprint the client declared in its HELLO, or null when it declared none.
     */
    public synchronized String getFingerprint() {
        return fingerprint;
    }

    /**
     * Returns the reason the join was rejected, or null when the client attached or never joined.
     */
    public synchronized String getJoinFailure() {
        return joinFailure;
    }

    /**
     * Detaches the client's session and closes its transport; idempotent.
     *
     * <p>Only the session this handler attached is detached, so a call that lost its session to a
     * takeover can end without detaching the replacement.</p>
     */
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (key != null) {
            hub.detachIfCurrent(key, attachedSession);
            key = null;
        }
        attachedSession = null;
        transport.close();
    }

    // Called under the handler monitor from onClientMessage or drainOnce; never called directly.
    private void onHello(Hello hello) {
        if (attached || pendingJoin) {
            return;
        }
        String clientId = hello.getClientId();
        if (clientId == null || clientId.trim().isEmpty()) {
            clientId = "client-" + UUID.randomUUID();
        }
        pendingClientId = clientId;
        deviceId = emptyToNull(hello.getDeviceId());
        fingerprint = emptyToNull(hello.getFingerprint());
        pendingCapabilities = ScreenStreamProtoMapper.toClientCapabilities(hello.getCapabilities());
        if (pendingCapabilities.getCodecIds().isEmpty()) {
            // Old clients that declare nothing are assumed to support the H.264 baseline.
            pendingCapabilities = new ClientCapabilities(
                    Collections.singleton("h264"));
        }
        pendingJoin = true;
        tryJoin();
    }

    // Called under the handler monitor from onHello or drainOnce; never called directly.
    private void tryJoin() {
        if (!pendingJoin || attached || closed) {
            return;
        }
        ClientSessionKey candidate = new ClientSessionKey(runtime.getStreamKey(), pendingClientId);
        JoinDecision decision = hub.join(candidate, pendingCapabilities, transport);
        switch (decision.getStatus()) {
            case ACCEPTED:
                key = candidate;
                attachedSession = decision.getSession();
                attached = true;
                pendingJoin = false;
                break;
            case NOT_READY:
                // Keep the join pending; the drain loop retries once a descriptor is published.
                break;
            case REJECTED:
            case REQUIRES_EXPLICIT_FALLBACK:
                pendingJoin = false;
                joinFailure = decision.getReason();
                listener.onJoinRejected(candidate, transportId(), joinFailure);
                close();
                break;
            default:
                break;
        }
    }

    /**
     * Returns the identity of this call's transport for log lines.
     *
     * <p>The class name plus the JVM identity hash is stable for the life of the call and carries
     * no peer identity, so it can be logged and correlated with the transport's own events.</p>
     */
    private String transportId() {
        String type = transport.getClass().getSimpleName();
        if (type.isEmpty()) {
            type = transport.getClass().getName();
        }
        return type + "#" + Integer.toHexString(System.identityHashCode(transport));
    }

    /**
     * Returns the trimmed value, or null when it is absent or blank.
     */
    private static String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
