package com.obby.android.localscreenshare.transport;

import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.stream.MediaTransport;
import com.obby.android.localscreenshare.grpc.screenstream.ServerMessage;

import java.io.IOException;

import io.grpc.stub.StreamObserver;

/**
 * Server-to-client {@link MediaTransport} backed by a single gRPC {@link StreamObserver}.
 *
 * <p>The observer is not thread-safe, so every send is serialized behind a private lock. The
 * owning {@link com.obby.android.localscreenshare.core.stream.ClientSendSession} already guarantees
 * at most one drainer per session, but a transport must not assume that and additionally guards
 * against concurrent sends and a concurrent {@link #close()}.</p>
 *
 * <p>The {@code secure} flag is fixed at construction and attached to every descriptor; it is
 * session-level metadata, not a per-unit property.</p>
 */
public final class GrpcMediaTransport implements MediaTransport {

    private final StreamObserver<ServerMessage> observer;

    private final boolean secure;

    private final Object sendLock = new Object();

    private boolean closed;

    /**
     * Creates a transport over {@code observer}.
     *
     * @throws IllegalArgumentException if {@code observer} is null
     */
    public GrpcMediaTransport(StreamObserver<ServerMessage> observer, boolean secure) {
        if (observer == null) {
            throw new IllegalArgumentException("observer must not be null");
        }
        this.observer = observer;
        this.secure = secure;
    }

    @Override
    public void sendDescriptor(StreamDescriptor descriptor) throws IOException {
        send(ServerMessage.newBuilder()
                .setDescriptor(ScreenStreamProtoMapper.toProtoDescriptor(descriptor, secure))
                .build());
    }

    @Override
    public void sendAccessUnit(EncodedAccessUnit accessUnit) throws IOException {
        send(ServerMessage.newBuilder()
                .setAccessUnit(ScreenStreamProtoMapper.toProtoAccessUnit(accessUnit))
                .build());
    }

    @Override
    public void close() {
        synchronized (sendLock) {
            closed = true;
        }
    }

    private void send(ServerMessage message) throws IOException {
        synchronized (sendLock) {
            if (closed) {
                throw new IOException("transport is closed");
            }
            observer.onNext(message);
        }
    }
}
