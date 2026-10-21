package com.obby.android.localscreenshare.core.audio;

import com.obby.android.localscreenshare.core.model.AudioAccessUnit;
import com.obby.android.localscreenshare.core.model.AudioDescriptor;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-client audio send session: a small bounded queue drained to one {@link AudioTransport}.
 *
 * <p>Audio is deliberately not a {@code ClientSendSession}: it has no key frames, no join points
 * and no resynchronisation, so reusing that state machine would either drop every frame or never
 * leave {@code RESYNCING}. This session is much smaller. It buffers at most
 * {@value #DEFAULT_MAX_FRAMES} frames or 300 ms of audio, whichever comes first, and drops the
 * oldest frame when a new one would exceed either bound. A descriptor always precedes the first
 * frame of its epoch because it is kept as a pending item and drained first.</p>
 *
 * <p><b>Failure isolation.</b> A transport failure detaches this session and is rethrown to the
 * caller that drove the drain; the hub removes the mapping. A transport that reports "not ready"
 * does not fail: the frame is dropped and counted, which is what keeps audio latency bounded in
 * front of a client that cannot keep up. Detaching never touches the transport, because the video
 * session of the same call owns its lifetime.</p>
 */
public final class AudioSendSession {

    /**
     * Default maximum number of queued frames.
     */
    public static final int DEFAULT_MAX_FRAMES = 32;

    /**
     * Default maximum queued duration in milliseconds.
     */
    public static final long DEFAULT_MAX_QUEUED_MS = 300L;

    private final ClientSessionKey key;

    private final AudioTransport transport;

    private final int maxFrames;

    private final long maxQueuedMs;

    private final Object stateLock = new Object();

    private final ReentrantLock drainLock = new ReentrantLock();

    private final Deque<AudioAccessUnit> queue = new ArrayDeque<>();

    private AudioDescriptor currentDescriptor;

    private AudioDescriptor pendingDescriptor;

    private boolean detached;

    private long sentFrames;

    private long droppedFrames;

    private long descriptorSends;

    /**
     * Creates a session with the default bounds.
     *
     * @param key       client and stream identity; required
     * @param transport audio transport of the call; required
     * @throws IllegalArgumentException if any argument is null
     */
    public AudioSendSession(ClientSessionKey key, AudioTransport transport) {
        this(key, transport, DEFAULT_MAX_FRAMES, DEFAULT_MAX_QUEUED_MS);
    }

    /**
     * Creates a session with explicit bounds.
     *
     * @param key         client and stream identity; required
     * @param transport   audio transport of the call; required
     * @param maxFrames   maximum queued frames; must be positive
     * @param maxQueuedMs maximum queued duration in milliseconds; must be positive
     * @throws IllegalArgumentException if an argument is null or a bound is not positive
     */
    public AudioSendSession(ClientSessionKey key, AudioTransport transport, int maxFrames,
                            long maxQueuedMs) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (transport == null) {
            throw new IllegalArgumentException("transport must not be null");
        }
        if (maxFrames <= 0) {
            throw new IllegalArgumentException("maxFrames must be positive");
        }
        if (maxQueuedMs <= 0) {
            throw new IllegalArgumentException("maxQueuedMs must be positive");
        }
        this.key = key;
        this.transport = transport;
        this.maxFrames = maxFrames;
        this.maxQueuedMs = maxQueuedMs;
    }

    /**
     * Returns the client and stream identity of this session.
     */
    public ClientSessionKey getKey() {
        return key;
    }

    /**
     * Returns whether the session was detached; a detached session never becomes active again.
     */
    public boolean isDetached() {
        synchronized (stateLock) {
            return detached;
        }
    }

    /**
     * Returns the descriptor of the current epoch, or null before the first one.
     */
    public AudioDescriptor getCurrentDescriptor() {
        synchronized (stateLock) {
            return currentDescriptor;
        }
    }

    /**
     * Accepts a descriptor and queues it ahead of the next frame.
     *
     * <p>An older epoch is ignored, the same epoch with the same config hash is a duplicate, and the
     * same epoch with a different hash is a protocol violation. A newer epoch always wins: the
     * queued frames of the old epoch are discarded because a decoder that reconfigured cannot
     * consume them.</p>
     *
     * @return true when the descriptor was applied
     * @throws IllegalArgumentException if {@code descriptor} is null or reuses the current epoch
     *                                  with a different decode configuration
     */
    public boolean onDescriptor(AudioDescriptor descriptor) {
        synchronized (stateLock) {
            if (detached) {
                return false;
            }
            if (descriptor == null) {
                throw new IllegalArgumentException("descriptor must not be null");
            }
            if (currentDescriptor == null) {
                currentDescriptor = descriptor;
                pendingDescriptor = descriptor;
                return true;
            }
            final int currentEpoch = currentDescriptor.getConfigEpoch();
            final int newEpoch = descriptor.getConfigEpoch();
            if (newEpoch < currentEpoch) {
                return false;
            }
            if (newEpoch == currentEpoch) {
                if (descriptor.getConfigHash().equals(currentDescriptor.getConfigHash())) {
                    return false;
                }
                throw new IllegalArgumentException(
                        "same epoch with a different audio decode config");
            }
            currentDescriptor = descriptor;
            pendingDescriptor = descriptor;
            droppedFrames += queue.size();
            queue.clear();
            return true;
        }
    }

    /**
     * Offers one frame for transmission.
     *
     * <p>A detached session, a session without a descriptor and a frame of another epoch are
     * rejected and counted as dropped. An accepted frame first evicts the oldest frames until both
     * bounds admit it, so the queue never grows without a delay bound.</p>
     *
     * @return true when the frame is queued
     */
    public boolean offer(AudioAccessUnit accessUnit) {
        if (accessUnit == null) {
            throw new IllegalArgumentException("accessUnit must not be null");
        }
        synchronized (stateLock) {
            if (detached || currentDescriptor == null) {
                droppedFrames++;
                return false;
            }
            if (accessUnit.getConfigEpoch() != currentDescriptor.getConfigEpoch()) {
                droppedFrames++;
                return false;
            }
            while (!queue.isEmpty()
                    && (queue.size() + 1 > maxFrames
                    || queuedMsLocked() + frameDurationMsLocked() > maxQueuedMs)) {
                queue.pollFirst();
                droppedFrames++;
            }
            if (queue.size() + 1 > maxFrames) {
                // The bound cannot admit even one frame; drop the incoming one instead.
                droppedFrames++;
                return false;
            }
            queue.addLast(accessUnit);
            return true;
        }
    }

    /**
     * Sends at most one item: a pending descriptor first, otherwise one queued frame.
     *
     * @return true when a message was handed to the transport
     * @throws IOException in case of a transport failure, after the session detached itself
     */
    public boolean drainOne() throws IOException {
        synchronized (stateLock) {
            if (detached) {
                return false;
            }
        }
        if (!drainLock.tryLock()) {
            return false;
        }
        try {
            final AudioDescriptor descriptorToSend;
            final AudioAccessUnit unitToSend;
            synchronized (stateLock) {
                if (detached) {
                    return false;
                }
                if (pendingDescriptor != null) {
                    descriptorToSend = pendingDescriptor;
                    unitToSend = null;
                } else {
                    descriptorToSend = null;
                    unitToSend = queue.pollFirst();
                    if (unitToSend == null) {
                        return false;
                    }
                }
            }
            if (descriptorToSend != null) {
                final boolean sent = transport.sendAudioDescriptor(descriptorToSend);
                synchronized (stateLock) {
                    if (sent && !detached) {
                        descriptorSends++;
                        pendingDescriptor = null;
                    }
                }
                return sent;
            }
            final boolean sent = transport.sendAudioUnit(unitToSend);
            synchronized (stateLock) {
                if (!detached) {
                    if (sent) {
                        sentFrames++;
                    } else {
                        droppedFrames++;
                    }
                }
            }
            return sent;
        } catch (IOException | RuntimeException e) {
            synchronized (stateLock) {
                detached = true;
                queue.clear();
            }
            throw e;
        } finally {
            drainLock.unlock();
        }
    }

    /**
     * Detaches the session and clears its queue; idempotent and thread-safe.
     *
     * <p>The transport is not touched: it belongs to the video session of the same call.</p>
     */
    public void detach() {
        synchronized (stateLock) {
            detached = true;
            droppedFrames += queue.size();
            queue.clear();
            pendingDescriptor = null;
        }
    }

    /**
     * Returns a consistent snapshot of the counters.
     */
    public AudioSendStats getStats() {
        synchronized (stateLock) {
            return new AudioSendStats(queue.size(), queuedMsLocked(), sentFrames, droppedFrames,
                    descriptorSends);
        }
    }

    private long frameDurationMsLocked() {
        return currentDescriptor == null ? 0L : Math.max(1L,
                currentDescriptor.getFrameDurationUs() / 1_000L);
    }

    private long queuedMsLocked() {
        if (currentDescriptor == null) {
            return 0L;
        }
        return queue.size() * Math.max(1L, currentDescriptor.getFrameDurationUs() / 1_000L);
    }
}
