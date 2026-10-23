package com.obby.android.localscreenshare.transport;

import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.client.MediaChannelLink;
import com.obby.android.localscreenshare.core.net.MediaChannelHandshake;
import com.obby.android.localscreenshare.core.net.MediaChannelStatusListener;
import com.obby.android.localscreenshare.core.net.MediaChannelListener;
import com.obby.android.localscreenshare.core.net.MediaChannelOffer;
import com.obby.android.localscreenshare.core.net.MediaFrame;
import com.obby.android.localscreenshare.core.net.MediaFrameCodec;
import com.obby.android.localscreenshare.core.model.AccessUnitFlags;
import com.obby.android.localscreenshare.core.model.JoinInfo;
import com.obby.android.localscreenshare.grpc.screenstream.Descriptor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * Client side of one dedicated stream channel: connects, greets, then delivers frames.
 *
 * <p>{@link #start()} blocks for the whole life of the channel and must therefore run on a thread
 * of its own. It opens the socket, completes the TLS handshake with the same identity and pin the
 * control stream used, writes the hello of the offer it belongs to and reads the server's
 * acknowledgement. Only after an {@code OK} does it report the channel ready and enter the frame
 * loop, so a receiver never sees a frame from a socket that was not bound to the call.</p>
 *
 * <p>Frames are decoded into the framework model and handed to a single
 * {@link MediaChannelListener}; that listener owns the threading decision, which is what keeps the
 * receive metrics in exactly one place no matter which channel delivered the bytes.</p>
 *
 * <p><b>Failure.</b> Every abnormal end - connect timeout, TLS failure, rejected greeting, EOF, a
 * malformed frame - is reported exactly once through the status listener and through
 * {@link MediaChannelListener#onChannelFailed(String)}, and the socket is closed. A channel closed
 * from the outside reports nothing, because its end was intentional.</p>
 */
public final class TcpMediaReceiver implements MediaChannelLink {

    private final StreamKey streamKey;

    private final String host;

    private final MediaChannelOffer offer;

    private final MediaChannelListener listener;

    private final MediaChannelStatusListener statusListener;

    private final SSLContext sslContext;

    private final Object closeLock = new Object();

    private volatile boolean closed;

    private boolean failed;

    // Sequence assigned to the access units of this channel, see deliver().
    private long nextSequence;

    private Socket socket;

    // Closes this channel's socket off the calling thread; see MediaChannelCloser.
    private final MediaChannelCloser closer;

    // Whether the socket was already handed to the closer, so repeated close() calls queue one.
    private boolean closeHandedOff;

    /**
     * Creates a receiver for one offer.
     *
     * @param streamKey      stream identity, implicit on the wire; required
     * @param host           address to connect to, already resolved from the offer or the control
     *                       plane; required
     * @param offer          offer this channel answers; required
     * @param listener       receiver of descriptors and access units; required
     * @param statusListener receiver of the lifecycle events; required
     * @param sslContext     client context built by {@link MediaTlsFactory}, or null for the
     *                       plaintext debug transport
     * @throws IllegalArgumentException if a required argument is null
     */
    public TcpMediaReceiver(StreamKey streamKey, String host, MediaChannelOffer offer,
                            MediaChannelListener listener, MediaChannelStatusListener statusListener,
                            SSLContext sslContext) {
        this(streamKey, host, offer, listener, statusListener, sslContext,
                MediaChannelCloser.daemon());
    }

    /**
     * Creates a receiver that closes its socket through {@code closer}.
     *
     * @param streamKey      stream identity, implicit on the wire; required
     * @param host           address to connect to, already resolved from the offer or the control
     *                       plane; required
     * @param offer          offer this channel answers; required
     * @param listener       receiver of descriptors and access units; required
     * @param statusListener receiver of the lifecycle events; required
     * @param sslContext     client context built by {@link MediaTlsFactory}, or null for the
     *                       plaintext debug transport
     * @param closer         closer of the channel socket; required
     * @throws IllegalArgumentException if a required argument is null
     */
    public TcpMediaReceiver(StreamKey streamKey, String host, MediaChannelOffer offer,
                            MediaChannelListener listener, MediaChannelStatusListener statusListener,
                            SSLContext sslContext, MediaChannelCloser closer) {
        if (streamKey == null) {
            throw new IllegalArgumentException("streamKey must not be null");
        }
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("host must not be empty");
        }
        if (offer == null) {
            throw new IllegalArgumentException("offer must not be null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        if (statusListener == null) {
            throw new IllegalArgumentException("statusListener must not be null");
        }
        if (closer == null) {
            throw new IllegalArgumentException("closer must not be null");
        }
        this.streamKey = streamKey;
        this.host = host;
        this.offer = offer;
        this.listener = listener;
        this.statusListener = statusListener;
        this.sslContext = sslContext;
        this.closer = closer;
    }

    /**
     * Connects, greets and delivers frames until the channel ends; blocks the calling thread.
     */
    @Override
    public void start() {
        Socket raw = null;
        try {
            raw = new Socket();
            raw.setTcpNoDelay(true);
            raw.connect(new InetSocketAddress(host, offer.getPort()),
                    (int) TcpMediaConstants.CONNECT_TIMEOUT_MS);
            Socket channel = raw;
            if (sslContext != null) {
                SSLSocket ssl = (SSLSocket) sslContext.getSocketFactory()
                        .createSocket(raw, host, offer.getPort(), true);
                MediaTlsFactory.configureClientSocket(ssl);
                ssl.setSoTimeout((int) TcpMediaConstants.HANDSHAKE_TIMEOUT_MS);
                ssl.startHandshake();
                channel = ssl;
            } else {
                channel.setSoTimeout((int) TcpMediaConstants.HANDSHAKE_TIMEOUT_MS);
            }
            synchronized (closeLock) {
                if (closed) {
                    closeQuietly(channel);
                    return;
                }
                socket = channel;
            }
            if (!greet(channel)) {
                return;
            }
            statusListener.onChannelReady();
            readFrames(channel);
        } catch (IOException | RuntimeException e) {
            fail(reasonOf(e));
        } finally {
            closeQuietly(socket);
            if (socket == null && raw != null) {
                closeQuietly(raw);
            }
        }
    }

    /**
     * Closes the channel from the outside; idempotent and safe from any thread.
     *
     * <p>The socket itself is closed through {@link MediaChannelCloser}, because a TLS close writes
     * a {@code close_notify} and must not run on the main thread of the process. The channel counts
     * as closed the moment this method returns, so a write or a read stops immediately even though
     * the socket is still being torn down.</p>
     */
    @Override
    public void close() {
        final Socket current;
        synchronized (closeLock) {
            closed = true;
            if (socket == null || closeHandedOff) {
                return;
            }
            closeHandedOff = true;
            current = socket;
        }
        closer.closeQuietly(current);
    }

    private boolean greet(Socket channel) throws IOException {
        OutputStream out = channel.getOutputStream();
        out.write(MediaChannelHandshake.encodeHello(offer.getTransport(), offer.getChannelId(),
                offer.getToken()));
        out.flush();
        byte[] ack = new byte[MediaChannelHandshake.ACK_BYTES];
        readFully(channel.getInputStream(), ack);
        MediaChannelHandshake.Status status = MediaChannelHandshake.decodeAck(ack);
        if (status == MediaChannelHandshake.Status.OK) {
            channel.setSoTimeout(0);
            return true;
        }
        fail("handshake_" + (status == null ? "unknown" : status.name().toLowerCase(
                java.util.Locale.ROOT)));
        return false;
    }

    private void readFrames(Socket channel) throws IOException {
        InputStream in = channel.getInputStream();
        while (!closed) {
            long arrivalNs = System.nanoTime();
            MediaFrame frame = MediaFrameCodec.read(in);
            deliver(frame, arrivalNs);
        }
    }

    private void deliver(MediaFrame frame, long arrivalNs) throws IOException {
        if (frame.getType() == MediaFrame.Type.DESCRIPTOR) {
            StreamDescriptor descriptor = ScreenStreamProtoMapper.toStreamDescriptor(streamKey,
                    Descriptor.parseFrom(frame.getPayload()));
            listener.onDescriptor(descriptor, offer.isSecure());
            return;
        }
        // The payload of an access unit is the encoded bitstream, not a protocol message: the
        // header carries everything else. Version one sends no decode timestamp, so it is derived
        // from the presentation one instead of being guessed by the decoder.
        long dtsUs = frame.hasDtsUs() ? frame.getDtsUs() : frame.getPtsUs();
        AccessUnitFlags flags = AccessUnitFlags.of(frame.isSyncPoint(), frame.isJoinPoint(),
                frame.isConfig());
        JoinInfo joinInfo = (frame.isJoinPoint() || !frame.getJoinParts().isEmpty())
                ? new JoinInfo(frame.getConfigEpoch(), frame.getJoinParts()) : null;
        // The frame has no sequence field: the channel is an ordered stream, so a local counter is
        // exact - a gap can only come from a drop this side performed, never from the wire.
        EncodedAccessUnit accessUnit = new EncodedAccessUnit(streamKey, frame.getConfigEpoch(),
                nextSequence++, frame.getPtsUs(), dtsUs, flags, joinInfo, null,
                frame.getPayload());
        listener.onAccessUnit(accessUnit, arrivalNs);
    }

    private void fail(String reason) {
        synchronized (closeLock) {
            if (closed || failed) {
                return;
            }
            failed = true;
        }
        listener.onChannelFailed(reason);
        statusListener.onChannelFailed(reason);
    }

    private static String reasonOf(Throwable error) {
        if (error instanceof java.net.SocketTimeoutException) {
            return "connect_timeout";
        }
        if (error instanceof javax.net.ssl.SSLException) {
            return "tls_failed";
        }
        if (error instanceof java.io.EOFException) {
            return "channel_closed";
        }
        if (error instanceof IOException) {
            return "channel_io";
        }
        return "channel_failed";
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
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing an already broken socket is not an event.
        }
    }
}
