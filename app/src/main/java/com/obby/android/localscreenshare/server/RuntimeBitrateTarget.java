package com.obby.android.localscreenshare.server;

import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamParameters;
import com.obby.android.localscreenshare.core.stream.StreamHub;
import com.obby.android.localscreenshare.core.stream.StreamRuntime;

/**
 * Production {@link BitrateTarget}: reads the live hub and runtime and writes the runtime's
 * parameters.
 *
 * <p>Every getter tolerates a descriptor that is not published yet (deferred codec configuration
 * or a failed start) and a runtime that is no longer running: the coordinator turns those into a
 * skip instead of an exception. Applying a target goes through {@link
 * StreamRuntime#updateParameters(StreamParameters)}, which keeps the encoder call off the gRPC
 * thread and propagates an {@link IllegalStateException} when the runtime has stopped; the
 * coordinator contains it.</p>
 */
public final class RuntimeBitrateTarget implements BitrateTarget {

    private final StreamHub mHub;

    private final StreamRuntime mRuntime;

    /**
     * Creates the target of one running stream.
     *
     * @param hub     stream hub that owns the sessions; required
     * @param runtime active stream runtime; required
     * @throws IllegalArgumentException if an argument is null
     */
    public RuntimeBitrateTarget(StreamHub hub, StreamRuntime runtime) {
        if (hub == null) {
            throw new IllegalArgumentException("hub must not be null");
        }
        if (runtime == null) {
            throw new IllegalArgumentException("runtime must not be null");
        }
        mHub = hub;
        mRuntime = runtime;
    }

    @Override
    public int getSessionCount() {
        return mHub.getSessionCount();
    }

    @Override
    public String getCodecId() {
        final StreamDescriptor descriptor = mRuntime.getDescriptor();
        return descriptor == null ? null : descriptor.getCodecId();
    }

    @Override
    public int getTargetBitrateBps() {
        final StreamParameters parameters = mRuntime.getParameters();
        return parameters == null ? 0 : parameters.getTargetBitrateBps();
    }

    @Override
    public void applyTargetBitrate(int bitrateBps) {
        final StreamParameters parameters = mRuntime.getParameters();
        if (parameters == null) {
            throw new IllegalStateException("runtime has no parameters");
        }
        mRuntime.updateParameters(parameters.withTargetBitrate(bitrateBps));
    }
}
