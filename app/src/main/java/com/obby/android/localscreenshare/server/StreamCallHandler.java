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
import com.obby.android.localscreenshare.identity.tls.TlsCertificate;
import com.obby.android.localscreenshare.transport.ScreenStreamProtoMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
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

    // Remote address the call arrived on; null when the caller does not attribute statistics to a
    // peer address (tests, or a call whose transport attributes carry none).
    private final InetSocketAddress remoteAddress;

    // Echo target for round-trip probes; NO_OP when the caller does not measure round trips.
    private final PongTarget pongTarget;

    // Certificate identity of the transport, or null on a plaintext call or in a test.
    private final TlsCertificate peerCertificate;

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
        this(hub, runtime, transport, listener, null);
    }

    /**
     * Creates a handler for one client that also knows the address its call arrived on.
     *
     * <p>The address is only used to attribute the session's counters to the transport row that
     * carries the same address in the server statistics; it never influences the session itself.</p>
     *
     * @param hub           stream hub the client joins; required
     * @param runtime       active stream runtime; required
     * @param transport     transport owned by this call; required
     * @param listener      receiver of call events; required
     * @param remoteAddress address the call arrived on, or null when unknown
     * @throws IllegalArgumentException if any required argument is null
     */
    public StreamCallHandler(StreamHub hub, StreamRuntime runtime, MediaTransport transport,
                             StreamCallListener listener, InetSocketAddress remoteAddress) {
        this(hub, runtime, transport, listener, remoteAddress, PongTarget.NO_OP);
    }

    /**
     * Creates a handler for one client that also echoes round-trip probes.
     *
     * <p>The echo target is a narrow callback rather than a wider transport interface, so the
     * media transport contract stays unchanged and a transport that cannot echo simply drops the
     * probe.</p>
     *
     * @param hub           stream hub the client joins; required
     * @param runtime       active stream runtime; required
     * @param transport     transport owned by this call; required
     * @param listener      receiver of call events; required
     * @param remoteAddress address the call arrived on, or null when unknown
     * @param pongTarget    echo target for round-trip probes; required
     * @throws IllegalArgumentException if any required argument is null
     */
    public StreamCallHandler(StreamHub hub, StreamRuntime runtime, MediaTransport transport,
                             StreamCallListener listener, InetSocketAddress remoteAddress,
                             PongTarget pongTarget) {
        this(hub, runtime, transport, listener, remoteAddress, pongTarget, null);
    }

    /**
     * Creates a handler bound to the certificate identity of the TLS session.
     *
     * <p>When a certificate is present, it is the authoritative identity: a HELLO that declares a
     * different device id or fingerprint is rejected instead of joined, and a HELLO that declares
     * nothing still joins under the certificate identity. A null certificate keeps the legacy
     * plaintext behaviour, where the HELLO is taken at face value.</p>
     *
     * @param hub             stream hub the client joins; required
     * @param runtime         active stream runtime; required
     * @param transport       transport owned by this call; required
     * @param listener        receiver of call events; required
     * @param remoteAddress   address the call arrived on, or null when unknown
     * @param pongTarget      echo target for round-trip probes; required
     * @param peerCertificate identity proven by the handshake, or null when unverified
     * @throws IllegalArgumentException if any required argument is null
     */
    public StreamCallHandler(StreamHub hub, StreamRuntime runtime, MediaTransport transport,
                             StreamCallListener listener, InetSocketAddress remoteAddress,
                             PongTarget pongTarget, TlsCertificate peerCertificate) {
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
        if (pongTarget == null) {
            throw new IllegalArgumentException("pongTarget must not be null");
        }
        this.hub = hub;
        this.runtime = runtime;
        this.transport = transport;
        this.listener = listener;
        this.remoteAddress = remoteAddress;
        this.pongTarget = pongTarget;
        this.peerCertificate = peerCertificate;
    }

    /**
     * Handles one client control message; a second HELLO after joining is ignored.
     *
     * <p>A round-trip probe is answered outside the handler monitor: the echo shares the
     * transport's send lock with media, so a blocked echo must never stall a drain. Every other
     * payload keeps running under the monitor, because it may change the join state.</p>
     */
    public void onClientMessage(ClientMessage message) {
        if (message == null || isClosed()) {
            return;
        }
        if (message.getPayloadCase() == ClientMessage.PayloadCase.PING) {
            pongTarget.sendPong(message.getPing().getSeq(), message.getPing().getClientUptimeMs());
            return;
        }
        synchronized (this) {
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
                close();
                return false;
            }
            if (!session.isDetached()) {
                // The hub removes a mapping before it detaches the session, so the mapping can be
                // briefly absent. Retry instead of closing ahead of the session's owner, which
                // would release the transport before the takeover can report its reason.
                return false;
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
     * Returns the session key this handler attached with, or null before a successful join.
     */
    public synchronized ClientSessionKey getAttachedKey() {
        return key;
    }

    /**
     * Returns the address the call arrived on in socket form, or null when it is unknown.
     *
     * <p>The text is the same form the gRPC transport tracer reports, so server statistics can
     * join the per-call session counters with the per-address byte counters.</p>
     */
    public String getRemoteAddress() {
        return remoteAddress == null ? null : remoteAddress.toString();
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
        if (!bindDeclaredIdentity(hello)) {
            return;
        }
        pendingCapabilities = ScreenStreamProtoMapper.toClientCapabilities(hello.getCapabilities());
        if (pendingCapabilities.getCodecIds().isEmpty()) {
            // Old clients that declare nothing are assumed to support the H.264 baseline.
            pendingCapabilities = new ClientCapabilities(
                    Collections.singleton("h264"));
        }
        pendingJoin = true;
        tryJoin();
    }

    /**
     * Binds the declared HELLO identity to the certificate, or rejects the call.
     *
     * @return true when the HELLO may proceed
     */
    private boolean bindDeclaredIdentity(final Hello hello) {
        final String declaredId = emptyToNull(hello.getDeviceId());
        final String declaredFingerprint = emptyToNull(hello.getFingerprint());
        if (peerCertificate == null) {
            deviceId = declaredId;
            fingerprint = declaredFingerprint;
            return true;
        }
        if (declaredId != null && !declaredId.equals(peerCertificate.getDeviceId())) {
            rejectIdentity();
            return false;
        }
        if (declaredFingerprint != null
            && !declaredFingerprint.equals(peerCertificate.getFingerprint())) {
            rejectIdentity();
            return false;
        }
        deviceId = peerCertificate.getDeviceId();
        fingerprint = peerCertificate.getFingerprint();
        return true;
    }

    // Ends a call whose HELLO contradicts the certificate; the drain loop reports the failure.
    private void rejectIdentity() {
        pendingJoin = false;
        joinFailure = "identity_mismatch";
        close();
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
