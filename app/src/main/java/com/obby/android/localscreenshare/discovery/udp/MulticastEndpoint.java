package com.obby.android.localscreenshare.discovery.udp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;

/**
 * One bound multicast socket, abstracted down to the two operations the transport needs.
 *
 * <p>The abstraction exists so the receive loop, the error budget and the rebuild behaviour can be
 * exercised on the JVM with fake endpoints; production endpoints wrap a real
 * {@link java.net.MulticastSocket}. Implementations may be called from different threads: one
 * receiver thread reads, while the announcer may send at the same time.</p>
 */
public interface MulticastEndpoint extends Closeable {

    /** Returns the name of the interface this endpoint is bound to, for logs and diagnostics. */
    @NonNull
    String interfaceName();

    /**
     * Blocks until one datagram arrives and copies it into {@code buffer}.
     *
     * @return the number of bytes copied
     * @throws IOException if the socket failed or was closed
     */
    int receive(@NonNull byte[] buffer) throws IOException;

    /**
     * Returns the sender of the datagram returned by the last {@link #receive(byte[])} call.
     *
     * @return the sender address, or null when none is known
     */
    @Nullable
    InetAddress lastSender();

    /** Sends one datagram to the configured group; called from the announcement scheduler. */
    void send(@NonNull byte[] payload) throws IOException;
}
