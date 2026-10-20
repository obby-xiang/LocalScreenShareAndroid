package com.obby.android.localscreenshare.core.stream;

import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;

import java.io.IOException;

/**
 * Transport used to push stream descriptors and access units to a client.
 *
 * <p><b>Ordering.</b> For every config epoch {@link #sendDescriptor} must be called before the
 * first {@link #sendAccessUnit} of that epoch; the owning session enforces this by draining a
 * pending descriptor first. A transport therefore never has to reorder or synthesize
 * descriptors.</p>
 *
 * <p><b>Threading.</b> A session serializes sends behind its drain lock, so at most one drainer
 * sends at a time; {@link #close()} may be called concurrently from another thread (and from
 * under the session's state lock) while a send is in flight, so an implementation must tolerate
 * that. A send may block, for example on network backpressure, without blocking offers or the
 * encoder. An implementation must not call back into the session that owns it.</p>
 *
 * <p><b>Failure and close.</b> An {@link IOException} or an unchecked exception from a send means
 * the client is unrecoverable: the session detaches, calls {@link #close()} exactly once and
 * rethrows, and the hub removes the session. {@link #close()} must be idempotent and must not
 * throw; it is called by the session, never by the hub, and may follow a failed send.</p>
 */
public interface MediaTransport {

    /**
     * Sends a descriptor for a config epoch.
     *
     * @throws IOException if the client connection failed; the session detaches
     */
    void sendDescriptor(StreamDescriptor descriptor) throws IOException;

    /**
     * Sends one encoded access unit.
     *
     * <p>Called only for units of the epoch whose descriptor was already sent.</p>
     *
     * @throws IOException if the client connection failed; the session detaches
     */
    void sendAccessUnit(EncodedAccessUnit accessUnit) throws IOException;

    /**
     * Releases the connection; idempotent and non-throwing.
     */
    void close();
}
