package com.obby.android.localscreenshare.transport;

import com.obby.android.localscreenshare.core.net.MediaChannelHandshake;
import com.obby.android.localscreenshare.core.net.MediaChannelTicket;
import com.obby.android.localscreenshare.core.net.MediaTransportKind;
import com.obby.android.localscreenshare.core.util.TimeSource;
import com.obby.android.localscreenshare.identity.SpkiId;

import org.apache.commons.lang3.concurrent.BasicThreadFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/**
 * Listening side of the dedicated stream channel.
 *
 * <p>The listener is bound once per sharing session and lives as long as the control-plane port
 * does. A client that answered an offer opens a socket, proves its identity with the certificate
 * the control plane already verified, presents the token that came with the offer, and is bound to
 * that call; only then does the socket start carrying frames. Every failure has its own
 * acknowledgement code, so a client can tell "this call is gone" from "that token is wrong" from
 * "you are not the device we offered this to".</p>
 *
 * <p><b>Binding.</b> The preferred port is the control-plane port plus one, which keeps the two
 * endpoints of one device adjacent and predictable in logs; when it is taken the listener falls
 * back to an ephemeral port. A listener that cannot bind at all does not fail sharing: the caller
 * marks the channel unavailable and every call simply stays on the control-plane carrier.</p>
 *
 * <p><b>Watchdog.</b> One scheduled check per second closes channels whose write made no progress
 * for the stall window. Closing the socket is what unblocks the write, and the resulting
 * {@link IOException} is what moves the call back to its carrier, so a wedged socket takes the same
 * path as a reset one.</p>
 *
 * <p><b>One channel per call.</b> A second socket for the same channel is refused as busy; the
 * registry hands the first one out and forgets it, which is what makes the ticket single use.</p>
 */
public final class MediaChannelServer {

    /**
     * Reason reported when the process would not accept another channel socket.
     */
    public static final String REASON_STOPPED = "server_stopped";

    private final SSLContext serverContext;

    private final MediaChannelTicketAuthority authority;

    private final TimeSource timeSource;

    private final List<TcpMediaTransport> channels = new CopyOnWriteArrayList<>();

    private final ExecutorService connectionExecutor = Executors.newCachedThreadPool(
            new BasicThreadFactory.Builder().namingPattern("lss-media-rx-%d").build());

    private final ExecutorService watchdogExecutor = Executors.newSingleThreadExecutor(
            new BasicThreadFactory.Builder().namingPattern("lss-media-watchdog").build());

    private volatile boolean running;

    private ServerSocket serverSocket;

    private int port;

    /**
     * Creates a listener that has not bound a port yet.
     *
     * @param serverContext TLS context of the media channel, or null to serve the plaintext debug
     *                      transport
     * @param authority     lookup of the outstanding offers; required
     * @param timeSource    clock used by the watchdog; required
     * @throws IllegalArgumentException if a required argument is null
     */
    public MediaChannelServer(SSLContext serverContext, MediaChannelTicketAuthority authority,
                              TimeSource timeSource) {
        if (authority == null) {
            throw new IllegalArgumentException("authority must not be null");
        }
        if (timeSource == null) {
            throw new IllegalArgumentException("timeSource must not be null");
        }
        this.serverContext = serverContext;
        this.authority = authority;
        this.timeSource = timeSource;
    }

    /**
     * Binds the listener and starts accepting channels.
     *
     * @param preferredPort port to try first; zero binds an ephemeral port directly
     * @return the port the listener actually bound
     * @throws IOException if neither the preferred nor an ephemeral port can be bound
     */
    public int start(int preferredPort) throws IOException {
        ServerSocket bound;
        try {
            bound = bind(preferredPort);
        } catch (IOException preferredFailure) {
            bound = bind(0);
        }
        serverSocket = bound;
        port = bound.getLocalPort();
        running = true;
        Thread accept = new Thread(this::acceptLoop, "lss-media-accept");
        accept.setDaemon(true);
        accept.start();
        watchdogExecutor.execute(this::watchdogLoop);
        return port;
    }

    /**
     * Stops accepting and closes every channel; idempotent.
     */
    public void stop() {
        running = false;
        ServerSocket socket = serverSocket;
        serverSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // The accept loop is already unwinding.
            }
        }
        for (TcpMediaTransport transport : channels) {
            transport.close();
        }
        channels.clear();
        connectionExecutor.shutdownNow();
        watchdogExecutor.shutdownNow();
    }

    /**
     * Returns the bound port, or zero before {@link #start(int)}.
     */
    public int getPort() {
        return port;
    }

    /**
     * Returns whether the listener is accepting channels.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Returns the number of channels that completed the greeting.
     */
    public int getChannelCount() {
        return channels.size();
    }

    private ServerSocket bind(int requestedPort) throws IOException {
        ServerSocket socket = serverContext == null
                ? new ServerSocket()
                : serverContext.getServerSocketFactory().createServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(requestedPort), 4);
            if (socket instanceof SSLServerSocket) {
                SSLServerSocket ssl = (SSLServerSocket) socket;
                ssl.setNeedClientAuth(true);
                ssl.setEnabledProtocols(MediaTlsFactory.commonProtocols(
                        ssl.getSupportedProtocols()));
            }
            return socket;
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Nothing was bound.
            }
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    private void acceptLoop() {
        while (running) {
            final Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                if (running) {
                    // A single failed accept must not end the listener; the next one may succeed.
                    continue;
                }
                return;
            }
            try {
                connectionExecutor.execute(() -> handle(socket));
            } catch (RejectedExecutionException e) {
                closeQuietly(socket);
            }
        }
    }

    private void handle(Socket accepted) {
        try {
            accepted.setTcpNoDelay(true);
            accepted.setSoTimeout((int) TcpMediaConstants.HANDSHAKE_TIMEOUT_MS);
            if (accepted instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket) accepted;
                MediaTlsFactory.configureServerSocket(ssl);
                ssl.startHandshake();
            }
            InputStream in = accepted.getInputStream();
            OutputStream out = accepted.getOutputStream();
            byte[] helloBytes = new byte[MediaChannelHandshake.HELLO_BYTES];
            readFully(in, helloBytes);
            MediaChannelHandshake.Hello hello = MediaChannelHandshake.decodeHello(helloBytes);
            if (hello.getTransport() != MediaTransportKind.TCP) {
                reject(out, MediaChannelHandshake.Status.BAD_CHANNEL);
                return;
            }
            MediaChannelTicket ticket = authority.ticketFor(hello.getChannelIdHex());
            if (ticket == null || ticket.isConsumed() || ticket.isExpired(timeSource.nowMs())) {
                reject(out, MediaChannelHandshake.Status.BAD_CHANNEL);
                return;
            }
            if (ticket.getPeerDeviceId() != null
                    && !ticket.getPeerDeviceId().equals(peerDeviceId(accepted))) {
                reject(out, MediaChannelHandshake.Status.PEER_MISMATCH);
                return;
            }
            if (!ticket.consume(hello.getToken(), timeSource.nowMs())) {
                reject(out, MediaChannelHandshake.Status.BAD_TOKEN);
                return;
            }
            out.write(MediaChannelHandshake.encodeAck(MediaChannelHandshake.Status.OK));
            out.flush();
            accepted.setSoTimeout(0);
            TcpMediaTransport transport = new TcpMediaTransport(accepted,
                    serverContext != null, timeSource);
            channels.add(transport);
            authority.onChannelEstablished(hello.getChannelIdHex(), transport);
        } catch (IOException | RuntimeException e) {
            closeQuietly(accepted);
        }
    }

    private void reject(OutputStream out, MediaChannelHandshake.Status status) {
        try {
            out.write(MediaChannelHandshake.encodeAck(status));
            out.flush();
        } catch (IOException ignored) {
            // The peer already left; the status it never read costs nothing.
        }
    }

    private String peerDeviceId(Socket socket) {
        if (!(socket instanceof SSLSocket)) {
            return null;
        }
        try {
            Certificate[] chain = ((SSLSocket) socket).getSession().getPeerCertificates();
            if (chain == null || chain.length == 0 || !(chain[0] instanceof X509Certificate)) {
                return null;
            }
            byte[] spki = ((X509Certificate) chain[0]).getPublicKey().getEncoded();
            return spki == null ? null : SpkiId.deviceId(spki);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void watchdogLoop() {
        while (running) {
            try {
                TimeUnit.MILLISECONDS.sleep(TcpMediaConstants.WATCHDOG_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            long nowMs = timeSource.nowMs();
            for (TcpMediaTransport transport : channels) {
                if (transport.isWriteStalled(nowMs)) {
                    channels.remove(transport);
                    transport.abort("write_stalled");
                } else if (transport.isClosed()) {
                    channels.remove(transport);
                }
            }
        }
    }

    private static void readFully(InputStream in, byte[] target) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int read = in.read(target, offset, target.length - offset);
            if (read < 0) {
                throw new java.io.EOFException("channel closed during the greeting");
            }
            offset += read;
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing an already broken socket is not an event.
        }
    }

    /**
     * Returns a short lowercase description of a status, for logs.
     */
    static String labelOf(MediaChannelHandshake.Status status) {
        return status == null ? "unknown" : status.name().toLowerCase(Locale.ROOT);
    }
}
