package com.obby.android.localscreenshare.core.stream;

import com.obby.android.localscreenshare.core.codec.CodecPolicy;
import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.model.ClientState;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;

import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-client send session: buffers access units in a bounded queue and drains
 * them to a {@link MediaTransport}. Descriptors are always sent before the
 * first access unit of their epoch.
 *
 * <p>All session state is guarded by a single state lock. Draining takes a
 * separate {@code drainLock} so that a slow send never blocks {@link #offer}.
 * Sends happen outside the state lock.</p>
 */
public final class ClientSendSession {

    private final ClientSessionKey key;
    private final CodecRegistry registry;
    private final StreamQueueLimits limits;
    private final MediaTransport transport;
    private final TimeSource timeSource;

    private final Object stateLock = new Object();
    private final ReentrantLock drainLock = new ReentrantLock();

    private CodecPolicy policy;
    private StreamDescriptor currentDescriptor;
    private BoundedAccessUnitQueue queue;
    private StreamDescriptor pendingDescriptor;
    private ClientState state = ClientState.WAIT_CONFIG;
    private boolean detached;
    private boolean transportClosed;
    private long resyncCount;
    private boolean resyncEpisodeCounted;
    private long sentUnits;
    private long descriptorSends;
    private long droppedUnits;

    public ClientSendSession(ClientSessionKey key, CodecRegistry registry,
                             StreamQueueLimits limits, MediaTransport transport,
                             TimeSource timeSource) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        if (limits == null) {
            throw new IllegalArgumentException("limits must not be null");
        }
        if (transport == null) {
            throw new IllegalArgumentException("transport must not be null");
        }
        if (timeSource == null) {
            throw new IllegalArgumentException("timeSource must not be null");
        }
        this.key = key;
        this.registry = registry;
        this.limits = limits;
        this.transport = transport;
        this.timeSource = timeSource;
    }

    public ClientSessionKey getKey() {
        return key;
    }

    public String getClientId() {
        return key.getClientId();
    }

    public ClientState getState() {
        synchronized (stateLock) {
            return state;
        }
    }

    public StreamDescriptor getCurrentDescriptor() {
        synchronized (stateLock) {
            return currentDescriptor;
        }
    }

    public boolean isDetached() {
        synchronized (stateLock) {
            return detached;
        }
    }

    public boolean onDescriptor(StreamDescriptor descriptor) {
        synchronized (stateLock) {
            if (detached) {
                return false;
            }
            if (descriptor == null) {
                throw new IllegalArgumentException("descriptor must not be null");
            }
            CodecPolicy newPolicy = registry.require(descriptor.getCodecId()).getPolicy();

            if (currentDescriptor == null) {
                currentDescriptor = descriptor;
                policy = newPolicy;
                queue = new BoundedAccessUnitQueue(limits, newPolicy.getOverflowAction());
                pendingDescriptor = descriptor;
                state = ClientState.WAIT_RANDOM_ACCESS;
                resyncEpisodeCounted = false;
                return true;
            }

            int currentEpoch = currentDescriptor.getConfigEpoch();
            int newEpoch = descriptor.getConfigEpoch();
            if (newEpoch < currentEpoch) {
                return false;
            }
            if (newEpoch == currentEpoch) {
                if (descriptor.getConfigHash().equals(currentDescriptor.getConfigHash())) {
                    return false;
                }
                throw new IllegalArgumentException("same epoch with a different decode config");
            }

            droppedUnits = Math.max(droppedUnits, queue.droppedUnits());
            currentDescriptor = descriptor;
            policy = newPolicy;
            queue = new BoundedAccessUnitQueue(limits, newPolicy.getOverflowAction());
            pendingDescriptor = descriptor;
            state = ClientState.WAIT_RANDOM_ACCESS;
            resyncEpisodeCounted = false;
            return true;
        }
    }

    public QueueOfferResult offer(EncodedAccessUnit accessUnit) {
        synchronized (stateLock) {
            if (detached) {
                return QueueOfferResult.REJECTED_CLOSED;
            }
            if (accessUnit == null) {
                throw new IllegalArgumentException("access unit must not be null");
            }
            if (currentDescriptor == null) {
                return QueueOfferResult.REJECTED_NOT_READY;
            }
            if (!accessUnit.getStreamKey().equals(key.getStreamKey())
                    || accessUnit.getConfigEpoch() != currentDescriptor.getConfigEpoch()) {
                return QueueOfferResult.REJECTED_EPOCH;
            }
            if ((state == ClientState.WAIT_RANDOM_ACCESS || state == ClientState.RESYNCING)
                    && !policy.isJoinPoint(accessUnit)) {
                return QueueOfferResult.REJECTED_NOT_JOIN_POINT;
            }
            QueueOfferResult result = queue.offer(accessUnit, timeSource.nowMs());
            enterResyncIfNeededLocked();
            return result;
        }
    }

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
            boolean sendDescriptor = false;
            StreamDescriptor descriptorToSend = null;
            EncodedAccessUnit accessUnitToSend = null;

            synchronized (stateLock) {
                if (detached) {
                    return false;
                }
                if (pendingDescriptor != null) {
                    sendDescriptor = true;
                    descriptorToSend = pendingDescriptor;
                    pendingDescriptor = null;
                } else {
                    QueuePollResult polled = queue != null
                            ? queue.poll(timeSource.nowMs())
                            : QueuePollResult.empty();
                    if (polled.getAccessUnit() == null) {
                        if (polled.isResyncRequired()) {
                            enterResyncIfNeededLocked();
                        }
                        return false;
                    }
                    sendDescriptor = false;
                    accessUnitToSend = polled.getAccessUnit();
                }
            }

            if (sendDescriptor) {
                transport.sendDescriptor(descriptorToSend);
            } else {
                transport.sendAccessUnit(accessUnitToSend);
            }

            synchronized (stateLock) {
                if (!detached) {
                    if (sendDescriptor) {
                        descriptorSends++;
                    } else {
                        sentUnits++;
                        if (policy.isJoinPoint(accessUnitToSend)) {
                            state = ClientState.STREAMING;
                            resyncEpisodeCounted = false;
                        }
                    }
                }
            }
            return true;
        } catch (IOException e) {
            synchronized (stateLock) {
                detachInternalLocked();
            }
            throw e;
        } finally {
            drainLock.unlock();
        }
    }

    public ClientSendStats getStats() {
        synchronized (stateLock) {
            long queuedUnits = queue != null ? queue.size() : 0L;
            long queuedBytes = queue != null ? queue.bytes() : 0L;
            long dropped = Math.max(droppedUnits, queue != null ? queue.droppedUnits() : 0L);
            return new ClientSendStats(queuedUnits, queuedBytes, dropped, resyncCount,
                    sentUnits, descriptorSends);
        }
    }

    public void detach() {
        synchronized (stateLock) {
            detachInternalLocked();
        }
    }

    private void enterResyncIfNeededLocked() {
        if (queue != null && queue.isResyncRequired() && !resyncEpisodeCounted) {
            state = ClientState.RESYNCING;
            resyncCount++;
            resyncEpisodeCounted = true;
        }
    }

    private void detachInternalLocked() {
        detached = true;
        state = ClientState.CLOSED;
        if (queue != null) {
            queue.clear();
        }
        if (!transportClosed) {
            transportClosed = true;
            transport.close();
        }
    }
}
