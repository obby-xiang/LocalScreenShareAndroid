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
 * <p><b>Locking and callbacks.</b> Internal state is guarded by a private lock. Outside a
 * descriptor switch, encoder creation, close and start happen outside that lock, and every
 * listener notification is always delivered outside it. The deliberate exception is
 * {@link #switchDescriptor(StreamDescriptor)}, which prewarms, starts and stops encoders while
 * holding the lock. Listener callbacks run on the thread that produced the event - an encoder
 * drain thread for access units, deferred configuration and encoder failures, the caller's thread
 * for a descriptor switch - so listeners must not call back into blocking runtime methods. An
 * exception thrown by one listener is isolated and does not prevent the remaining listeners from
 * running.</p>
 */
public final class StreamRuntime {

    private static final long DEFAULT_JOIN_POINT_MIN_INTERVAL_MS = 100L;

    private final CodecRegistry registry;
    private final StreamKey streamKey;
    private final TimeSource timeSource;
    private final long joinPointMinIntervalMs;

    private final Object lock = new Object();
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
        this.registry = registry;
        this.streamKey = streamKey;
        this.requested = descriptor;
        this.parameters = parameters;
        this.timeSource = timeSource;
        this.joinPointMinIntervalMs = joinPointMinIntervalMs;
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

    /** Returns whether {@link #close()} has been called; a closed runtime never reopens. */
    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    /** Returns the current lifecycle state, read atomically with the rest of the runtime state. */
    public RuntimeState getState() {
        synchronized (lock) {
            return state;
        }
    }

    /** Returns the stream identity this runtime serves; never null and never changes. */
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

    /** Returns the rate and cadence hints last accepted by {@link #updateParameters}. */
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
     * <p>The request is validated under the lock before any encoder is created: the stream key must
     * match and the epoch must be exactly one greater than the current one (otherwise
     * {@link ConfigEpochException}). The new encoder is then created, surface-checked and started,
     * and the old encoder is stopped, all while the lock is held. This synchronous prewarm is the
     * deliberate exception to the rule that encoder lifecycle calls happen outside the runtime
     * lock.</p>
     *
     * <p>If creating, validating or starting the new encoder fails, it is closed and the old
     * encoder, descriptor, epoch and state are kept, wrapped in {@link CodecSwitchException}. If
     * stopping the old encoder throws, the new encoder is closed and the original exception
     * propagates with the old encoder still active. In both cases nothing is published and no
     * listener is notified.</p>
     *
     * <p>On success the runtime adopts {@code next} as its requested descriptor and advances the
     * attempt generation. A codec without configuration parts publishes {@code next} immediately,
     * moves to {@link RuntimeState#READY}, notifies listeners outside the lock and requests a join
     * point. A codec with configuration parts stays {@link RuntimeState#CONFIGURING} and publishes
     * the final descriptor when the new encoder reports its configuration, exactly like a first
     * start. The retired encoder is kept until the first join point of the new epoch has been
     * forwarded, then closed.</p>
     *
     * @throws IllegalArgumentException if {@code next} is null or belongs to another stream
     * @throws ConfigEpochException     if the epoch is not exactly one greater than the current
     * @throws CodecSwitchException     if the new encoder cannot be prewarmed
     * @throws IllegalStateException    if the runtime is closed
     */
    public void switchDescriptor(StreamDescriptor next) {
        if (next == null) {
            throw new IllegalArgumentException("next must not be null");
        }
        List<StreamRuntimeListener> descriptorSnapshot = null;
        boolean requestJoin = false;
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

            CodecRegistration nextRegistration = registry.require(next.getCodecId());
            boolean nextDeferred = nextRegistration.getDescriptor().hasConfigParts();

            long attempt = ++nextAttemptGeneration;
            Encoder newEncoder = null;
            try {
                newEncoder = nextRegistration.getEncoderFactory().create(streamKey, next,
                        parameters, outputForGeneration(attempt));
                if (nextRegistration.getDescriptor().isSurfaceInputRequired()
                        && newEncoder.getInputSurface() == null) {
                    throw new IllegalStateException(
                            "surface input required but encoder returned a null surface");
                }
                newEncoder.start();
            } catch (RuntimeException e) {
                if (newEncoder != null) {
                    newEncoder.close();
                }
                throw new CodecSwitchException(
                        "failed to prewarm encoder for epoch " + next.getConfigEpoch(), e);
            }

            Encoder old = activeEncoder;
            try {
                if (old != null) {
                    old.stop();
                    retiredEncoders.add(old);
                }
            } catch (RuntimeException e) {
                newEncoder.close();
                throw e;
            }
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

    /** Removes a listener; removing one that was never added is a no-op. */
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
}
