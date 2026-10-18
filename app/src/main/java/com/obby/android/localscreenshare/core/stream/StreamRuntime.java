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
import com.obby.android.localscreenshare.core.model.ColorInfo;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.model.StreamParameters;

import java.util.ArrayList;
import java.util.List;

/**
 * Runtime that owns a single active encoder for one stream.
 *
 * <p>Each encoder instance is tagged with a monotonically increasing generation.
 * Access units from retired encoders are dropped via generation and epoch checks;
 * retired encoders are closed only after the new epoch's first join point is
 * broadcast. Deferred codecs stay in {@link RuntimeState#CONFIGURING} until the
 * encoder reports its {@link EncoderConfig}.</p>
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

    public StreamRuntime(CodecRegistry registry, StreamKey streamKey,
                         StreamDescriptor descriptor, StreamParameters parameters) {
        this(registry, streamKey, descriptor, parameters, TimeSource.MONOTONIC,
                DEFAULT_JOIN_POINT_MIN_INTERVAL_MS);
    }

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

    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    public RuntimeState getState() {
        synchronized (lock) {
            return state;
        }
    }

    public StreamKey getStreamKey() {
        return streamKey;
    }

    public StreamDescriptor getDescriptor() {
        synchronized (lock) {
            return publishedDescriptor;
        }
    }

    public int getConfigEpoch() {
        synchronized (lock) {
            return requested.getConfigEpoch();
        }
    }

    public StreamParameters getParameters() {
        synchronized (lock) {
            return parameters;
        }
    }

    public Surface getInputSurface() {
        synchronized (lock) {
            if (state != RuntimeState.CONFIGURING && state != RuntimeState.READY) {
                return null;
            }
            return activeEncoder != null ? activeEncoder.getInputSurface() : null;
        }
    }

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

    public void switchDescriptor(StreamDescriptor next) {
        if (next == null) {
            throw new IllegalArgumentException("next must not be null");
        }
        List<StreamRuntimeListener> descriptorSnapshot;
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

            CodecRegistration currentRegistration = registry.require(requested.getCodecId());
            CodecRegistration nextRegistration = registry.require(next.getCodecId());
            if (currentRegistration.getDescriptor().hasConfigParts()
                    || nextRegistration.getDescriptor().hasConfigParts()) {
                throw new CodecSwitchException(
                        "deferred codec switching is not supported in this batch", null);
            }

            if (requested.sameDecodeConfig(next)) {
                throw new IllegalArgumentException("decode config is unchanged");
            }

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
            publishedDescriptor = next;
            state = RuntimeState.READY;
            deferred = false;
            pendingJoin = false;
            lastJoinMs = Long.MIN_VALUE;
            descriptorSnapshot = new ArrayList<>(listeners);
        }

        for (StreamRuntimeListener listener : descriptorSnapshot) {
            try {
                listener.onDescriptorChanged(next);
            } catch (RuntimeException ignored) {
                // isolate listener exceptions
            }
        }

        requestJoinPoint();
    }

    public void addListener(StreamRuntimeListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        synchronized (lock) {
            listeners.add(listener);
        }
    }

    public void removeListener(StreamRuntimeListener listener) {
        synchronized (lock) {
            listeners.remove(listener);
        }
    }

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
