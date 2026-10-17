package com.obby.android.localscreenshare.core.stream;

import com.obby.android.localscreenshare.core.codec.CodecRegistration;
import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hub that owns registered stream runtimes and fans out the active stream's
 * access units to per-client send sessions.
 *
 * <p>Only one stream may be active at a time. Compound state mutations happen
 * under a single lock while the maps themselves are concurrent; broadcast uses
 * a snapshot so a slow or overflowing client never blocks the encoder.</p>
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

    public StreamHub(CodecRegistry registry) {
        this(registry, StreamQueueLimits.defaults(), TimeSource.MONOTONIC);
    }

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

    public void register(StreamRuntime runtime) {
        if (runtime == null) {
            throw new IllegalArgumentException("runtime must not be null");
        }
        StreamKey key = runtime.getDescriptor().getStreamKey();
        synchronized (lock) {
            if (runtimes.containsKey(key)) {
                throw new IllegalStateException("stream already registered: " + key.getValue());
            }
            runtimes.put(key, runtime);
            runtime.addListener(this);
        }
    }

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

    public StreamRuntime getActiveStream() {
        synchronized (lock) {
            return activeRuntime;
        }
    }

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
        JoinDecision decision;
        synchronized (lock) {
            StreamRuntime active = activeRuntime;
            if (active == null) {
                return JoinDecision.rejected("no active stream");
            }
            StreamDescriptor activeDescriptor = active.getDescriptor();
            if (!key.getStreamKey().equals(activeDescriptor.getStreamKey())) {
                return JoinDecision.rejected("stream key mismatch");
            }
            if (sessions.containsKey(key)) {
                return JoinDecision.rejected("session already exists");
            }

            if (caps.supports(activeDescriptor.getCodecId())) {
                ClientSendSession session =
                        new ClientSendSession(key, registry, limits, transport, timeSource);
                session.onDescriptor(activeDescriptor);
                sessions.put(key, session);
                capabilities.put(key, caps);
                toRequestJoin = active;
                decision = JoinDecision.accepted(session);
            } else {
                decision = computeFallback(caps);
            }
        }

        if (toRequestJoin != null) {
            toRequestJoin.requestJoinPoint();
        }
        return decision;
    }

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
        } catch (IOException e) {
            synchronized (lock) {
                sessions.remove(key, session);
                capabilities.remove(key);
            }
            throw e;
        }
    }

    public ClientSendSession getSession(ClientSessionKey key) {
        return key == null ? null : sessions.get(key);
    }

    public int getSessionCount() {
        return sessions.size();
    }

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
            if (!accessUnit.getStreamKey().equals(active.getDescriptor().getStreamKey())) {
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
            if (!descriptor.getStreamKey().equals(active.getDescriptor().getStreamKey())) {
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

    private JoinDecision computeFallback(ClientCapabilities candidateCaps) {
        String bestCodecId = null;
        int bestPriority = Integer.MAX_VALUE;
        for (CodecRegistration registration : registry.getRegistrations()) {
            String codecId = registration.getDescriptor().getCodecId();
            if (!candidateCaps.supports(codecId)) {
                continue;
            }
            boolean allSupported = true;
            for (ClientCapabilities existing : capabilities.values()) {
                if (!existing.supports(codecId)) {
                    allSupported = false;
                    break;
                }
            }
            if (!allSupported) {
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
