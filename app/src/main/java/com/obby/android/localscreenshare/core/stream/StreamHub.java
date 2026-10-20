package com.obby.android.localscreenshare.core.stream;

import com.obby.android.localscreenshare.core.codec.CodecRegistration;
import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.codec.EncoderException;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.util.TimeSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hub that owns registered stream runtimes and fans out the active stream's
 * access units to per-client send sessions.
 *
 * <p><b>Single active stream.</b> Runtimes are registered by {@link StreamKey} and at most one of
 * them is active. Activation is refused while another stream still has sessions, so switching
 * streams is an explicit decision made by the caller rather than an implicit side effect of a
 * join. The hub listens to every registered runtime but forwards only events whose stream key
 * matches the active runtime, which lets a runtime that is still winding down deliver callbacks
 * without disturbing active sessions.</p>
 *
 * <p><b>Join outcomes.</b> A join is accepted only when the active runtime is
 * {@link RuntimeState#READY} with a published descriptor and the client supports that codec. A
 * client that cannot decode the active codec is never attached: the hub reports the lowest
 * {@code fallbackPriority} codec supported by both that client and every existing client, or
 * rejects the join when no common codec exists. Fallback is metadata only; the hub never switches
 * the active runtime or the active descriptor by itself.</p>
 *
 * <p><b>Locking and broadcast.</b> A private lock serializes compound changes of the runtime map,
 * the session map, the capabilities map and the active runtime reference; the maps themselves are
 * concurrent for the single-key reads used by {@link #drainOne(ClientSessionKey)},
 * {@link #getSession(ClientSessionKey)} and {@link #getSessionCount()}. Encoder callbacks snapshot
 * the session map under the lock and call session methods outside it. A join creates and primes
 * its new session under the lock before publishing it, then requests a join point from the runtime
 * outside the lock, so no shared session's transport is touched while the lock is held and a slow
 * client never blocks the encoder or another client. Per-session failures are isolated during
 * broadcast; a failure while draining removes that session from the hub and is rethrown to the
 * caller that drove the drain.</p>
 */
public final class StreamHub implements StreamRuntimeListener {

    private final CodecRegistry registry;
    private final StreamQueueLimits limits;
    private final TimeSource timeSource;

    private final Object lock = new Object();
    private final Map<StreamKey, StreamRuntime> runtimes = new ConcurrentHashMap<>();
    private final Map<ClientSessionKey, ClientSendSession> sessions = new ConcurrentHashMap<>();
    private final Map<ClientSessionKey, ClientCapabilities> capabilities =
            new ConcurrentHashMap<>();
    private StreamRuntime activeRuntime;

    /**
     * Creates a hub with default queue limits and the production monotonic clock.
     */
    public StreamHub(CodecRegistry registry) {
        this(registry, StreamQueueLimits.defaults(), TimeSource.MONOTONIC);
    }

    /**
     * Creates a hub with explicit queue limits and clock.
     *
     * <p>The limits and clock are copied to every session created by {@link #join}, so they must
     * be fixed before the first join.</p>
     *
     * @throws IllegalArgumentException if any argument is null
     */
    public StreamHub(CodecRegistry registry, StreamQueueLimits limits, TimeSource timeSource) {
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        if (limits == null) {
            throw new IllegalArgumentException("limits must not be null");
        }
        if (timeSource == null) {
            throw new IllegalArgumentException("timeSource must not be null");
        }
        this.registry = registry;
        this.limits = limits;
        this.timeSource = timeSource;
    }

    /**
     * Registers a runtime under its stream key and subscribes the hub to its events.
     *
     * <p>Registration uses {@link StreamRuntime#getStreamKey()} only, so a runtime whose
     * descriptor is not published yet can be registered. Registering does not activate the
     * runtime; call {@link #activate(StreamKey)} for that.</p>
     *
     * @throws IllegalArgumentException if {@code runtime} is null
     * @throws IllegalStateException    if the stream key is already registered
     */
    public void register(StreamRuntime runtime) {
        if (runtime == null) {
            throw new IllegalArgumentException("runtime must not be null");
        }
        StreamKey key = runtime.getStreamKey();
        synchronized (lock) {
            if (runtimes.containsKey(key)) {
                throw new IllegalStateException("stream already registered: " + key.getValue());
            }
            runtimes.put(key, runtime);
            runtime.addListener(this);
        }
    }

    /**
     * Removes a runtime and, when it was the active one, detaches every client session.
     *
     * <p>Idempotent: unregistering an unknown or null key does nothing. Sessions are removed from
     * the maps under the lock and detached outside it; detaching closes their transports.</p>
     */
    public void unregister(StreamKey key) {
        if (key == null) {
            return;
        }
        List<ClientSendSession> toDetach = new ArrayList<>();
        synchronized (lock) {
            StreamRuntime removed = runtimes.remove(key);
            if (removed != null) {
                removed.removeListener(this);
            }
            if (activeRuntime == removed) {
                toDetach.addAll(sessions.values());
                sessions.clear();
                capabilities.clear();
                activeRuntime = null;
            }
        }
        for (ClientSendSession session : toDetach) {
            session.detach();
        }
    }

    /**
     * Makes a registered runtime the active stream.
     *
     * <p>Activation does not require the runtime to be {@link RuntimeState#READY}; joins are gated
     * on readiness instead. Switching away from a stream that still has sessions is refused,
     * because those sessions are bound to the old stream's descriptor and transport.</p>
     *
     * @throws IllegalStateException if the key is null, not registered, or another stream still
     *                               has sessions
     */
    public void activate(StreamKey key) {
        synchronized (lock) {
            StreamRuntime runtime = key == null ? null : runtimes.get(key);
            if (runtime == null) {
                throw new IllegalStateException("stream not registered: " + key);
            }
            if (activeRuntime != null && activeRuntime != runtime && !sessions.isEmpty()) {
                throw new IllegalStateException(
                        "cannot switch active stream while sessions exist");
            }
            activeRuntime = runtime;
        }
    }

    /**
     * Returns the active runtime, or {@code null} when no stream is active.
     */
    public StreamRuntime getActiveStream() {
        synchronized (lock) {
            return activeRuntime;
        }
    }

    /**
     * Attempts to attach a client to the active stream.
     *
     * <p>The checks run in this order: no active stream and a stream key mismatch are
     * {@link JoinDecision.Status#REJECTED}; a runtime that is not {@link RuntimeState#READY} or
     * has no published descriptor is {@link JoinDecision.Status#NOT_READY}, which reserves nothing
     * and may be retried later; a duplicate session key is rejected. When the client supports the
     * active codec, a session is created, primed with the active descriptor as its pending
     * descriptor, stored with its capabilities and reported as
     * {@link JoinDecision.Status#ACCEPTED}. Otherwise the decision describes a required explicit
     * fallback or a rejection, and no session, capability or presence change is made.</p>
     *
     * <p>The join-point request is issued outside the lock after a successful attach, so the
     * encoder is asked for a random-access point new clients can start on. If that request throws,
     * the new session is removed and detached before the exception propagates.</p>
     *
     * @throws IllegalArgumentException if any argument is null
     */
    public JoinDecision join(ClientSessionKey key, ClientCapabilities caps,
                             MediaTransport transport) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (caps == null) {
            throw new IllegalArgumentException("capabilities must not be null");
        }
        if (transport == null) {
            throw new IllegalArgumentException("transport must not be null");
        }

        StreamRuntime toRequestJoin = null;
        ClientSendSession createdSession = null;
        JoinDecision decision;
        synchronized (lock) {
            StreamRuntime active = activeRuntime;
            if (active == null) {
                return JoinDecision.rejected("no active stream");
            }
            if (active.getState() != RuntimeState.READY) {
                return JoinDecision.notReady("active stream is not ready: " + active.getState());
            }
            StreamDescriptor activeDescriptor = active.getDescriptor();
            if (activeDescriptor == null) {
                return JoinDecision.notReady("active stream has no published descriptor");
            }
            if (!key.getStreamKey().equals(active.getStreamKey())) {
                return JoinDecision.rejected("stream key mismatch");
            }
            if (sessions.containsKey(key)) {
                return JoinDecision.rejected("session already exists");
            }

            if (caps.supports(activeDescriptor.getCodecId())) {
                createdSession =
                        new ClientSendSession(key, registry, limits, transport, timeSource);
                createdSession.onDescriptor(activeDescriptor);
                sessions.put(key, createdSession);
                capabilities.put(key, caps);
                toRequestJoin = active;
                decision = JoinDecision.accepted(createdSession);
            } else {
                decision = computeFallback(caps);
            }
        }

        if (toRequestJoin != null) {
            try {
                toRequestJoin.requestJoinPoint();
            } catch (RuntimeException e) {
                synchronized (lock) {
                    if (sessions.remove(key, createdSession)) {
                        capabilities.remove(key);
                    }
                }
                createdSession.detach();
                throw e;
            }
        }
        return decision;
    }

    /**
     * Removes a client's session and closes its transport.
     *
     * <p>Idempotent: a null or unknown key does nothing. The mapping is removed under the lock and
     * the session is detached outside it; the session closes the transport exactly once. The same
     * key may join again afterwards with a new transport.</p>
     */
    public void detach(ClientSessionKey key) {
        if (key == null) {
            return;
        }
        ClientSendSession session;
        synchronized (lock) {
            session = sessions.remove(key);
            if (session != null) {
                capabilities.remove(key);
            }
        }
        if (session != null) {
            session.detach();
        }
    }

    /**
     * Drains at most one descriptor or access unit to one client.
     *
     * <p>The lookup is lock-free for the common case; the actual send runs in the session without
     * holding the hub lock, so one client's slow transport does not block others. A session only
     * allows one concurrent drainer. On an I/O or unchecked failure the session has already
     * detached itself and closed its transport; the hub only removes the mapping and rethrows, so
     * the caller driving the drain learns about the failure without the hub closing the transport
     * a second time.</p>
     *
     * @return true when something was sent or a descriptor was pending, false when there was
     *         nothing to send or another thread is draining this session
     * @throws IOException in case of transport failure, after the session was removed
     */
    public boolean drainOne(ClientSessionKey key) throws IOException {
        if (key == null) {
            return false;
        }
        ClientSendSession session = sessions.get(key);
        if (session == null) {
            return false;
        }
        try {
            return session.drainOne();
        } catch (IOException | RuntimeException e) {
            synchronized (lock) {
                if (sessions.remove(key, session)) {
                    capabilities.remove(key);
                }
            }
            throw e;
        }
    }

    /**
     * Returns the session bound to a key, or null; intended for inspection and tests.
     */
    public ClientSendSession getSession(ClientSessionKey key) {
        return key == null ? null : sessions.get(key);
    }

    /**
     * Returns the number of attached sessions.
     */
    public int getSessionCount() {
        return sessions.size();
    }

    /**
     * Fans one access unit out to every attached session.
     *
     * <p>The unit is forwarded only when its stream key matches the active runtime; events from a
     * non-active runtime are ignored. The session list is snapshotted under the lock and offers
     * happen outside it, so a slow or overflowing client cannot block the encoder or the other
     * clients. Each session applies its own epoch and queue policy.</p>
     */
    @Override
    public void onAccessUnit(EncodedAccessUnit accessUnit) {
        if (accessUnit == null) {
            return;
        }
        List<ClientSendSession> snapshot;
        synchronized (lock) {
            StreamRuntime active = activeRuntime;
            if (active == null) {
                return;
            }
            if (!accessUnit.getStreamKey().equals(active.getStreamKey())) {
                return;
            }
            snapshot = new ArrayList<>(sessions.values());
        }
        for (ClientSendSession session : snapshot) {
            try {
                session.offer(accessUnit);
            } catch (RuntimeException ignored) {
                // isolate a single session's failure
            }
        }
    }

    /**
     * Hands a new active descriptor to every attached session.
     *
     * <p>Only notifications whose stream key matches the active runtime are applied; anything
     * else is ignored, which matters while a non-active runtime is still shutting down. Sessions
     * and transports are reused, not rebuilt; each session queues the descriptor so it is sent
     * before the first unit of the new epoch. One session rejecting or failing on the descriptor
     * does not affect the others.</p>
     */
    @Override
    public void onDescriptorChanged(StreamDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        List<ClientSendSession> snapshot;
        synchronized (lock) {
            StreamRuntime active = activeRuntime;
            if (active == null) {
                return;
            }
            if (!descriptor.getStreamKey().equals(active.getStreamKey())) {
                return;
            }
            snapshot = new ArrayList<>(sessions.values());
        }
        for (ClientSendSession session : snapshot) {
            try {
                session.onDescriptor(descriptor);
            } catch (RuntimeException ignored) {
                // isolate a single session's failure
            }
        }
    }

    /**
     * Detaches every client after the active encoder failed.
     *
     * <p>Only the active stream's failure is handled. Sessions and capabilities are removed under
     * the lock and detached outside it, with each transport close isolated. The active runtime is
     * kept in its failed state, so later joins report {@code NOT_READY} instead of silently
     * attaching to a dead stream.</p>
     */
    @Override
    public void onEncoderFailed(StreamKey streamKey, EncoderException failure) {
        if (streamKey == null) {
            return;
        }
        List<ClientSendSession> toDetach;
        synchronized (lock) {
            StreamRuntime active = activeRuntime;
            if (active == null || !streamKey.equals(active.getStreamKey())) {
                return;
            }
            toDetach = new ArrayList<>(sessions.values());
            sessions.clear();
            capabilities.clear();
        }
        for (ClientSendSession session : toDetach) {
            try {
                session.detach();
            } catch (RuntimeException ignored) {
                // A single session's transport close may throw; isolate it so the remaining
                // sessions are still detached and closed.
            }
        }
    }

    /**
     * Chooses the codec a client would have to be reconfigured to use.
     *
     * <p>A candidate must be registered, supported by the joining client and supported by every
     * client that already has a session. Among the candidates the registration with the lowest
     * {@code fallbackPriority} wins; ties are broken by codec id in lexicographic order so the
     * result is stable. The selected codec is reported only as metadata
     * ({@link JoinDecision#requiresFallback(String, String)}); nothing is switched and no session is
     * created. When no candidate qualifies the join is rejected.</p>
     */
    private JoinDecision computeFallback(ClientCapabilities candidateCaps) {
        String bestCodecId = null;
        int bestPriority = Integer.MAX_VALUE;
        for (CodecRegistration registration : registry.getRegistrations()) {
            String codecId = registration.getDescriptor().getCodecId();
            CodecCapability candidate = candidateCaps.getCapability(codecId);
            if (candidate == null) {
                continue;
            }
            boolean allCompatible = true;
            for (ClientCapabilities existing : capabilities.values()) {
                CodecCapability existingCap = existing.getCapability(codecId);
                if (existingCap == null || !candidate.isCompatibleWith(existingCap)) {
                    allCompatible = false;
                    break;
                }
            }
            if (!allCompatible) {
                continue;
            }
            int priority = registration.getFallbackPriority();
            if (bestCodecId == null || priority < bestPriority
                    || (priority == bestPriority && codecId.compareTo(bestCodecId) < 0)) {
                bestPriority = priority;
                bestCodecId = codecId;
            }
        }
        if (bestCodecId != null) {
            return JoinDecision.requiresFallback(bestCodecId,
                    "client does not support the active codec");
        }
        return JoinDecision.rejected("no common codec is supported by all clients");
    }
}
