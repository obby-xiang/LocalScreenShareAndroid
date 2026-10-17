package com.obby.android.localscreenshare.core.client;

import com.obby.android.localscreenshare.core.codec.CodecPolicy;
import com.obby.android.localscreenshare.core.codec.CodecRegistration;
import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.codec.Decoder;
import com.obby.android.localscreenshare.core.codec.DecoderException;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.model.ClientState;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.render.RenderTarget;
import com.obby.android.localscreenshare.core.stream.ClientCapabilities;

/**
 * Per-client media state machine that drives a {@link Decoder} and forwards its
 * output to the session's {@link RenderTarget}.
 */
public final class ClientMediaSession {

    private final ClientSessionKey key;
    private final CodecRegistry registry;
    private final ClientCapabilities capabilities;
    private final RenderTarget renderTarget;
    private final ClientStateListener listener;

    private ClientState state = ClientState.NEGOTIATING;
    private StreamDescriptor currentDescriptor;
    private Decoder decoder;
    private CodecPolicy policy;
    private boolean closed;

    public ClientMediaSession(ClientSessionKey key, CodecRegistry registry,
                              ClientCapabilities capabilities, RenderTarget renderTarget,
                              ClientStateListener listener) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        if (capabilities == null) {
            throw new IllegalArgumentException("capabilities must not be null");
        }
        if (renderTarget == null) {
            throw new IllegalArgumentException("renderTarget must not be null");
        }
        this.key = key;
        this.registry = registry;
        this.capabilities = capabilities;
        this.renderTarget = renderTarget;
        this.listener = listener;
    }

    public synchronized ClientState getState() {
        return state;
    }

    public synchronized StreamDescriptor getCurrentDescriptor() {
        return currentDescriptor;
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    public synchronized boolean onDescriptor(StreamDescriptor descriptor) {
        if (closed) {
            return false;
        }
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor must not be null");
        }

        if (!capabilities.supports(descriptor.getCodecId())) {
            if (decoder != null) {
                decoder.close();
                decoder = null;
            }
            closed = true;
            setState(ClientState.CLOSED);
            return false;
        }

        CodecRegistration registration = registry.require(descriptor.getCodecId());
        if (registration.getDescriptor().isSurfaceOutputRequired()
                && renderTarget.getSurface() == null) {
            currentDescriptor = descriptor;
            policy = registration.getPolicy();
            setState(ClientState.RESYNCING);
            return false;
        }

        if (currentDescriptor != null) {
            int currentEpoch = currentDescriptor.getConfigEpoch();
            int newEpoch = descriptor.getConfigEpoch();
            if (newEpoch == currentEpoch) {
                if (descriptor.getConfigHash().equals(currentDescriptor.getConfigHash())) {
                    return false;
                }
                throw new IllegalArgumentException("same epoch with a different decode config");
            }
            if (newEpoch < currentEpoch) {
                return false;
            }
        }

        return applyDescriptor(descriptor, registration);
    }

    public synchronized boolean onAccessUnit(EncodedAccessUnit accessUnit) {
        if (accessUnit == null) {
            throw new IllegalArgumentException("access unit must not be null");
        }
        if (closed || currentDescriptor == null) {
            return false;
        }
        int currentEpoch = currentDescriptor.getConfigEpoch();
        int auEpoch = accessUnit.getConfigEpoch();
        if (auEpoch < currentEpoch) {
            return false;
        }
        if (auEpoch > currentEpoch) {
            setState(ClientState.RESYNCING);
            return false;
        }
        if (decoder == null) {
            return false;
        }

        ClientState currentState = state;
        if (currentState == ClientState.WAIT_CONFIG) {
            return false;
        }
        if (currentState == ClientState.WAIT_RANDOM_ACCESS
                || currentState == ClientState.RESYNCING) {
            if (!policy.isJoinPoint(accessUnit)) {
                return false;
            }
            setState(ClientState.STREAMING);
        } else if (currentState != ClientState.STREAMING) {
            return false;
        }
        return decode(accessUnit);
    }

    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (decoder != null) {
            decoder.close();
            decoder = null;
        }
        setState(ClientState.CLOSED);
    }

    private boolean applyDescriptor(StreamDescriptor descriptor,
                                    CodecRegistration registration) {
        setState(ClientState.WAIT_CONFIG);
        if (decoder != null) {
            decoder.close();
            decoder = null;
        }
        Decoder newDecoder = null;
        try {
            newDecoder = registration.getDecoderFactory().create(
                    key.getStreamKey(), descriptor, renderTarget);
            newDecoder.configure(descriptor);
        } catch (RuntimeException e) {
            if (newDecoder != null) {
                newDecoder.close();
            }
            currentDescriptor = descriptor;
            policy = registration.getPolicy();
            decoder = null;
            setState(ClientState.RESYNCING);
            return false;
        }
        currentDescriptor = descriptor;
        policy = registration.getPolicy();
        decoder = newDecoder;
        setState(ClientState.WAIT_RANDOM_ACCESS);
        return true;
    }

    private boolean decode(EncodedAccessUnit accessUnit) {
        try {
            decoder.decode(accessUnit);
            return true;
        } catch (DecoderException e) {
            setState(ClientState.RESYNCING);
            return false;
        }
    }

    private void setState(ClientState newState) {
        if (state != newState) {
            ClientState previous = state;
            state = newState;
            if (listener != null) {
                listener.onStateChanged(previous, newState);
            }
        }
    }
}
