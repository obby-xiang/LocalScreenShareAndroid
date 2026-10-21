package com.obby.android.localscreenshare.core.audio;

import com.obby.android.localscreenshare.core.model.AudioAccessUnit;
import com.obby.android.localscreenshare.core.model.AudioDescriptor;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.stream.ClientCapabilities;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fan-out of one encoded audio track to every client that declared an AAC decoder.
 *
 * <p>The audio hub is the sibling of the video {@code StreamHub}, not part of it: one capture and
 * one encoder produce a single track, and each client gets an independent {@link AudioSendSession}
 * with its own bounded queue. That is why a slow client only drops its own audio, and why nothing
 * here can disturb the video stream's join, takeover or resynchronisation semantics.</p>
 *
 * <p><b>Descriptor first.</b> The current descriptor is kept by the hub; a client that joins after
 * the descriptor was published receives it as its first pending item, and a format change is pushed
 * to every session ahead of the first frame of the new epoch.</p>
 *
 * <p><b>Isolation.</b> A failing session never fails a broadcast: offers are made outside the hub
 * lock and each session's exception is swallowed. A transport failure during a drain removes that
 * session from the hub and is rethrown to the caller that drove the drain, which is the only place
 * that can decide what to log; the video session still owns the transport and detects its own
 * failure independently.</p>
 */
public final class AudioHub {

    private final Object lock = new Object();

    private final Map<ClientSessionKey, AudioSendSession> sessions = new ConcurrentHashMap<>();

    private AudioDescriptor descriptor;

    /**
     * Attaches a client and queues the current descriptor for it.
     *
     * <p>A client that does not declare a supported audio decoder is refused without touching the
     * video session. A join for an already attached key replaces the old session, mirroring the
     * video hub's takeover, so a reconnect is never rejected for holding a duplicate key.</p>
     *
     * @param key          client and stream identity; required
     * @param capabilities client capabilities declared in HELLO; required
     * @param transport    audio transport of the call; required
     * @return true when a session was attached
     * @throws IllegalArgumentException if any argument is null
     */
    public boolean join(ClientSessionKey key, ClientCapabilities capabilities,
                        AudioTransport transport) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (capabilities == null) {
            throw new IllegalArgumentException("capabilities must not be null");
        }
        if (transport == null) {
            throw new IllegalArgumentException("transport must not be null");
        }
        final AudioDescriptor current;
        synchronized (lock) {
            current = descriptor;
        }
        final String codecId = current != null ? current.getCodecId() : AudioCodecIds.AAC;
        if (!capabilities.supportsAudio(codecId)) {
            return false;
        }
        final AudioSendSession session = new AudioSendSession(key, transport);
        AudioSendSession replaced;
        synchronized (lock) {
            replaced = sessions.put(key, session);
            if (descriptor != null) {
                session.onDescriptor(descriptor);
            }
        }
        if (replaced != null) {
            replaced.detach();
        }
        return true;
    }

    /**
     * Removes a client's session only while the key still maps to {@code session}.
     *
     * <p>This is the detach a call handler needs: after a takeover the key maps to the newer
     * session, and an old handler that ends must not detach the replacement. Idempotent.</p>
     */
    public void detachIfCurrent(ClientSessionKey key, AudioSendSession session) {
        if (key == null || session == null) {
            return;
        }
        final boolean removed;
        synchronized (lock) {
            removed = sessions.remove(key, session);
        }
        if (removed) {
            session.detach();
        }
    }

    /**
     * Removes a client's session and clears its queue; idempotent.
     */
    public void detach(ClientSessionKey key) {
        if (key == null) {
            return;
        }
        final AudioSendSession session;
        synchronized (lock) {
            session = sessions.remove(key);
        }
        if (session != null) {
            session.detach();
        }
    }

    /**
     * Returns the session bound to a key, or null.
     */
    public AudioSendSession getSession(ClientSessionKey key) {
        return key == null ? null : sessions.get(key);
    }

    /**
     * Returns the number of attached audio sessions.
     */
    public int getSessionCount() {
        return sessions.size();
    }

    /**
     * Returns the descriptor currently published, or null when audio is not running.
     */
    public AudioDescriptor getDescriptor() {
        synchronized (lock) {
            return descriptor;
        }
    }

    /**
     * Publishes a descriptor to the hub and every attached session.
     *
     * <p>The same epoch with the same configuration is a duplicate and changes nothing; a newer
     * epoch is pushed ahead of the next frame of each session. A session that rejects the
     * descriptor does not affect the others.</p>
     */
    public void onDescriptor(AudioDescriptor newDescriptor) {
        if (newDescriptor == null) {
            return;
        }
        final List<AudioSendSession> snapshot;
        synchronized (lock) {
            final AudioDescriptor current = descriptor;
            if (current != null) {
                if (newDescriptor.getConfigEpoch() < current.getConfigEpoch()) {
                    return;
                }
                if (newDescriptor.getConfigEpoch() == current.getConfigEpoch()
                        && newDescriptor.sameDecodeConfig(current)) {
                    return;
                }
            }
            descriptor = newDescriptor;
            snapshot = new ArrayList<>(sessions.values());
        }
        for (AudioSendSession session : snapshot) {
            try {
                session.onDescriptor(newDescriptor);
            } catch (RuntimeException ignored) {
                // Isolate a single session's failure; the others keep receiving audio.
            }
        }
    }

    /**
     * Broadcasts one audio frame to every attached session.
     *
     * <p>Sessions are snapshotted under the lock and offered outside it, so a full queue on one
     * client cannot block the encoder or another client.</p>
     */
    public void onAccessUnit(AudioAccessUnit accessUnit) {
        if (accessUnit == null) {
            return;
        }
        final List<AudioSendSession> snapshot;
        synchronized (lock) {
            if (descriptor == null) {
                return;
            }
            if (accessUnit.getConfigEpoch() != descriptor.getConfigEpoch()) {
                return;
            }
            snapshot = new ArrayList<>(sessions.values());
        }
        for (AudioSendSession session : snapshot) {
            try {
                session.offer(accessUnit);
            } catch (RuntimeException ignored) {
                // Isolate a single session's failure.
            }
        }
    }

    /**
     * Drains at most one descriptor or frame to one client.
     *
     * @return true when a message was sent
     * @throws IOException in case of a transport failure, after the session was removed
     */
    public boolean drainOne(ClientSessionKey key) throws IOException {
        if (key == null) {
            return false;
        }
        final AudioSendSession session = sessions.get(key);
        if (session == null) {
            return false;
        }
        try {
            return session.drainOne();
        } catch (IOException | RuntimeException e) {
            synchronized (lock) {
                sessions.remove(key, session);
            }
            throw e;
        }
    }

    /**
     * Returns the aggregate counters of every attached session, ordered deterministically.
     */
    public AudioSendStats getAggregateStats() {
        AudioSendStats total = AudioSendStats.EMPTY;
        for (AudioSendSession session : snapshotSessions()) {
            total = total.plus(session.getStats());
        }
        return total;
    }

    /**
     * Returns one snapshot per attached session, ordered by client id and then stream key.
     */
    public List<AudioSendSession> snapshotSessions() {
        final List<AudioSendSession> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(sessions.values());
        }
        snapshot.sort(Comparator.comparing(
                        (AudioSendSession session) -> session.getKey().getClientId())
                .thenComparing(session -> session.getKey().getStreamKey().getValue()));
        return snapshot;
    }
}
