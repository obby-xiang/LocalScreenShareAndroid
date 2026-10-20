package com.obby.android.localscreenshare.client;

import com.obby.android.localscreenshare.core.client.ClientMediaSession;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.grpc.screenstream.ServerMessage;
import com.obby.android.localscreenshare.transport.ScreenStreamProtoMapper;

/**
 * Drives a {@link ClientMediaSession} from wire messages on the client's decode thread.
 *
 * <p>The receiver owns the deserialization of server messages and the stream identity, and reports
 * the session-level secure flag to a {@link ClientSecureListener}. It is deliberately not
 * thread-safe: the owning client must call {@link #onServerMessage} from a single decode thread so
 * decoding never runs on the gRPC shared executor. The session itself serializes its own state, so
 * the receiver only has to serialize the deserialization plus the secure-flag edge tracking.</p>
 */
public final class ClientMediaReceiver {

    private final StreamKey streamKey;

    private final ClientMediaSession session;

    private final ClientSecureListener secureListener;

    private Boolean lastSecure;

    /**
     * Creates a receiver for one session.
     *
     * @param streamKey      stream identity used to rebuild descriptors and access units
     * @param session        the session driven by this receiver
     * @param secureListener optional listener for secure-flag changes; may be null
     * @throws IllegalArgumentException if {@code streamKey} or {@code session} is null
     */
    public ClientMediaReceiver(StreamKey streamKey, ClientMediaSession session,
                               ClientSecureListener secureListener) {
        if (streamKey == null) {
            throw new IllegalArgumentException("streamKey must not be null");
        }
        if (session == null) {
            throw new IllegalArgumentException("session must not be null");
        }
        this.streamKey = streamKey;
        this.session = session;
        this.secureListener = secureListener;
    }

    /**
     * Applies one server message to the session.
     *
     * <p>A descriptor is deserialized and applied before its access units; the secure flag is
     * reported on every change from the previous value, including the first descriptor. A unit
     * from a stale or unknown epoch is handled by the session without changing its state
     * incorrectly.</p>
     */
    public void onServerMessage(ServerMessage message) {
        if (message == null || session.isClosed()) {
            return;
        }
        switch (message.getPayloadCase()) {
            case DESCRIPTOR: {
                com.obby.android.localscreenshare.grpc.screenstream.Descriptor descriptor =
                        message.getDescriptor();
                boolean secure = descriptor.getSecure();
                if (lastSecure == null || lastSecure != secure) {
                    lastSecure = secure;
                    if (secureListener != null) {
                        secureListener.onSecureChanged(secure);
                    }
                }
                session.onDescriptor(
                        ScreenStreamProtoMapper.toStreamDescriptor(streamKey, descriptor));
                break;
            }
            case ACCESS_UNIT:
                session.onAccessUnit(ScreenStreamProtoMapper.toAccessUnit(streamKey,
                        message.getAccessUnit()));
                break;
            case PAYLOAD_NOT_SET:
            default:
                break;
        }
    }

    /**
     * Returns the session driven by this receiver.
     */
    public ClientMediaSession getSession() {
        return session;
    }

    /**
     * Closes the underlying session; idempotent.
     */
    public void close() {
        session.close();
    }
}
