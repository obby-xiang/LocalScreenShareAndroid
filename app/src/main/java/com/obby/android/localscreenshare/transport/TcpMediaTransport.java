package com.obby.android.localscreenshare.transport;

import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.net.MediaChannelAware;
import com.obby.android.localscreenshare.core.net.MediaFrame;
import com.obby.android.localscreenshare.core.net.MediaFrameCodec;
import com.obby.android.localscreenshare.core.net.MediaTransportKind;
import com.obby.android.localscreenshare.core.stream.MediaTransport;
import com.obby.android.localscreenshare.core.util.TimeSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;

/**
 * {@link MediaTransport} over one established TLS stream.
 *
 * <p>The socket was authenticated and bound to the call before this object existed: the server
 * completed the handshake, checked the offer token and acknowledged the channel. From then on a
 * send is one framed write, serialized behind a private lock because the drain thread is the only
 * writer but the watchdog and the call teardown read the same streams.</p>
 *
 * <p><b>Failure.</b> A write error is an {@link IOException}: the session then asks its recovery
 * hook, which returns the control-plane carrier, so the viewer keeps receiving media. The transport
 * itself never retries and never drops a frame - the queue above it owns that decision.</p>
 *
 * <p><b>Stall.</b> A blocking write is invisible to the drain thread, so every write records when
 * it started and when it last completed. The server watchdog closes a channel whose write has
 * produced no progress for {@link TcpMediaConstants#WRITE_STALL_MS}, which is what turns a wedged
 * socket into the same failure path as a reset one.</p>
 */
public final class TcpMediaTransport implements MediaTransport, MediaChannelAware {

    private final Socket socket;

    private final OutputStream out;

    private final boolean secure;

    private final TimeSource timeSource;

    // Closes the socket off the calling thread; a TLS close writes a close_notify.
    private final MediaChannelCloser closer;

    private final Object writeLock = new Object();

    private final Object closeLock = new Object();

    private volatile long lastSuccessfulWriteMs;

    private volatile long lastWriteAttemptMs;

    private volatile boolean writeAttempted;

    private volatile boolean closed;

    /**
     * Creates a transport over an established channel.
     *
     * @param socket     connected socket, already handshaken when the channel is TLS; required
     * @param secure     whether the control plane of this call reported the stream as secure
     * @param timeSource clock used for the stall detection; required
     * @throws IOException if the socket streams cannot be obtained
     */
    public TcpMediaTransport(Socket socket, boolean secure, TimeSource timeSource)
            throws IOException {
        this(socket, secure, timeSource, MediaChannelCloser.daemon());
    }

    /**
     * Creates a transport that closes its socket through {@code closer}.
     *
     * @param socket     connected socket, already handshaken when the channel is TLS; required
     * @param secure     whether the control plane of this call reported the stream as secure
     * @param timeSource clock used for the stall detection; required
     * @param closer     closer of the channel socket; required
     * @throws IOException if the socket streams cannot be obtained
     */
    public TcpMediaTransport(Socket socket, boolean secure, TimeSource timeSource,
                             MediaChannelCloser closer) throws IOException {
        if (socket == null) {
            throw new IllegalArgumentException("socket must not be null");
        }
        if (timeSource == null) {
            throw new IllegalArgumentException("timeSource must not be null");
        }
        if (closer == null) {
            throw new IllegalArgumentException("closer must not be null");
        }
        this.socket = socket;
        this.secure = secure;
        this.timeSource = timeSource;
        this.closer = closer;
        socket.setTcpNoDelay(true);
        this.out = socket.getOutputStream();
        this.lastSuccessfulWriteMs = timeSource.nowMs();
    }

    @Override
    public void sendDescriptor(StreamDescriptor descriptor) throws IOException {
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor must not be null");
        }
        writeFrame(MediaFrame.descriptor(0L, descriptor.getConfigEpoch(),
                ScreenStreamProtoMapper.toProtoDescriptor(descriptor, secure).toByteArray()));
    }

    @Override
    public void sendAccessUnit(EncodedAccessUnit accessUnit) throws IOException {
        if (accessUnit == null) {
            throw new IllegalArgumentException("accessUnit must not be null");
        }
        // Version one carries no decode timestamp: H.264 without B frames decodes in presentation
        // order, so a receiver derives dts from pts instead of paying eight bytes per unit for a
        // value it already knows.
        writeFrame(MediaFrame.accessUnit(accessUnit.getPtsUs(), MediaFrame.DTS_UNSET,
                accessUnit.getConfigEpoch(), accessUnit.getFlags().isConfig(),
                accessUnit.getFlags().isSyncPoint(), accessUnit.getFlags().isJoinPoint(),
                accessUnit.getJoinInfo() == null
                        ? null : accessUnit.getJoinInfo().getRequiredConfigParts(),
                accessUnit.getPayload()));
    }

    @Override
    public void close() {
        closeQuietly();
    }

    @Override
    public void abort(String reason) {
        // A terminal notice cannot be written on a socket that is being abandoned; closing it is
        // the only signal the peer can observe, and its reader reports the end of the channel.
        closeQuietly();
    }

    @Override
    public MediaTransportKind getMediaTransportKind() {
        return MediaTransportKind.TCP;
    }

    /**
     * Returns whether a write has produced no progress for the stall window.
     *
     * <p>False before the first send and after the channel was closed, so an idle channel is never
     * mistaken for a wedged one.</p>
     *
     * @param nowMs current time on the transport's clock
     */
    public boolean isWriteStalled(long nowMs) {
        if (closed || !writeAttempted) {
            return false;
        }
        long lastProgress = Math.max(lastSuccessfulWriteMs, lastWriteAttemptMs);
        return nowMs - lastProgress > TcpMediaConstants.WRITE_STALL_MS;
    }

    /**
     * Returns whether this transport was closed.
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Returns the remote port of the channel, for logs and tests.
     */
    public int getPort() {
        return socket.getPort();
    }

    private void writeFrame(MediaFrame frame) throws IOException {
        synchronized (writeLock) {
            if (closed) {
                throw new IOException("media channel closed");
            }
            writeAttempted = true;
            lastWriteAttemptMs = timeSource.nowMs();
            MediaFrameCodec.write(out, frame);
            lastSuccessfulWriteMs = timeSource.nowMs();
        }
    }

    /**
     * Closes the channel; the socket itself is closed off the calling thread.
     *
     * <p>The transport counts as closed immediately, so a write that is in flight fails instead of
     * waiting for a peer that is gone, while the socket close - which writes a {@code close_notify}
     * on a TLS channel - is performed by {@link MediaChannelCloser}. That is what lets a teardown on
     * the main thread close a channel without performing network I/O there.</p>
     */
    private void closeQuietly() {
        synchronized (closeLock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        closer.closeQuietly(socket);
    }
}
