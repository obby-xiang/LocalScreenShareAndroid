package com.obby.android.localscreenshare.discovery.udp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.Closeable;
import java.net.InetAddress;

/**
 * Multicast datagram transport used by both the announcer and the discovery provider.
 *
 * <p>The transport owns the lifetime of the interface sockets. A discovery provider starts it
 * with a listener and receives decoded candidates on the receiver threads; the announcer starts it
 * without a listener, because it only sends. All methods are safe to call from any thread.</p>
 */
public interface MulticastTransport extends Closeable {

    /** Receives datagrams and transport-level failures from the receiver threads. */
    interface Listener {
        /**
         * Called on a receiver thread for every datagram.
         *
         * @param payload datagram bytes, never null
         * @param sender source address, never null
         */
        void onPacket(@NonNull byte[] payload, @NonNull InetAddress sender);

        /** Reports a transport-level failure that did not produce a packet. */
        void onTransportError(@NonNull String reason);
    }

    /**
     * Opens the sockets and, when {@code listener} is non-null, starts one receiver thread per
     * endpoint. A null listener creates a send-only transport.
     */
    void start(@Nullable Listener listener);

    /** Sends one datagram through every open endpoint; failures are reported, never thrown. */
    void send(@NonNull byte[] payload);

    /** Stops receiving, closes every socket and releases the rebuild scheduler. */
    @Override
    void close();
}
