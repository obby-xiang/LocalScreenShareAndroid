package com.obby.android.localscreenshare.core.stream;

import android.view.Surface;

import com.obby.android.localscreenshare.core.codec.CodecRegistration;
import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.codec.CodecSwitchException;
import com.obby.android.localscreenshare.core.codec.ConfigEpochException;
import com.obby.android.localscreenshare.core.codec.Encoder;
import com.obby.android.localscreenshare.core.codec.EncoderOutput;
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
 * broadcast.</p>
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

    private StreamDescriptor descriptor;
    private StreamParameters parameters;
    private Encoder activeEncoder;
    private long generation;
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
        this.descriptor = descriptor;
        this.parameters = parameters;
        this.timeSource = timeSource;
        this.joinPointMinIntervalMs = joinPointMinIntervalMs;
        this.lastJoinMs = Long.MIN_VALUE;
    }

    public void start() {
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("runtime is closed");
            }
            if (activeEncoder != null) {
                throw new IllegalStateException("runtime is already started");
            }
            CodecRegistration registration = registry.require(descriptor.getCodecId());
            Encoder encoder = registration.getEncoderFactory().create(streamKey, descriptor,
                    parameters, outputForGeneration(generation));
            try {
                if (registration.getDescriptor().isSurfaceInputRequired()
                        && encoder.getInputSurface() == null) {
                    throw new IllegalStateException(
                            "surface input required but encoder returned a null surface");
                }
                encoder.start();
            } catch (RuntimeException e) {
                encoder.close();
                throw e;
            }
            activeEncoder = encoder;
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
            activeToClose = activeEncoder;
            activeEncoder = null;
            retiredToClose = new ArrayList<>(retiredEncoders);
            retiredEncoders.clear();
        }
        if (activeToClose != null) {
            activeToClose.close();
        }
        for (Encoder encoder : retiredToClose) {
            encoder.close();
        }
    }

    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    public StreamDescriptor getDescriptor() {
        synchronized (lock) {
            return descriptor;
        }
    }

    public int getConfigEpoch() {
        synchronized (lock) {
            return descriptor.getConfigEpoch();
        }
    }

    public StreamParameters getParameters() {
        synchronized (lock) {
            return parameters;
        }
    }

    public Surface getInputSurface() {
        synchronized (lock) {
            return activeEncoder != null ? activeEncoder.getInputSurface() : null;
        }
    }

    public void updateParameters(StreamParameters newParameters) {
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("runtime is closed");
            }
            if (newParameters == null) {
                throw new IllegalArgumentException("parameters must not be null");
            }
            parameters = newParameters;
            if (activeEncoder != null) {
                activeEncoder.updateParameters(newParameters);
            }
        }
    }

    public boolean requestJoinPoint() {
        Encoder encoder;
        long requestGeneration;
        synchronized (lock) {
            if (closed || activeEncoder == null) {
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
            int expectedEpoch = descriptor.getConfigEpoch() + 1;
            if (next.getConfigEpoch() != expectedEpoch) {
                throw new ConfigEpochException(expectedEpoch, next.getConfigEpoch());
            }
            if (descriptor.sameDecodeConfig(next)) {
                throw new IllegalArgumentException("decode config is unchanged");
            }

            long nextGeneration = generation + 1;
            CodecRegistration registration = registry.require(next.getCodecId());
            Encoder newEncoder = null;
            try {
                newEncoder = registration.getEncoderFactory().create(streamKey, next, parameters,
                        outputForGeneration(nextGeneration));
                if (registration.getDescriptor().isSurfaceInputRequired()
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
            generation = nextGeneration;
            descriptor = next;
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
        };
    }

    private void onAccessUnit(EncodedAccessUnit accessUnit, long generationOfOutput) {
        List<StreamRuntimeListener> snapshot;
        List<Encoder> toClose = null;
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
            if (accessUnit.getConfigEpoch() != descriptor.getConfigEpoch()) {
                return;
            }
            if (accessUnit.getFlags().isJoinPoint()) {
                pendingJoin = false;
                lastJoinMs = timeSource.nowMs();
                if (!retiredEncoders.isEmpty()) {
                    toClose = new ArrayList<>(retiredEncoders);
                    retiredEncoders.clear();
                }
            }
            snapshot = new ArrayList<>(listeners);
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
