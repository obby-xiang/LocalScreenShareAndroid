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
 *
 * <p><b>Abnormal termination.</b> {@link #abort(String)} releases the connection and additionally
 * signals the failure to the peer, which {@link #close()} does not have to do. A session uses it
 * when it is revoked while the client may still be waiting for media - for example when a newer
 * connection takes over the same client id - so the client learns about the termination instead of
 * waiting for its own timeout. Both methods are idempotent and may be called from any thread, and a
 * repeated call is a no-op. An abort still reports the failure when {@link #close()} already ran,
 * because a revoked session must not leave the peer waiting; aborting twice reports it only
 * once.</p>
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

    /**
     * Releases the connection and reports {@code reason} to the peer as a terminal failure.
     *
     * <p>Called instead of {@link #close()} when the session is revoked while the peer may still
     * be waiting for media, so the peer does not have to wait for its own deadline. Like
     * {@link #close()} this is idempotent and must not throw; repeated aborts are no-ops, and an
     * abort after {@link #close()} still reports the failure once.</p>
     *
     * @param reason short English description of the termination, safe to send to the peer
     */
    void abort(String reason);
}
