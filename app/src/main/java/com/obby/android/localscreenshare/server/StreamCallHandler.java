package com.obby.android.localscreenshare.server;

import androidx.annotation.NonNull;

import com.obby.android.localscreenshare.authorization.AuthorizationGate;
import com.obby.android.localscreenshare.authorization.AuthorizationPolicy;
import com.obby.android.localscreenshare.core.audio.AudioHub;
import com.obby.android.localscreenshare.core.audio.AudioSendSession;
import com.obby.android.localscreenshare.core.audio.AudioTransport;
import com.obby.android.localscreenshare.core.audio.AudioCodecIds;
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
 *
 * <p><b>Audio.</b> When the call carries an {@link AudioHub}, a successful video join also attaches
 * an audio session for clients that declared an AAC decoder. Audio is drained first, at most
 * {@value #AUDIO_DRAIN_BURST} frames per call, and then one video item is drained, so audio never
 * starves video. An audio transport failure only detaches audio: the handler keeps the video
 * session and reports the reason for logs, because a failure in the optional track must not end the
 * stream the user is watching.</p>
 *
 * <p><b>Authorization.</b> With an {@link AuthorizationGate}, the HELLO is bound to the certificate
 * identity first and only then checked against the durable authorization set. A peer that is not
 * authorized is parked instead of joined: it has no session, receives no frame and no audio, and
 * one notice goes out so its viewer can show that it is waiting. {@link #drainOnce()} settles the
 * request - a grant joins on that same pass, a refusal or the elapsed window closes the call with
 * the reason, and a revoked grant ends an attached call within one pass. A grant made for one call
 * only is never re-checked, because it does not live in the durable set.</p>
 */
public final class StreamCallHandler {

    /**
     * Maximum audio frames drained before one video item in a single drain call.
     */
    private static final int AUDIO_DRAIN_BURST = 4;

    private final StreamHub hub;

    private final StreamRuntime runtime;

    private final MediaTransport transport;

    private final StreamCallListener listener;

    // Remote address the call arrived on; null when the caller does not attribute statistics to a
    // peer address (tests, or a call whose transport attributes carry none).
    private final InetSocketAddress remoteAddress;

    // Echo target for round-trip probes; NO_OP when the caller does not measure round trips.
    private final PongTarget pongTarget;

    // Approval gate; null when the call carries no authorization (tests, or a server without it).
    private final AuthorizationGate authorizationGate;

    // Output for the one "waiting for approval" notice; null when the call cannot send it.
    private final ApprovalTarget approvalTarget;

    // Certificate identity of the transport, or null on a plaintext call or in a test.
    private final TlsCertificate peerCertificate;

    private ClientSessionKey key;

    private ClientSendSession attachedSession;

    private ClientCapabilities pendingCapabilities;

    private String pendingClientId;

    // Identity the client declared in its HELLO; the session key stays client_id.
    private String deviceId;

    private String fingerprint;

    // Display name the peer declared for the approval prompt, or null when it declared none.
    private String deviceName;

    // True while the call waits for the server user to answer the approval prompt.
    private boolean pendingApproval;

    // Id of the waiting approval request; only meaningful while pendingApproval is true.
    private long approvalRequestId = -1L;

    // True when the current approval was granted for this call only, so revoking a durable
    // authorization must not end it.
    private boolean oneTimeGrant;

    private boolean pendingJoin;

    private boolean attached;

    private boolean closed;

    // Reason the join was rejected, or null when the client attached or never attempted to join.
    private String joinFailure;

    // Optional audio fan-out of the session; null when the call carries no audio.
    private final AudioHub audioHub;

    // Audio transport of the call, usually the same object as the media transport.
    private final AudioTransport audioTransport;

    private AudioSendSession audioSession;

    private String audioUnavailableReason;

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
        this(hub, runtime, transport, listener, remoteAddress, pongTarget, peerCertificate,
                null, null);
    }

    /**
     * Creates a handler that also drives the optional audio track of the call.
     *
     * <p>The audio hub and the audio transport are optional as a pair: a call without audio passes
     * null for both, and a client that does not declare an audio decoder simply never gets an audio
     * session. The transport is passed separately from the media transport because only the
     * production transport implements both; a test double usually implements one.</p>
     *
     * @param hub             stream hub the client joins; required
     * @param runtime         active stream runtime; required
     * @param transport       transport owned by this call; required
     * @param listener        receiver of call events; required
     * @param remoteAddress   address the call arrived on, or null when unknown
     * @param pongTarget      echo target for round-trip probes; required
     * @param peerCertificate identity proven by the handshake, or null when unverified
     * @param audioHub        audio fan-out of the session, or null when the call carries no audio
     * @param audioTransport  audio transport of the call, or null when the call carries no audio
     * @throws IllegalArgumentException if any required argument is null
     */
    public StreamCallHandler(StreamHub hub, StreamRuntime runtime, MediaTransport transport,
                             StreamCallListener listener, InetSocketAddress remoteAddress,
                             PongTarget pongTarget, TlsCertificate peerCertificate,
                             AudioHub audioHub, AudioTransport audioTransport) {
        this(hub, runtime, transport, listener, remoteAddress, pongTarget, peerCertificate,
                audioHub, audioTransport, null, null);
    }

    /**
     * Creates a handler that also asks the server user before letting the peer watch.
     *
     * <p>Without a gate the handler keeps the pre-authorization behaviour: the first HELLO joins.
     * With one, a peer whose identity is not authorized is parked after its HELLO is bound to the
     * certificate; the call then sends one notice (through {@code approvalTarget}), joins nothing,
     * and waits in {@link #drainOnce()} for an answer. A refusal or the elapsed approval window
     * closes the call with a reason the client can show, and revoking a durable authorization ends
     * an attached call the same way.</p>
     *
     * @param hub             stream hub the client joins; required
     * @param runtime         active stream runtime; required
     * @param transport       transport owned by this call; required
     * @param listener        receiver of call events; required
     * @param remoteAddress   address the call arrived on, or null when unknown
     * @param pongTarget      echo target for round-trip probes; required
     * @param peerCertificate identity proven by the handshake, or null when unverified
     * @param audioHub        audio fan-out of the session, or null when the call carries no audio
     * @param audioTransport  audio transport of the call, or null when the call carries no audio
     * @param authorizationGate approval gate, or null when this call is not gated
     * @param approvalTarget  output for the waiting notice, or null when it cannot be sent
     * @throws IllegalArgumentException if any required argument is null
     */
    public StreamCallHandler(StreamHub hub, StreamRuntime runtime, MediaTransport transport,
                             StreamCallListener listener, InetSocketAddress remoteAddress,
                             PongTarget pongTarget, TlsCertificate peerCertificate,
                             AudioHub audioHub, AudioTransport audioTransport,
                             AuthorizationGate authorizationGate, ApprovalTarget approvalTarget) {
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
        this.audioHub = audioHub;
        this.audioTransport = audioTransport;
        this.authorizationGate = authorizationGate;
        this.approvalTarget = approvalTarget;
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
            if (pendingApproval) {
                switch (settleApproval()) {
                    case PENDING:
                        return false;
                    case DENIED:
                    case TIMED_OUT:
                        return false;
                    default:
                        // Allowed: fall through and join on this very pass.
                        break;
                }
            }
            if (!attached) {
                tryJoin();
            }
            if (!attached) {
                return false;
            }
            if (!isStillAllowed()) {
                refuseAuthorization(AuthorizationPolicy.REASON_REVOKED);
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
        // Audio goes first, but only for a bounded burst, so a continuous audio track can never
        // starve video.
        boolean audioSent = false;
        for (int i = 0; i < AUDIO_DRAIN_BURST; i++) {
            if (!drainAudioOnce()) {
                break;
            }
            audioSent = true;
        }
        final boolean videoSent = hub.drainOne(sessionKey);
        return audioSent || videoSent;
    }

    /**
     * Drains at most one audio item.
     *
     * <p>A transport failure is contained here: the audio session is already detached by the hub,
     * the reason is kept for logs, and the method returns false so the video drain continues. That
     * is the whole point of a separate track - losing audio must not end the stream.</p>
     *
     * @return true when an audio message was sent
     */
    private boolean drainAudioOnce() {
        final AudioHub audio = audioHub;
        final ClientSessionKey sessionKey;
        final AudioSendSession session;
        synchronized (this) {
            if (closed || audio == null || audioSession == null) {
                return false;
            }
            sessionKey = key;
            session = audioSession;
        }
        if (sessionKey == null || audio.getSession(sessionKey) != session) {
            return false;
        }
        try {
            return audio.drainOne(sessionKey);
        } catch (IOException | RuntimeException e) {
            synchronized (this) {
                if (audioSession == session) {
                    audioSession = null;
                    audioUnavailableReason = "audio transport failed";
                }
            }
            return false;
        }
    }

    /**
     * Returns whether the handler has been closed and its session detached.
     */
    public synchronized boolean isClosed() {
        return closed;
    }

    /**
     * Returns why audio is not being sent on this call, or null when it is or was never attached.
     *
     * <p>Informational only: a client without an audio decoder and a failing audio transport look
     * the same to the video stream, which is exactly the isolation the design promises.</p>
     */
    public synchronized String getAudioUnavailableReason() {
        return audioUnavailableReason;
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
        if (pendingApproval) {
            pendingApproval = false;
            final AuthorizationGate gate = authorizationGate;
            if (gate != null) {
                gate.cancel(approvalRequestId);
            }
        }
        if (audioHub != null && key != null && audioSession != null) {
            audioHub.detachIfCurrent(key, audioSession);
        }
        audioSession = null;
        if (key != null) {
            hub.detachIfCurrent(key, attachedSession);
            key = null;
        }
        attachedSession = null;
        transport.close();
    }

    // Called under the handler monitor from onClientMessage or drainOnce; never called directly.
    private void onHello(Hello hello) {
        if (attached || pendingJoin || pendingApproval) {
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
        deviceName = emptyToNull(hello.getDeviceName());
        pendingCapabilities = ScreenStreamProtoMapper.toClientCapabilities(hello.getCapabilities());
        if (pendingCapabilities.getCodecIds().isEmpty()) {
            // Old clients that declare nothing are assumed to support the H.264 baseline.
            pendingCapabilities = new ClientCapabilities(
                    Collections.singleton("h264"));
        }
        final AuthorizationGate gate = authorizationGate;
        if (gate != null && !gate.isAuthorized(deviceId, fingerprint)) {
            // The peer is parked, not joined: no session, no frame, no audio until the user answers.
            pendingApproval = true;
            approvalRequestId = gate.request(deviceId, fingerprint, deviceName);
            sendApprovalNotice();
            return;
        }
        pendingJoin = true;
        tryJoin();
    }

    // Sends the one "waiting for approval" notice; a failing transport closes the parked call,
    // because nothing can reach the peer any more.
    private void sendApprovalNotice() {
        final ApprovalTarget target = approvalTarget;
        if (target == null) {
            return;
        }
        try {
            target.sendApprovalPending();
        } catch (RuntimeException e) {
            close();
        }
    }

    /**
     * Settles the waiting approval on a drain pass; called under the handler monitor.
     *
     * <p>An allowed request turns into a pending join on the same pass, so the first frame leaves
     * as soon as the answer arrives. A refusal closes the call with the reason the client shows;
     * the gate has already removed the request by the time this returns.</p>
     */
    @NonNull
    private AuthorizationGate.State settleApproval() {
        final AuthorizationGate.State state = authorizationGate.poll(approvalRequestId);
        switch (state) {
            case DENIED:
                refuseAuthorization(AuthorizationPolicy.REASON_DENIED);
                return state;
            case TIMED_OUT:
                refuseAuthorization(AuthorizationPolicy.REASON_TIMEOUT);
                return state;
            case ALLOWED_ONCE:
                oneTimeGrant = true;
                break;
            case ALLOWED_ALWAYS:
            default:
                oneTimeGrant = false;
                break;
        }
        pendingApproval = false;
        approvalRequestId = -1L;
        pendingJoin = true;
        return state;
    }

    /**
     * Returns whether this call may still be attached; called under the handler monitor.
     *
     * <p>A one-time grant is never re-checked: it does not live in the durable set, so revoking
     * another device must not end it. A grant that disappears does: the next drain ends the call,
     * which is what makes a revocation take effect immediately instead of on the next reconnect.</p>
     */
    private boolean isStillAllowed() {
        if (oneTimeGrant) {
            return true;
        }
        final AuthorizationGate gate = authorizationGate;
        return gate == null || gate.isAuthorized(deviceId, fingerprint);
    }

    // Ends a call whose authorization was refused or withdrawn; called under the handler monitor.
    private void refuseAuthorization(@NonNull final String reason) {
        pendingApproval = false;
        approvalRequestId = -1L;
        pendingJoin = false;
        joinFailure = reason;
        if (pendingClientId != null) {
            listener.onJoinRejected(new ClientSessionKey(runtime.getStreamKey(), pendingClientId),
                transportId(), reason);
        }
        close();
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
                attachAudio(candidate);
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
     * Attaches the audio session after a successful video join; called under the handler monitor.
     *
     * <p>Audio is optional by construction: a call without an audio hub, a client that declared no
     * audio decoder or an audio join that fails all leave the video session untouched. The reason
     * is recorded for logs so "the viewer has no sound" can be answered without guessing.</p>
     */
    private void attachAudio(ClientSessionKey sessionKey) {
        if (audioHub == null || audioTransport == null) {
            return;
        }
        if (!pendingCapabilities.supportsAudio(AudioCodecIds.AAC)) {
            audioUnavailableReason = "client does not declare an audio decoder";
            return;
        }
        try {
            if (audioHub.join(sessionKey, pendingCapabilities, audioTransport)) {
                audioSession = audioHub.getSession(sessionKey);
            } else {
                audioUnavailableReason = "audio join refused";
            }
        } catch (RuntimeException e) {
            audioUnavailableReason = "audio join failed";
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
