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
 *
 * <p><b>State machine.</b> A session starts in {@link ClientState#NEGOTIATING}. A supported
 * descriptor records the configuration and either configures a decoder
 * ({@code WAIT_CONFIG -> WAIT_RANDOM_ACCESS}) or, when a required output surface is missing,
 * enters {@link ClientState#RESYNCING}. Units are decoded only in {@code STREAMING}, which is
 * entered from {@code WAIT_RANDOM_ACCESS} or {@code RESYNCING} on a validated join point of the
 * current epoch; units before that point are dropped. A unit from a future epoch, a decode
 * failure, or a decoder configuration failure moves the session to {@code RESYNCING}, while a
 * unit from an older epoch is dropped without a state change. {@link ClientState#CLOSED} is
 * terminal and is reached through an unsupported codec or an explicit close.</p>
 *
 * <p><b>Descriptor validation.</b> For an already known descriptor the epochs are compared first:
 * an older epoch is ignored, the same epoch with the same config hash is a duplicate, and the same
 * epoch with a different hash is a protocol violation that throws. This validation happens before
 * any surface read or decoder side effect, so a stale or duplicate descriptor cannot disturb an
 * active decoder. The latest descriptor and codec policy are recorded before the surface gate, so
 * a session blocked on a missing surface still tracks the newest epoch for a later
 * {@link #retryConfigure()}. A newer epoch is applied even when its config hash equals the
 * previous one.</p>
 *
 * <p><b>Threading.</b> Every method synchronizes on the session instance, so state, descriptor
 * and decoder always change together. State listeners are invoked synchronously on the calling
 * thread while the monitor is held, and only on real transitions; a listener must therefore not
 * block or call into code that could try to enter another session. The session never closes the
 * {@link RenderTarget}, which is owned by the display side.</p>
 *
 * <p><b>Surface geometry.</b> Before a decoder is created for a descriptor the session reports the
 * descriptor's coded size to the render target through
 * {@link RenderTarget#setContentSize(int, int)}. A surface target keeps a texture whose buffer
 * geometry was fixed when the view was created, so a stream that changes its resolution in the
 * middle of a session has to resize those buffers; otherwise the decoder output lands in a
 * fraction of the window until the viewer reconnects.</p>
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

    /**
     * Creates a session that has not received a descriptor yet.
     *
     * @param key          client and stream identity; required
     * @param registry     codec registry used to resolve decoder factories and policies; required
     * @param capabilities codecs this client can decode; required
     * @param renderTarget output target, owned by the caller; required
     * @param listener     optional state listener; may be null
     * @throws IllegalArgumentException if any required argument is null
     */
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

    /**
     * Returns the current state, read atomically.
     */
    public synchronized ClientState getState() {
        return state;
    }

    /**
     * Returns the descriptor of the current epoch, or {@code null} before the first one.
     */
    public synchronized StreamDescriptor getCurrentDescriptor() {
        return currentDescriptor;
    }

    /**
     * Returns whether the session is closed; a closed session never reopens.
     */
    public synchronized boolean isClosed() {
        return closed;
    }

    /**
     * Applies a descriptor received from the server.
     *
     * <p>The processing order is fixed: closed check, null check, capability check, registry
     * lookup, epoch and hash validation, recording of the latest descriptor and policy, surface
     * check, then decoder creation and configuration. An unsupported codec closes the session
     * instead of leaving it waiting forever. An older epoch returns false with no side effect, a
     * same-epoch duplicate returns false, and a same-epoch descriptor with a different config hash
     * throws because a decode configuration cannot change without a new epoch. A newer epoch is
     * recorded and applied; if the codec requires an output surface that the render target does
     * not provide, the session stays in {@code RESYNCING} without creating a decoder and the
     * caller is expected to call {@link #retryConfigure()} once the surface exists.</p>
     *
     * @return true when a decoder was configured for the descriptor
     * @throws IllegalArgumentException if {@code descriptor} is null or reuses the current epoch
     *                                  with a different decode configuration
     */
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

        // Epoch/hash validation precedes any surface read or decoder side effect, so a stale or
        // duplicate descriptor cannot disturb an active decoder.
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

        // Record the latest descriptor and policy before the surface gate, so a session that stays
        // blocked on a missing surface still tracks the newest epoch for a later retryConfigure.
        currentDescriptor = descriptor;
        policy = registration.getPolicy();

        if (registration.getDescriptor().isSurfaceOutputRequired()
                && renderTarget.getSurface() == null) {
            setState(ClientState.RESYNCING);
            return false;
        }

        return applyDescriptor(descriptor, registration);
    }

    /**
     * Decodes one unit if the current state allows it.
     *
     * <p>A null unit always throws, even when the session is closed. Units from an older epoch are
     * dropped without a state change, because they belong to a configuration that was already
     * replaced; units from a future epoch move the session to {@code RESYNCING} and are dropped,
     * because the descriptor for them has not arrived yet. In {@code WAIT_CONFIG} and while no
     * decoder exists units are dropped. In {@code WAIT_RANDOM_ACCESS} and {@code RESYNCING} only a
     * validated join point is accepted, and accepting it moves the session to {@code STREAMING}
     * before decoding. A {@link DecoderException} moves the session back to {@code RESYNCING};
     * other unchecked failures from a decoder are not swallowed.</p>
     *
     * @return true when the unit was handed to the decoder successfully
     * @throws IllegalArgumentException if {@code accessUnit} is null
     */
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

    /**
     * Closes the session and its decoder.
     *
     * <p>Idempotent. The decoder is closed but the {@link RenderTarget} is left open because it is
     * owned by the display side. The state moves to {@link ClientState#CLOSED} through the regular
     * transition path, so a listener observes it; later descriptors and units are ignored.</p>
     */
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

    /**
     * Re-attempts decoder creation for the latest recorded descriptor.
     *
     * <p>Only meaningful while {@link ClientState#RESYNCING} with a recorded descriptor. It uses the
     * <i>latest</i> {@code currentDescriptor}, not the descriptor that first entered RESYNCING, so a
     * newer epoch received while blocked is what gets configured. When a required surface is still
     * missing the session stays in RESYNCING and no decoder is created; otherwise the old decoder is
     * closed and a fresh one is configured, moving to WAIT_RANDOM_ACCESS on success or back to
     * RESYNCING on failure.</p>
     */
    public synchronized boolean retryConfigure() {
        if (closed || state != ClientState.RESYNCING || currentDescriptor == null) {
            return false;
        }
        CodecRegistration registration = registry.require(currentDescriptor.getCodecId());
        if (registration.getDescriptor().isSurfaceOutputRequired()
                && renderTarget.getSurface() == null) {
            return false;
        }
        return applyDescriptor(currentDescriptor, registration);
    }

    /**
     * Creates and configures a decoder for the descriptor, replacing any previous decoder.
     *
     * <p>The state moves to {@code WAIT_CONFIG} first, so listeners see the reconfiguration before
     * the old decoder is closed. The render target is then told the new coded size, before any
     * decoder exists for it: a surface target keeps the buffer geometry of its texture, and the
     * old decoder has to be gone before that geometry changes. Configuration failure closes the
     * newly created decoder (if any), records the descriptor and policy, and leaves the session in
     * {@code RESYNCING} so the caller can retry; success installs the decoder and waits for a
     * random-access point.</p>
     */
    private boolean applyDescriptor(StreamDescriptor descriptor,
                                    CodecRegistration registration) {
        setState(ClientState.WAIT_CONFIG);
        if (decoder != null) {
            decoder.close();
            decoder = null;
        }
        renderTarget.setContentSize(descriptor.getCodedWidth(), descriptor.getCodedHeight());
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

    /**
     * Decodes one unit and maps the single decoder error channel to a resync. Other unchecked
     * exceptions are implementation defects and propagate unchanged.
     */
    private boolean decode(EncodedAccessUnit accessUnit) {
        try {
            decoder.decode(accessUnit);
            return true;
        } catch (DecoderException e) {
            setState(ClientState.RESYNCING);
            return false;
        }
    }

    /**
     * Applies a state change and notifies the listener only when the state actually changes, so a
     * listener observes each transition exactly once and never a no-op assignment.
     */
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
