package com.obby.android.localscreenshare.core.stream;

import com.obby.android.localscreenshare.core.codec.CodecPolicy;
import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.model.ClientState;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.metrics.PipelineMetrics;
import com.obby.android.localscreenshare.core.util.TimeSource;

import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-client send session: buffers access units in a bounded queue and drains
 * them to a {@link MediaTransport}. Descriptors are always sent before the
 * first access unit of their epoch.
 *
 * <p><b>State machine.</b> A session starts in {@link ClientState#WAIT_CONFIG}. Accepting the
 * first descriptor creates the queue and moves to {@link ClientState#WAIT_RANDOM_ACCESS}; a newer
 * epoch discards the old queue and its pending units and returns to the same waiting state. While
 * waiting or resynchronizing, only a validated join point is accepted, and a join point that is
 * actually sent moves the session to {@link ClientState#STREAMING}. For reference-dependent
 * codecs an overflow, an expired unit or an oversized unit sets the queue's resync flag, moves the
 * session to {@link ClientState#RESYNCING} and counts one resync episode; independently decodable
 * codecs keep the latest frame and never enter resync for throughput reasons. Detaching is
 * terminal and moves the session to {@link ClientState#CLOSED}.</p>
 *
 * <p><b>Locking.</b> All session state is guarded by a single state lock. Draining takes a
 * separate {@code drainLock} so that a slow send never blocks {@link #offer}: at most one drainer
 * runs at a time, the queue poll happens under the state lock, the transport send happens outside
 * every session lock, and counters and state are updated under the state lock afterwards, only if
 * the session was not detached meanwhile. A blocked transport therefore holds no lock that
 * {@code offer} needs.</p>
 *
 * <p><b>Failure.</b> Any transport failure detaches the session - clearing the queue, moving to
 * {@code CLOSED} and closing the transport exactly once - and is rethrown so the owning
 * {@link StreamHub} can drop the session. A detached session accepts no units and never drains
 * again. Detaching with a reason ({@link #detach(String)}) terminates the transport abnormally
 * instead of only closing it, which is what tells a peer whose session was replaced by a newer
 * connection that the stream is over.</p>
 */
public final class ClientSendSession {

    /**
     * Marker for a residency sample that could not be computed.
     */
    private static final long LONG_UNSET = Long.MIN_VALUE;

    private final ClientSessionKey key;
    private final CodecRegistry registry;
    private final StreamQueueLimits limits;
    private final MediaTransport transport;
    private final TimeSource timeSource;
    private final PipelineMetrics metrics;

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

    /**
     * Creates a session that has not received a descriptor yet.
     *
     * @param key        client and stream identity; required
     * @param registry   codec registry used to resolve the per-codec policy; required
     * @param limits     queue bounds applied to each epoch's queue; required
     * @param transport  transport owned by this session; required
     * @param timeSource clock used for queue residency and drops; required
     * @throws IllegalArgumentException if any argument is null
     */
    public ClientSendSession(ClientSessionKey key, CodecRegistry registry,
                             StreamQueueLimits limits, MediaTransport transport,
                             TimeSource timeSource) {
        this(key, registry, limits, transport, timeSource, PipelineMetrics.NONE);
    }

    /**
     * Creates a session that reports its send-stage metrics to {@code metrics}.
     *
     * <p>The scope receives the duration of one transport send call, the queue depth observed by
     * the drain loop and the residency of the unit that was just polled. Probes read the clock
     * once per timestamp and run outside the state lock, so a measured session keeps the existing
     * ordering guarantees and a slow transport still holds no lock that {@link #offer} needs.</p>
     *
     * @param key         client and stream identity; required
     * @param registry    codec registry used to resolve the per-codec policy; required
     * @param limits      queue bounds applied to each epoch's queue; required
     * @param transport   transport owned by this session; required
     * @param timeSource  clock used for queue residency and drops; required
     * @param metrics     metric scope of this session; required, may be
     *                    {@link PipelineMetrics#NONE}
     * @throws IllegalArgumentException if any argument is null
     */
    public ClientSendSession(ClientSessionKey key, CodecRegistry registry,
                             StreamQueueLimits limits, MediaTransport transport,
                             TimeSource timeSource, PipelineMetrics metrics) {
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
        if (metrics == null) {
            throw new IllegalArgumentException("metrics must not be null");
        }
        this.key = key;
        this.registry = registry;
        this.limits = limits;
        this.transport = transport;
        this.timeSource = timeSource;
        this.metrics = metrics;
    }

    /**
     * Returns the client and stream identity of this session.
     */
    public ClientSessionKey getKey() {
        return key;
    }

    /**
     * Returns the client id part of the session key.
     */
    public String getClientId() {
        return key.getClientId();
    }

    /**
     * Returns the current session state, read atomically.
     */
    public ClientState getState() {
        synchronized (stateLock) {
            return state;
        }
    }

    /**
     * Returns the descriptor of the current epoch, or {@code null} before the first one.
     */
    public StreamDescriptor getCurrentDescriptor() {
        synchronized (stateLock) {
            return currentDescriptor;
        }
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
     * Accepts a descriptor and prepares the session for its epoch.
     *
     * <p>A descriptor is validated against the current one before anything is replaced: an older
     * epoch is ignored, the same epoch with the same config hash is a duplicate, and the same epoch
     * with a different hash is a protocol violation. A newer epoch is always accepted and
     * discards the old queue and any unit buffered for it; the new descriptor is queued as the
     * pending descriptor, so {@link #drainOne()} sends it before the first unit of the new epoch.
     * The codec policy and queue bounds are taken from the registry entry of the descriptor's
     * codec.</p>
     *
     * <p>The codec lookup runs while the state lock is held; an unknown codec id propagates the
     * registry's exception.</p>
     *
     * @return true when the descriptor was applied, false for a duplicate or stale descriptor or a
     *         detached session
     * @throws IllegalArgumentException if {@code descriptor} is null or reuses the current epoch
     *                                  with a different decode configuration
     */
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

    /**
     * Offers an access unit for transmission.
     *
     * <p>Checks run in this order: a detached session returns
     * {@link QueueOfferResult#REJECTED_CLOSED}; a null unit throws; a session without a descriptor
     * returns {@link QueueOfferResult#REJECTED_NOT_READY}; a unit from another stream or another
     * config epoch returns {@link QueueOfferResult#REJECTED_EPOCH}, including ordinary units that
     * are not join points; while the session waits for a random-access point or resynchronizes a
     * non-join unit returns {@link QueueOfferResult#REJECTED_NOT_JOIN_POINT}. Accepted units are
     * passed to the queue, and any resync condition the queue reports moves the session to
     * {@code RESYNCING} and counts one resync episode.</p>
     *
     * <p>The whole operation runs under the state lock and never takes the drain lock, so a
     * transport blocked in {@link #drainOne()} cannot block this call.</p>
     *
     * @return the queue's outcome, or one of the session-level rejections described above
     * @throws IllegalArgumentException if {@code accessUnit} is null
     */
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

    /**
     * Sends at most one item: a pending descriptor first, otherwise one queued access unit.
     *
     * <p>Returns {@code false} immediately when another thread already holds the drain lock, so
     * callers can treat the session as busy rather than blocking. The pending descriptor is taken
     * under the state lock and always wins over queued units, which is what guarantees that every
     * epoch's descriptor is sent before its first unit. The transport call is made outside the
     * state lock. After a successful send the counters are updated under the state lock only if
     * the session is still attached, and the join-point transition is applied only if additionally
     * the epoch the unit was polled for is still current.</p>
     *
     * <p>When the queue reports that a resync is required without returning a unit, the session
     * moves to {@code RESYNCING} and false is returned. Any transport failure detaches the session
     * and is rethrown.</p>
     *
     * @return true when something was sent, false when there was nothing to send, the session is
     *         detached or another thread is draining
     * @throws IOException in case of transport failure, after the session detached itself
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
            boolean sendDescriptor = false;
            StreamDescriptor descriptorToSend = null;
            EncodedAccessUnit accessUnitToSend = null;
            int sentEpoch = -1;
            final boolean measured = metrics.isEnabled();
            long queueDepth = 0L;
            long residencyNs = LONG_UNSET;

            synchronized (stateLock) {
                if (detached) {
                    return false;
                }
                if (pendingDescriptor != null) {
                    sendDescriptor = true;
                    descriptorToSend = pendingDescriptor;
                    pendingDescriptor = null;
                } else {
                    final long nowMs = timeSource.nowMs();
                    QueuePollResult polled = queue != null
                            ? queue.poll(nowMs)
                            : QueuePollResult.empty();
                    if (polled.getAccessUnit() == null) {
                        if (polled.isResyncRequired()) {
                            enterResyncIfNeededLocked();
                        }
                        return false;
                    }
                    sendDescriptor = false;
                    accessUnitToSend = polled.getAccessUnit();
                    sentEpoch = currentDescriptor != null
                            ? currentDescriptor.getConfigEpoch()
                            : -1;
                    if (measured) {
                        queueDepth = queue.size();
                        if (polled.getEnqueuedMs() != QueuePollResult.UNKNOWN_ENQUEUED_MS) {
                            residencyNs = (nowMs - polled.getEnqueuedMs()) * 1_000_000L;
                        }
                    }
                }
            }

            if (sendDescriptor) {
                transport.sendDescriptor(descriptorToSend);
            } else if (measured) {
                final long sendStartNs = metrics.nowNs();
                transport.sendAccessUnit(accessUnitToSend);
                metrics.record(PipelineMetrics.Window.SEND, metrics.nowNs() - sendStartNs);
                metrics.record(PipelineMetrics.Window.QUEUE_DEPTH, queueDepth);
                if (residencyNs != LONG_UNSET) {
                    metrics.record(PipelineMetrics.Window.QUEUE_RESIDENCY, residencyNs);
                }
            } else {
                transport.sendAccessUnit(accessUnitToSend);
            }

            synchronized (stateLock) {
                if (!detached) {
                    if (sendDescriptor) {
                        descriptorSends++;
                    } else {
                        sentUnits++;
                        boolean epochStillCurrent = currentDescriptor != null
                                && currentDescriptor.getConfigEpoch() == sentEpoch;
                        if (epochStillCurrent && policy.isJoinPoint(accessUnitToSend)) {
                            state = ClientState.STREAMING;
                            resyncEpisodeCounted = false;
                        }
                    }
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            boolean terminate;
            synchronized (stateLock) {
                terminate = detachInternalLocked();
            }
            if (terminate) {
                transport.close();
            }
            throw e;
        } finally {
            drainLock.unlock();
        }
    }

    /**
     * Returns a consistent snapshot of the session counters.
     *
     * <p>Queued values describe the current queue; the dropped counter includes units dropped by
     * queues of previous epochs, so the value never decreases across an epoch change.
     * {@code resyncCount} counts resync episodes, and {@code sentUnits} and
     * {@code descriptorSends} count successfully completed transport calls.</p>
     */
    public ClientSendStats getStats() {
        synchronized (stateLock) {
            long queuedUnits = queue != null ? queue.size() : 0L;
            long queuedBytes = queue != null ? queue.bytes() : 0L;
            long dropped = Math.max(droppedUnits, queue != null ? queue.droppedUnits() : 0L);
            return new ClientSendStats(queuedUnits, queuedBytes, dropped, resyncCount,
                    sentUnits, descriptorSends);
        }
    }

    /**
     * Returns a consistent snapshot of the session counters together with its state and measured
     * send latency.
     *
     * <p>Used by the hub to enumerate attached sessions for the server statistics. The percentile
     * is computed from this session's own metric scope, so a snapshot costs one window copy and
     * never touches another session.</p>
     */
    public SessionSnapshot snapshot() {
        synchronized (stateLock) {
            long queuedUnits = queue != null ? queue.size() : 0L;
            long queuedBytes = queue != null ? queue.bytes() : 0L;
            long dropped = Math.max(droppedUnits, queue != null ? queue.droppedUnits() : 0L);
            ClientSendStats current = new ClientSendStats(queuedUnits, queuedBytes, dropped,
                    resyncCount, sentUnits, descriptorSends);
            return new SessionSnapshot(key, state, current,
                    metrics.stats(PipelineMetrics.Window.SEND).getP95());
        }
    }

    /**
     * Detaches the session and closes its transport.
     *
     * <p>Idempotent and safe from any thread; repeated calls do not close the transport again.
     * After this call the state is {@link ClientState#CLOSED}, the queue is cleared, offers are
     * rejected with {@code REJECTED_CLOSED}, and {@link #drainOne()} returns false. A drain that
     * is already inside the transport is not interrupted; it updates no counters once it returns,
     * because the post-send update checks the detached flag.</p>
     */
    public void detach() {
        boolean terminate;
        synchronized (stateLock) {
            terminate = detachInternalLocked();
        }
        if (terminate) {
            transport.close();
        }
    }

    /**
     * Detaches the session and terminates its transport with {@code reason}.
     *
     * <p>Used when the session is revoked while the peer may still be waiting for media, for
     * example when a newer connection takes over its client id: the transport reports the reason
     * to the peer instead of only being released. All other detach guarantees apply unchanged;
     * the first detach wins, so a later plain {@link #detach()} does not touch the transport
     * again. The transport call runs outside the state lock, so a terminal callback that blocks on
     * the peer never blocks offers.</p>
     *
     * @param reason short English description of the termination; null closes without a reason
     */
    public void detach(String reason) {
        boolean terminate;
        synchronized (stateLock) {
            terminate = detachInternalLocked();
        }
        if (terminate) {
            if (reason != null) {
                transport.abort(reason);
            } else {
                transport.close();
            }
        }
    }

    /**
     * Moves the session to {@code RESYNCING} when the queue reports a broken decode chain, at most
     * once per episode so that the resync counter reflects episodes rather than offers. The caller
     * must hold the state lock.
     */
    private void enterResyncIfNeededLocked() {
        if (queue != null && queue.isResyncRequired() && !resyncEpisodeCounted) {
            state = ClientState.RESYNCING;
            resyncCount++;
            resyncEpisodeCounted = true;
        }
    }

    /**
     * Marks the session closed, clears its queue and claims the single transport termination.
     * The caller must hold the state lock; the transport call itself is made outside it.
     *
     * @return true when this call claimed the transport termination, false when another detach or
     *         failed drain already did
     */
    private boolean detachInternalLocked() {
        detached = true;
        state = ClientState.CLOSED;
        if (queue != null) {
            queue.clear();
        }
        if (transportClosed) {
            return false;
        }
        transportClosed = true;
        return true;
    }
}
