package com.obby.android.localscreenshare.core.stream;

import com.obby.android.localscreenshare.core.codec.EncoderException;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;

/**
 * Callback for a single active stream's runtime events.
 *
 * <p><b>Ordering.</b> {@link #onDescriptorChanged} is delivered before any access unit of the
 * epoch it describes: a deferred codec publishes its final descriptor first, and a descriptor
 * switch notifies listeners before the new encoder's units are forwarded. A failed encoder is
 * reported through {@link #onEncoderFailed}; after that failure the runtime forwards no further
 * units from the failed attempt.</p>
 *
 * <p><b>Threading.</b> Callbacks are invoked outside the runtime's lock, on the thread that
 * produced the event: an encoder drain thread for access units and deferred configuration, the
 * caller's thread for a descriptor switch, and the failing thread for encoder failures. They must
 * not block, must not call {@code start()}, {@code close()} or {@code switchDescriptor()} on the
 * runtime, and must tolerate calls from different threads without external synchronization. An
 * exception thrown by one listener is isolated from the other listeners.</p>
 */
public interface StreamRuntimeListener {

    /**
     * Called for an access unit of the current epoch, already filtered by generation and epoch.
     */
    void onAccessUnit(EncodedAccessUnit accessUnit);

    /**
     * Called once per published descriptor, before the first unit of its epoch.
     *
     * <p>The descriptor is immutable and stays valid after the call. A listener that needs the
     * stream identity before anything is published must use the runtime's stream key instead,
     * because no descriptor exists yet.</p>
     */
    void onDescriptorChanged(StreamDescriptor descriptor);

    /**
     * Called when the current encoder attempt failed and the runtime moved to
     * {@link RuntimeState#FAILED}.
     *
     * <p>The default implementation does nothing, so listeners that do not care need no code. The
     * callback may arrive before or after a descriptor was published, and a published descriptor
     * is not revoked. Implementations must not stop, close or restart the encoder from inside the
     * callback.</p>
     */
    default void onEncoderFailed(StreamKey streamKey, EncoderException failure) {
    }
}
