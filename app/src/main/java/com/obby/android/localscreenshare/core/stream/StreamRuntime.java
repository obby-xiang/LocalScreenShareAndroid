package com.obby.android.localscreenshare.core.stream;

import android.view.Surface;

import com.obby.android.localscreenshare.core.codec.CodecRegistration;
import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.codec.CodecSwitchException;
import com.obby.android.localscreenshare.core.codec.ConfigEpochException;
import com.obby.android.localscreenshare.core.codec.Encoder;
import com.obby.android.localscreenshare.core.codec.EncoderConfig;
import com.obby.android.localscreenshare.core.codec.EncoderException;
import com.obby.android.localscreenshare.core.codec.EncoderOutput;
import com.obby.android.localscreenshare.core.codec.FrameInputEncoder;
import com.obby.android.localscreenshare.core.model.ColorInfo;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.model.StreamParameters;
import com.obby.android.localscreenshare.core.metrics.PipelineMetrics;
import com.obby.android.localscreenshare.core.util.TimeSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Runtime that owns the single active encoder for one stream.
 *
 * <p><b>Ownership.</b> A runtime is created for one {@link StreamKey} and one requested
 * {@link StreamDescriptor}, and is registered with a {@link StreamHub} that activates it and fans
 * its output out to client sessions. The runtime, not the caller, owns every encoder it creates:
 * encoders are created in {@link #start()} or {@link #switchDescriptor(StreamDescriptor)} and are
 * closed by the runtime when they are replaced or when it is closed.</p>
 *
 * <p><b>Attempt generations.</b> Every {@code start()} and every descriptor switch allocates a new
 * attempt generation from a counter that only increases. Each encoder output callback is bound to
 * the generation of the attempt that created its encoder, and access units are forwarded only
 * while that generation is still current. A superseded or retired encoder therefore cannot inject
 * units into the current epoch even while its drain thread is still winding down, and units from
 * an older config epoch are dropped as well, including ordinary units that are not join
 * points.</p>
 *
 * <p><b>Two-phase configuration.</b> Codecs whose registration reports configuration parts stay in
 * {@link RuntimeState#CONFIGURING} after {@link #start()} and remain unpublished until the encoder
 * reports an {@link EncoderConfig}. The runtime then builds the final descriptor for the same
 * config epoch, publishes it exactly once and moves to {@link RuntimeState#READY}. Codecs without
 * configuration parts publish the requested descriptor inside {@code start()} without notifying
 * listeners. {@link #getDescriptor()} returns only the published descriptor and is {@code null}
 * before publication.</p>
 *
 * <p><b>State machine.</b> {@code IDLE -> CONFIGURING -> READY}, with failure from
 * {@code CONFIGURING} or {@code READY} back to {@code FAILED}, and any state to {@code CLOSED}.
 * A failed attempt can be retried with {@link #start()} only while nothing was ever published;
 * once a descriptor has been published, a failure is terminal for this runtime instance and the
 * owner has to close and rebuild it.</p>
 *
 * <p><b>Locking and callbacks.</b> Internal state is guarded by a private lock, and descriptor
 * switches are serialized by a second, dedicated switch lock. The lock order is always switch lock
 * before state lock, and no state lock is ever taken while an encoder is created, started, stopped
 * or closed, so an encoder drain thread that enters {@link #onAccessUnit} while its encoder is
 * being superseded can always finish instead of waiting for the caller that waits for it.
 * Listener notifications are always delivered outside the state lock. Listener callbacks run on the
 * thread that produced the event - an encoder drain thread for access units, deferred
 * configuration and encoder failures, the caller's thread for a descriptor switch - so listeners
 * must not call back into blocking runtime methods. An exception thrown by one listener is isolated
 * and does not prevent the remaining listeners from running.</p>
 */
public final class StreamRuntime {

    public static final long DEFAULT_JOIN_POINT_MIN_INTERVAL_MS = 100L;

    private final CodecRegistry registry;
    private final StreamKey streamKey;
    private final TimeSource timeSource;
    private final long joinPointMinIntervalMs;
    private final PipelineMetrics metrics;

    private final Object lock = new Object();

    // Serializes descriptor switches without being held while an encoder is created or stopped, so
    // the state lock stays available to encoder callbacks for the whole switch.
    private final Object switchLock = new Object();

    private final List<StreamRuntimeListener> listeners = new ArrayList<>();
    private final List<Encoder> retiredEncoders = new ArrayList<>();

    private StreamDescriptor requested;
    private StreamDescriptor publishedDescriptor;
    private StreamParameters parameters;
    private Encoder activeEncoder;
    private RuntimeState state = RuntimeState.IDLE;
    private boolean deferred;
    private long generation;
    private long nextAttemptGeneration;
    private boolean closed;
    private boolean pendingJoin;
    private long lastJoinMs;

    /**
     * Creates a runtime with the production monotonic {@link TimeSource} and the default
     * join-point minimum interval.
     */
    public StreamRuntime(CodecRegistry registry, StreamKey streamKey,
                         StreamDescriptor descriptor, StreamParameters parameters) {
        this(registry, streamKey, descriptor, parameters, TimeSource.MONOTONIC,
                DEFAULT_JOIN_POINT_MIN_INTERVAL_MS);
    }

    /**
     * Creates a runtime with an injected clock and join-point interval.
     *
     * <p>Construction validates references only; no encoder is created and no codec is resolved
     * until {@link #start()}.</p>
     *
     * @param registry                codec registry used to resolve factories and policies
     * @param streamKey               identity of the stream this runtime serves
     * @param descriptor              requested descriptor for the first config epoch
     * @param parameters              initial rate and cadence hints
     * @param timeSource              clock used for join-point rate limiting
     * @param joinPointMinIntervalMs  minimum delay between join-point requests forwarded to the
     *                                encoder; zero disables rate limiting
     * @throws IllegalArgumentException if any reference is null
     */
    public StreamRuntime(CodecRegistry registry, StreamKey streamKey,
                         StreamDescriptor descriptor, StreamParameters parameters,
                         TimeSource timeSource, long joinPointMinIntervalMs) {
        this(registry, streamKey, descriptor, parameters, timeSource, joinPointMinIntervalMs,
                PipelineMetrics.NONE);
    }

    /**
     * Creates a runtime with an injected clock, join-point interval and metric scope.
     *
     * <p>The scope receives the fan-out counters - access units, their payload bytes and join
     * points - and the join-point request counter. It is shared with the encoder created from the
     * same codec registration, so one scope describes the whole server encode-to-send path.</p>
     *
     * @param registry                codec registry used to resolve factories and policies
     * @param streamKey               identity of the stream this runtime serves
     * @param descriptor              requested descriptor for the first config epoch
     * @param parameters              initial rate and cadence hints
     * @param timeSource              clock used for join-point rate limiting
     * @param joinPointMinIntervalMs  minimum delay between join-point requests forwarded to the
     *                                encoder; zero disables rate limiting
     * @param metrics                 metric scope of the server pipeline; required
     * @throws IllegalArgumentException if any reference is null
     */
    public StreamRuntime(CodecRegistry registry, StreamKey streamKey,
                         StreamDescriptor descriptor, StreamParameters parameters,
                         TimeSource timeSource, long joinPointMinIntervalMs,
                         PipelineMetrics metrics) {
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        if (streamKey == null) {
            throw new IllegalArgumentException("streamKey must not be null");
        }
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor must not be null");
        }
        if (parameters == null) {
            throw new IllegalArgumentException("parameters must not be null");
        }
        if (timeSource == null) {
            throw new IllegalArgumentException("timeSource must not be null");
        }
        if (metrics == null) {
            throw new IllegalArgumentException("metrics must not be null");
        }
        this.registry = registry;
        this.streamKey = streamKey;
        this.requested = descriptor;
        this.parameters = parameters;
        this.timeSource = timeSource;
        this.joinPointMinIntervalMs = joinPointMinIntervalMs;
        this.metrics = metrics;
        this.lastJoinMs = Long.MIN_VALUE;
    }

    /**
     * Creates and starts the encoder for the current requested descriptor.
     *
     * <p>Allowed from {@link RuntimeState#IDLE}, or from {@link RuntimeState#FAILED} when no
     * descriptor was ever published. A runtime that already started, or that failed after
     * publishing, cannot be restarted and is rejected with {@link IllegalStateException}. The call
     * allocates a new attempt generation, marks the runtime {@code CONFIGURING} and clears the
     * published descriptor before the encoder factory is invoked, so observers never see the
     * previous attempt's {@code READY} state while the new encoder is still being created. A
     * previous failed encoder is closed outside the lock; an unregistered codec id propagates the
     * registry's exception after the attempt was recorded.</p>
     *
     * <p>The factory is expected to finish codec configuration itself. This method only checks
     * that a surface-input codec returned an input surface, based on
     * {@code CodecDescriptor.isSurfaceInputRequired()} alone and without a configuration-parts
     * precondition, and then starts the encoder outside the lock. A synchronous factory, surface
     * or start failure marks the attempt {@code FAILED}, notifies
     * {@link StreamRuntimeListener#onEncoderFailed(StreamKey, EncoderException)} outside the lock,
     * closes that encoder and throws {@link EncoderException}.</p>
     *
     * <p>Codecs without configuration parts publish the requested descriptor and become
     * {@link RuntimeState#READY} inside this call without a descriptor notification. Codecs with
     * configuration parts stay {@code CONFIGURING} until the encoder reports its configuration
     * through {@link EncoderOutput#onEncoderConfig(EncoderConfig)}; access units that arrive
     * before publication are dropped.</p>
     *
     * @throws IllegalStateException if the runtime is closed or is not in a retryable state
     * @throws EncoderException      if the encoder cannot be created, validated or started
     */
    public void start() {
        final long attempt;
        final Encoder oldFailed;
        final StreamDescriptor startDescriptor;
        final StreamParameters startParameters;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("runtime is closed");
            }
            boolean retryable = state == RuntimeState.IDLE
                    || (state == RuntimeState.FAILED && publishedDescriptor == null);
            if (!retryable) {
                throw new IllegalStateException("runtime is already started");
            }
            attempt = ++nextAttemptGeneration;
            generation = attempt;
            state = RuntimeState.CONFIGURING;
            publishedDescriptor = null;
            oldFailed = activeEncoder;
            activeEncoder = null;
            startDescriptor = requested;
            startParameters = parameters;
        }

        // Retry path: close the previous failed encoder outside the lock.
        if (oldFailed != null) {
            oldFailed.close();
        }

        final CodecRegistration registration = registry.require(startDescriptor.getCodecId());
        final boolean deferredCodec = registration.getDescriptor().hasConfigParts();
        final Encoder encoder;
        try {
            encoder = registration.getEncoderFactory().create(streamKey, startDescriptor,
                    startParameters, outputForGeneration(attempt));
        } catch (RuntimeException e) {
            EncoderException failure = new EncoderException(
                    "encoder factory failed for codec " + startDescriptor.getCodecId(), e);
            notifyEncoderFailed(markFailed(attempt), failure);
            throw failure;
        }

        // Record the active encoder and deferral under the lock, then validate the
        // input surface outside the lock (surface requirement only, no config-parts
        // precondition).
        boolean overtaken;
        synchronized (lock) {
            overtaken = generation != attempt
                    || (state != RuntimeState.CONFIGURING && state != RuntimeState.READY);
            if (!overtaken) {
                activeEncoder = encoder;
                deferred = deferredCodec;
            }
        }
        if (overtaken) {
            encoder.close();
            throw new EncoderException("encoder attempt superseded before surface validation");
        }

        if (registration.getDescriptor().isSurfaceInputRequired()
                && encoder.getInputSurface() == null) {
            EncoderException failure = new EncoderException(
                    "surface input required but encoder returned a null surface");
            notifyEncoderFailed(markFailed(attempt), failure);
            encoder.close();
            throw failure;
        }

        // Re-check that no asynchronous failure or close overtook the attempt before
        // starting the encoder.
        boolean startOvertaken;
        synchronized (lock) {
            startOvertaken = generation != attempt
                    || (state != RuntimeState.CONFIGURING && state != RuntimeState.READY);
        }
        if (startOvertaken) {
            encoder.close();
            throw new EncoderException("encoder attempt superseded before start");
        }

        try {
            encoder.start();
        } catch (RuntimeException e) {
            EncoderException failure = new EncoderException("encoder failed to start", e);
            notifyEncoderFailed(markFailed(attempt), failure);
            encoder.close();
            throw failure;
        }

        // Publish for non-deferred codecs; deferred codecs stay CONFIGURING until the
        // encoder reports its configuration. Non-deferred start publishes the descriptor
        // without notifying listeners (existing behavior).
        synchronized (lock) {
            if (generation == attempt && !closed && state == RuntimeState.CONFIGURING) {
                if (!deferredCodec) {
                    state = RuntimeState.READY;
                    publishedDescriptor = requested;
                }
            }
        }
    }

    /**
     * Closes the runtime and every encoder it owns.
     *
     * <p>Idempotent and non-throwing. The runtime moves to {@link RuntimeState#CLOSED} and drops
     * its references before closing the active encoder and any encoders retired by a descriptor
     * switch. The close calls happen outside the lock and a failing close is ignored so the
     * remaining encoders are still closed. The last published descriptor is intentionally kept
     * readable for diagnostics; after this call no access unit, configuration or failure callback
     * is delivered.</p>
     */
    public void close() {
        Encoder activeToClose;
        List<Encoder> retiredToClose;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            state = RuntimeState.CLOSED;
            activeToClose = activeEncoder;
            activeEncoder = null;
            retiredToClose = new ArrayList<>(retiredEncoders);
            retiredEncoders.clear();
        }
        if (activeToClose != null) {
            try {
                activeToClose.close();
            } catch (RuntimeException ignored) {
                // a single encoder's close failure must not affect the others
            }
        }
        for (Encoder encoder : retiredToClose) {
            try {
                encoder.close();
            } catch (RuntimeException ignored) {
                // a single encoder's close failure must not affect the others
            }
        }
    }

    /**
     * Returns whether {@link #close()} has been called; a closed runtime never reopens.
     */
    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    /**
     * Returns the current lifecycle state, read atomically with the rest of the runtime state.
     */
    public RuntimeState getState() {
        synchronized (lock) {
            return state;
        }
    }

    /**
     * Returns the stream identity this runtime serves; never null and never changes.
     */
    public StreamKey getStreamKey() {
        return streamKey;
    }

    /**
     * Returns the published descriptor, or {@code null} before anything was published.
     *
     * <p>A deferred codec has no descriptor between {@link #start()} and the encoder's
     * configuration report, and a failed attempt that never published stays {@code null}. Callers
     * that need the stream identity must use {@link #getStreamKey()} instead of this method.</p>
     */
    public StreamDescriptor getDescriptor() {
        synchronized (lock) {
            return publishedDescriptor;
        }
    }

    /**
     * Returns the config epoch of the current requested descriptor. It advances only through a
     * successful {@link #switchDescriptor(StreamDescriptor)}; publishing deferred configuration for
     * the same epoch does not change it.
     */
    public int getConfigEpoch() {
        synchronized (lock) {
            return requested.getConfigEpoch();
        }
    }

    /**
     * Returns the rate and cadence hints last accepted by {@link #updateParameters}.
     */
    public StreamParameters getParameters() {
        synchronized (lock) {
            return parameters;
        }
    }

    /**
     * Returns the input surface of the active encoder, or {@code null}.
     *
     * <p>Returns {@code null} unless the runtime is {@code CONFIGURING} or {@code READY}, and for
     * codecs that do not consume surface input. For a surface-input codec the value is non-null
     * from encoder creation until the runtime is closed or the encoder is replaced. The active
     * encoder is queried while the runtime lock is held, so implementations must return promptly
     * and must not call back into the runtime from this method.</p>
     */
    public Surface getInputSurface() {
        synchronized (lock) {
            if (state != RuntimeState.CONFIGURING && state != RuntimeState.READY) {
                return null;
            }
            return activeEncoder != null ? activeEncoder.getInputSurface() : null;
        }
    }

    /**
     * Applies new rate and cadence hints to the active encoder.
     *
     * <p>Allowed only while {@code CONFIGURING} or {@code READY}. The encoder is updated first,
     * under the runtime lock, and the cached parameters are replaced only when that call returns,
     * so an encoder failure leaves the previous value in place and propagates to the caller. The
     * config epoch, descriptor and encoder instance are unchanged.</p>
     *
     * @throws IllegalStateException    if the runtime is closed or not running
     * @throws IllegalArgumentException if {@code newParameters} is null
     */
    public void updateParameters(StreamParameters newParameters) {
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("runtime is closed");
            }
            if (state != RuntimeState.CONFIGURING && state != RuntimeState.READY) {
                throw new IllegalStateException("runtime is not running");
            }
            if (newParameters == null) {
                throw new IllegalArgumentException("parameters must not be null");
            }
            if (activeEncoder != null) {
                activeEncoder.updateParameters(newParameters);
            }
            parameters = newParameters;
        }
    }

    /**
     * Asks the active encoder for a random-access point, coalescing and rate-limiting requests.
     *
     * <p>Requests are forwarded only from {@link RuntimeState#READY}. While a request is still
     * pending, or while the last delivered join point is younger than the configured minimum
     * interval, the call returns {@code false} without reaching the encoder; concurrent callers
     * therefore cause at most one outstanding request. A forwarded request sets the pending flag,
     * which is cleared when the encoder emits the next join point. If {@code requestSyncPoint()}
     * throws, the pending flag is rolled back only when the runtime generation is still the one
     * the request was made for, and the exception is rethrown.</p>
     *
     * @return {@code true} when this call reached the encoder, {@code false} when it was
     *         coalesced, rate-limited or the runtime is not ready
     */
    public boolean requestJoinPoint() {
        Encoder encoder;
        long requestGeneration;
        synchronized (lock) {
            if (closed || state != RuntimeState.READY || activeEncoder == null) {
                return false;
            }
            if (pendingJoin) {
                return false;
            }
            if (lastJoinMs != Long.MIN_VALUE
                    && timeSource.nowMs() - lastJoinMs <= joinPointMinIntervalMs) {
                return false;
            }
            pendingJoin = true;
            encoder = activeEncoder;
            requestGeneration = generation;
        }
        try {
            encoder.requestSyncPoint();
        } catch (RuntimeException e) {
            synchronized (lock) {
                if (requestGeneration == generation) {
                    pendingJoin = false;
                }
            }
            throw e;
        }
        metrics.increment(PipelineMetrics.Counter.JOIN_REQUESTS);
        return true;
    }

    /**
     * Submits one complete encoded frame to a frame-input encoder.
     *
     * <p>The frame is forwarded only while the runtime is {@code CONFIGURING} or {@code READY} and
     * the active encoder implements {@link FrameInputEncoder}; other codecs ignore the call and
     * return {@code false}. The encoder is invoked outside the runtime lock because it emits an
     * access unit synchronously, which must not run while the lock is held.</p>
     *
     * @param ptsUs        presentation timestamp in microseconds
     * @param encodedBytes complete encoded frame payload; must not be null or empty
     * @return {@code true} when the active encoder accepted the frame
     */
    public boolean submitFrame(long ptsUs, byte[] encodedBytes) {
        if (encodedBytes == null || encodedBytes.length == 0) {
            return false;
        }
        final Encoder encoder;
        synchronized (lock) {
            if (closed) {
                return false;
            }
            if (state != RuntimeState.CONFIGURING && state != RuntimeState.READY) {
                return false;
            }
            if (!(activeEncoder instanceof FrameInputEncoder)) {
                return false;
            }
            encoder = activeEncoder;
        }
        return ((FrameInputEncoder) encoder).submitFrame(ptsUs, encodedBytes);
    }

    /**
     * Switches to the next descriptor by prewarming a new encoder and retiring the old one.
     *
     * <p><b>Phases.</b> The request is validated and the attempt generation reserved under the
     * state lock, which is then released: the stream key must match and the epoch must be exactly
     * one greater than the current one (otherwise {@link ConfigEpochException}). The new encoder is
     * created, surface-checked and started outside every lock, and only then is the superseded
     * encoder stopped, again outside every lock. Stopping it while holding the state lock is what
     * used to deadlock: the old drain thread enters {@link #onAccessUnit} to forward its last unit
     * and needs that lock, whereas the caller waited for the thread to terminate with a bounded
     * join. Concurrent switches are serialized by the switch lock, which is always taken before the
     * state lock and never held while an encoder lifecycle call runs.</p>
     *
     * <p><b>Commit.</b> In one critical section the runtime adopts the new encoder, advances the
     * attempt generation and records the new descriptor, then retires the superseded encoder. A
     * codec without configuration parts also publishes {@code next} and moves to
     * {@link RuntimeState#READY}; a codec with configuration parts stays
     * {@link RuntimeState#CONFIGURING} and publishes the final descriptor when the new encoder
     * reports its configuration, exactly like a first start. Listeners are notified outside the
     * lock and a join point is requested. The retired encoder is closed after the first join point
     * of the new epoch has been forwarded, which is also the point by which the owner has
     * re-pointed the capture display at the new encoder's input surface.</p>
     *
     * <p><b>Failure.</b> A failure while creating, validating or starting the new encoder closes it
     * and keeps the old encoder, descriptor, epoch and state, wrapped in
     * {@link CodecSwitchException}; nothing is published and no listener is notified, so the owner
     * can retry with the same epoch. A failure of {@code stop()} on the old encoder does not abort
     * the switch: that encoder has already been told to stop and cannot produce another usable
     * frame, so keeping it active would bind the runtime - and the capture display that points at
     * its surface - to a dead encoder. The switch is committed instead and the encoder is retired
     * for closing like any other, which is what makes a switch recoverable rather than terminal.</p>
     *
     * @throws IllegalArgumentException if {@code next} is null or belongs to another stream
     * @throws ConfigEpochException     if the epoch is not exactly one greater than the current
     * @throws CodecSwitchException     if the new encoder cannot be prewarmed
     * @throws IllegalStateException    if the runtime is closed, before or during the switch
     */
    public void switchDescriptor(StreamDescriptor next) {
        if (next == null) {
            throw new IllegalArgumentException("next must not be null");
        }
        List<StreamRuntimeListener> descriptorSnapshot = null;
        boolean requestJoin = false;
        synchronized (switchLock) {
            final CodecRegistration nextRegistration;
            final boolean nextDeferred;
            final StreamParameters nextParameters;
            final long attempt;
            synchronized (lock) {
                if (closed) {
                    throw new IllegalStateException("runtime is closed");
                }
                if (!next.getStreamKey().equals(streamKey)) {
                    throw new IllegalArgumentException("stream key mismatch");
                }
                int expectedEpoch = requested.getConfigEpoch() + 1;
                if (next.getConfigEpoch() != expectedEpoch) {
                    throw new ConfigEpochException(expectedEpoch, next.getConfigEpoch());
                }
                nextRegistration = registry.require(next.getCodecId());
                nextDeferred = nextRegistration.getDescriptor().hasConfigParts();
                nextParameters = parameters;
                attempt = ++nextAttemptGeneration;
            }

            // Phase one: prewarm the new encoder outside the lock. The old encoder keeps draining
            // meanwhile; its units still carry the current generation, so they are forwarded as
            // usual, and units from the new encoder are dropped until the commit below.
            Encoder created = null;
            try {
                created = nextRegistration.getEncoderFactory().create(streamKey, next,
                        nextParameters, outputForGeneration(attempt));
                if (nextRegistration.getDescriptor().isSurfaceInputRequired()
                        && created.getInputSurface() == null) {
                    throw new IllegalStateException(
                            "surface input required but encoder returned a null surface");
                }
                created.start();
            } catch (RuntimeException e) {
                if (created != null) {
                    closeQuietly(created);
                }
                throw new CodecSwitchException(
                        "failed to prewarm encoder for epoch " + next.getConfigEpoch(), e);
            }
            final Encoder newEncoder = created;

            // Phase two: commit under the lock. A close that won the race closes the prewarmed
            // encoder instead of installing it into a closed runtime.
            final Encoder oldEncoder;
            synchronized (lock) {
                if (closed) {
                    closeQuietly(newEncoder);
                    throw new IllegalStateException("runtime is closed");
                }
                oldEncoder = activeEncoder;
                activeEncoder = newEncoder;
                generation = attempt;
                requested = next;
                deferred = nextDeferred;
                pendingJoin = false;
                lastJoinMs = Long.MIN_VALUE;
                if (nextDeferred) {
                    // The new descriptor is published when the encoder reports its configuration.
                    state = RuntimeState.CONFIGURING;
                    publishedDescriptor = null;
                } else {
                    publishedDescriptor = next;
                    state = RuntimeState.READY;
                    descriptorSnapshot = new ArrayList<>(listeners);
                    requestJoin = true;
                }
            }

            // Phase three: stop the superseded encoder outside the lock. The runtime is already
            // pointing at the new encoder, so a failure here can only affect the old one.
            if (oldEncoder != null) {
                stopQuietly(oldEncoder);
                final boolean runtimeClosed;
                synchronized (lock) {
                    runtimeClosed = closed;
                    if (!runtimeClosed) {
                        retiredEncoders.add(oldEncoder);
                    }
                }
                if (runtimeClosed) {
                    closeQuietly(oldEncoder);
                }
            }
        }

        if (descriptorSnapshot != null) {
            for (StreamRuntimeListener listener : descriptorSnapshot) {
                try {
                    listener.onDescriptorChanged(next);
                } catch (RuntimeException ignored) {
                    // isolate listener exceptions
                }
            }
        }

        if (requestJoin) {
            requestJoinPoint();
        }
    }

    /**
     * Adds a listener.
     *
     * <p>Callbacks are delivered in registration order and outside the runtime lock. A listener
     * that throws does not prevent the remaining listeners from being invoked; it also does not
     * change the runtime state.</p>
     */
    public void addListener(StreamRuntimeListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        synchronized (lock) {
            listeners.add(listener);
        }
    }

    /**
     * Removes a listener; removing one that was never added is a no-op.
     */
    public void removeListener(StreamRuntimeListener listener) {
        synchronized (lock) {
            listeners.remove(listener);
        }
    }

    /**
     * Creates the output sink handed to one encoder instance, binding every callback to the
     * generation of the attempt that created the encoder so late callbacks can be discarded.
     */
    private EncoderOutput outputForGeneration(final long gen) {
        return new EncoderOutput() {
            @Override
            public void onAccessUnit(EncodedAccessUnit accessUnit) {
                StreamRuntime.this.onAccessUnit(accessUnit, gen);
            }

            @Override
            public void onEncoderConfig(EncoderConfig config) {
                StreamRuntime.this.onEncoderConfig(config, gen);
            }

            @Override
            public void onEncoderFailure(EncoderException failure) {
                StreamRuntime.this.onEncoderFailure(failure, gen);
            }
        };
    }

    /**
     * Handles one encoded unit from a specific attempt.
     *
     * <p>Under the lock the unit is discarded when the runtime is closed, the stream key differs,
     * the attempt is no longer current, the unit's config epoch differs from the current one, or
     * the runtime failed. In the ready state a join point clears the pending join request, records
     * the join time and schedules every retired encoder for closing; the unit is then forwarded to
     * a listener snapshot outside the lock, and the retired encoders are closed after forwarding.
     * For a deferred codec an early unit fails the attempt (and is not forwarded) instead of being
     * silently dropped; for a non-deferred codec an early unit is dropped without changing the
     * state, as publication happens inside {@link #start()}.</p>
     */
    private void onAccessUnit(EncodedAccessUnit accessUnit, long generationOfOutput) {
        List<StreamRuntimeListener> snapshot = null;
        List<Encoder> toClose = null;
        EncoderException deferredFailure = null;
        boolean forward = false;
        synchronized (lock) {
            if (closed || accessUnit == null) {
                return;
            }
            if (!accessUnit.getStreamKey().equals(streamKey)) {
                return;
            }
            if (generationOfOutput != generation) {
                return;
            }
            if (accessUnit.getConfigEpoch() != requested.getConfigEpoch()) {
                return;
            }
            if (state == RuntimeState.FAILED) {
                return;
            }
            if (state == RuntimeState.READY && publishedDescriptor != null) {
                if (accessUnit.getFlags().isJoinPoint()) {
                    pendingJoin = false;
                    lastJoinMs = timeSource.nowMs();
                    if (!retiredEncoders.isEmpty()) {
                        toClose = new ArrayList<>(retiredEncoders);
                        retiredEncoders.clear();
                    }
                }
                snapshot = new ArrayList<>(listeners);
                forward = true;
            } else if (deferred) {
                state = RuntimeState.FAILED;
                deferredFailure = new EncoderException(
                        "access unit arrived before encoder configuration");
                snapshot = new ArrayList<>(listeners);
            } else {
                // non-deferred access unit before publication: drop silently
                return;
            }
        }
        if (deferredFailure != null) {
            notifyEncoderFailed(snapshot, deferredFailure);
            return;
        }
        if (forward) {
            metrics.increment(PipelineMetrics.Counter.ACCESS_UNITS);
            metrics.add(PipelineMetrics.Counter.ACCESS_UNIT_BYTES,
                    accessUnit.getPayloadLength());
            if (accessUnit.getFlags().isJoinPoint()) {
                metrics.increment(PipelineMetrics.Counter.JOIN_POINTS);
            }
            for (StreamRuntimeListener listener : snapshot) {
                try {
                    listener.onAccessUnit(accessUnit);
                } catch (RuntimeException ignored) {
                    // isolate listener exceptions
                }
            }
            if (toClose != null) {
                for (Encoder encoder : toClose) {
                    encoder.close();
                }
            }
        }
    }

    /**
     * Publishes the final descriptor for a deferred codec, or fails the attempt.
     *
     * <p>Only the current attempt may publish, only once, and only while still
     * {@code CONFIGURING}. Missing configuration parts or a non-positive coded size fail the
     * attempt instead of falling back to the requested sizes. On success the descriptor is built
     * for the same config epoch, stored as published and the runtime becomes {@code READY}; the
     * notification is then delivered outside the lock, after every runtime state that gates unit
     * forwarding has been updated. Ordering between the notification and the first unit relies on
     * the encoder contract that reports its configuration before the first access unit on the same
     * output path.</p>
     */
    private void onEncoderConfig(EncoderConfig config, long generationOfOutput) {
        List<StreamRuntimeListener> snapshot = null;
        StreamDescriptor published = null;
        EncoderException failure = null;
        synchronized (lock) {
            if (closed) {
                return;
            }
            if (generationOfOutput != generation) {
                return;
            }
            if (state != RuntimeState.CONFIGURING || publishedDescriptor != null) {
                return;
            }
            if (config == null || config.getConfigParts() == null || config.getConfigParts().isEmpty()
                    || config.getCodedWidth() <= 0 || config.getCodedHeight() <= 0) {
                state = RuntimeState.FAILED;
                publishedDescriptor = null;
                failure = new EncoderException("encoder reported an invalid configuration");
                snapshot = new ArrayList<>(listeners);
            } else {
                published = buildPublishedDescriptor(config);
                publishedDescriptor = published;
                state = RuntimeState.READY;
                snapshot = new ArrayList<>(listeners);
            }
        }
        if (snapshot != null) {
            if (failure != null) {
                notifyEncoderFailed(snapshot, failure);
            } else {
                notifyDescriptorChanged(snapshot, published);
            }
        }
    }

    /**
     * Asynchronous encoder failure.
     *
     * <p>Only the current attempt can fail the runtime, and only while it is not idle, already
     * failed or closed; callbacks from a superseded attempt are ignored. The published descriptor
     * is intentionally kept, because the descriptor still describes the last valid configuration
     * while the failure is reported. The notification is delivered outside the lock, and no
     * encoder lifecycle call is made from inside the callback.</p>
     */
    private void onEncoderFailure(EncoderException failure, long generationOfOutput) {
        List<StreamRuntimeListener> snapshot = null;
        synchronized (lock) {
            if (closed) {
                return;
            }
            if (state == RuntimeState.IDLE || state == RuntimeState.FAILED
                    || state == RuntimeState.CLOSED) {
                return;
            }
            if (generationOfOutput != generation) {
                return;
            }
            state = RuntimeState.FAILED;
            // published descriptor is intentionally left unchanged
            snapshot = new ArrayList<>(listeners);
        }
        if (snapshot != null) {
            notifyEncoderFailed(snapshot, failure);
        }
    }

    /**
     * Combines the requested descriptor with the configuration reported by a deferred codec.
     *
     * <p>Stream identity, epoch, codec id, MIME type, bitstream format, profile, level, visible
     * size and rotation come from the request; the coded size and configuration parts come from
     * the encoder; the color comes from the encoder unless it is unspecified. The resulting config
     * hash therefore covers the actual configuration, while the epoch stays unchanged.</p>
     */
    private StreamDescriptor buildPublishedDescriptor(EncoderConfig config) {
        StreamDescriptor.Builder builder = StreamDescriptor.builder(
                        requested.getStreamKey(), requested.getConfigEpoch(),
                        requested.getCodecId(), requested.getMime(), requested.getBitstreamFormat())
                .profile(requested.getProfile())
                .level(requested.getLevel())
                .codedSize(config.getCodedWidth(), config.getCodedHeight())
                .visibleSize(requested.getVisibleWidth(), requested.getVisibleHeight())
                .rotationDegrees(requested.getRotationDegrees())
                .configParts(config.getConfigParts());
        ColorInfo color = config.getColorInfo();
        if (color != ColorInfo.UNSPECIFIED) {
            builder.colorInfo(color);
        } else {
            builder.colorInfo(requested.getColorInfo());
        }
        return builder.build();
    }

    /**
     * Marks the attempt failed if it is still current, and returns the listeners to notify.
     *
     * <p>Used by the synchronous start paths, which must clear the published descriptor as well.
     * Returns {@code null} when a concurrent failure or close already took over the attempt, so
     * the caller silently skips the notification.</p>
     */
    private List<StreamRuntimeListener> markFailed(long attempt) {
        synchronized (lock) {
            if (generation == attempt && !closed) {
                state = RuntimeState.FAILED;
                publishedDescriptor = null;
                activeEncoder = null;
                return new ArrayList<>(listeners);
            }
            return null;
        }
    }

    private void notifyDescriptorChanged(List<StreamRuntimeListener> snapshot,
                                         StreamDescriptor descriptor) {
        if (snapshot == null) {
            return;
        }
        for (StreamRuntimeListener listener : snapshot) {
            try {
                listener.onDescriptorChanged(descriptor);
            } catch (RuntimeException ignored) {
                // isolate listener exceptions
            }
        }
    }

    private void notifyEncoderFailed(List<StreamRuntimeListener> snapshot,
                                     EncoderException failure) {
        if (snapshot == null) {
            return;
        }
        for (StreamRuntimeListener listener : snapshot) {
            try {
                listener.onEncoderFailed(streamKey, failure);
            } catch (RuntimeException ignored) {
                // isolate listener exceptions
            }
        }
    }

    /**
     * Stops a superseded encoder without letting its outcome change the switch result.
     *
     * <p>Called outside the state lock. The encoder has already been replaced in the runtime, so a
     * join timeout or a bridge failure must not be reported as a failed switch: the encoder is
     * retired either way and closing it later retries the release. The exception is intentionally
     * not propagated, because a caller that saw a failure would retry a switch that already
     * happened.</p>
     */
    private static void stopQuietly(Encoder encoder) {
        try {
            encoder.stop();
        } catch (RuntimeException ignored) {
            // The encoder is already superseded; retiring it is the recovery.
        }
    }

    /**
     * Closes an encoder without letting a failure escape; used on switch failure paths.
     */
    private static void closeQuietly(Encoder encoder) {
        try {
            encoder.close();
        } catch (RuntimeException ignored) {
            // A failing close must not hide the switch outcome.
        }
    }
}
