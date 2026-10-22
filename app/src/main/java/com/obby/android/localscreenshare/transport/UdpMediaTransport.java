package com.obby.android.localscreenshare.transport;

import com.obby.android.localscreenshare.core.metrics.PipelineMetrics;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.net.DatagramCipher;
import com.obby.android.localscreenshare.core.net.MediaChannelAware;
import com.obby.android.localscreenshare.core.net.MediaFrame;
import com.obby.android.localscreenshare.core.net.MediaTransportKind;
import com.obby.android.localscreenshare.core.net.UdpMediaPacketCodec;
import com.obby.android.localscreenshare.core.stream.MediaTransport;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link MediaTransport} that fragments access units into encrypted datagrams.
 *
 * <p>Each access unit is cut into fixed-size fragments by {@link UdpMediaPacketCodec} and sealed
 * one by one with the server-to-client key of the channel; the sequence number that feeds the nonce
 * is monotonic for the life of the channel. The unit id is also monotonic and wraps as an
 * {@code int}, which is what the receiver's wrap-safe comparison expects.</p>
 *
 * <p><b>Descriptors.</b> A datagram channel carries access units only: the descriptor of the epoch
 * already reached the viewer on the control plane, and it is the same epoch the channel carries.
 * {@link #sendDescriptor(StreamDescriptor)} therefore does nothing, which keeps the swap sequence
 * (descriptor first, then units) intact without inventing a second descriptor path.</p>
 *
 * <p><b>Ownership.</b> The socket belongs to the session, not to this channel: one bound port
 * carries every call, so {@link #close()} only stops this transport and never closes the socket.
 * A send failure is an {@link IOException}, exactly like the stream channel, and takes the same
 * recovery path back to the carrier.</p>
 */
public final class UdpMediaTransport implements MediaTransport, MediaChannelAware {

    private final DatagramSocket socket;

    private final InetSocketAddress remote;

    private final DatagramCipher cipher;

    private final PipelineMetrics metrics;

    private final AtomicInteger auId = new AtomicInteger();

    private final Object sendLock = new Object();

    private long wireSeq;

    private volatile boolean closed;

    /**
     * Creates a transport for one bound channel.
     *
     * @param socket     session-wide datagram socket; required
     * @param remote     address the channel was bound to; required
     * @param cipher     cipher of the server-to-client direction; required
     * @param metrics    scope that counts sent fragments; required, may be
     *                   {@link PipelineMetrics#NONE}
     * @throws IllegalArgumentException if a required argument is null
     */
    public UdpMediaTransport(DatagramSocket socket, InetSocketAddress remote,
                             DatagramCipher cipher, PipelineMetrics metrics) {
        if (socket == null) {
            throw new IllegalArgumentException("socket must not be null");
        }
        if (remote == null) {
            throw new IllegalArgumentException("remote must not be null");
        }
        if (cipher == null) {
            throw new IllegalArgumentException("cipher must not be null");
        }
        if (metrics == null) {
            throw new IllegalArgumentException("metrics must not be null");
        }
        this.socket = socket;
        this.remote = remote;
        this.cipher = cipher;
        this.metrics = metrics;
    }

    /**
     * Does nothing: descriptors travel on the control plane, not on this channel.
     */
    @Override
    public void sendDescriptor(StreamDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor must not be null");
        }
    }

    @Override
    public void sendAccessUnit(EncodedAccessUnit accessUnit) throws IOException {
        if (accessUnit == null) {
            throw new IllegalArgumentException("accessUnit must not be null");
        }
        MediaFrame frame = MediaFrame.accessUnit(accessUnit.getPtsUs(), MediaFrame.DTS_UNSET,
                accessUnit.getConfigEpoch(), accessUnit.getFlags().isConfig(),
                accessUnit.getFlags().isSyncPoint(), accessUnit.getFlags().isJoinPoint(),
                accessUnit.getJoinInfo() == null
                        ? null : accessUnit.getJoinInfo().getRequiredConfigParts(),
                accessUnit.getPayload());
        synchronized (sendLock) {
            if (closed) {
                throw new IOException("media channel closed");
            }
            int unitId = auId.getAndIncrement();
            int count = UdpMediaPacketCodec.fragmentCount(frame.getPayloadLength());
            for (int index = 0; index < count; index++) {
                long sequence = ++wireSeq;
                byte[] datagram = UdpMediaPacketCodec.encodeFragment(frame, unitId, index,
                        sequence, cipher);
                try {
                    socket.send(new DatagramPacket(datagram, datagram.length, remote));
                } catch (IOException e) {
                    throw new IOException("datagram send failed: " + e.getMessage(), e);
                }
                metrics.increment(PipelineMetrics.Counter.FRAGMENTS_SENT);
            }
        }
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public void abort(String reason) {
        // Nothing can be reported on a datagram path that may already be broken; the receiver's
        // own timeout is what ends the channel.
        closed = true;
    }

    @Override
    public MediaTransportKind getMediaTransportKind() {
        return MediaTransportKind.UDP;
    }

    /**
     * Returns whether this transport was closed.
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Returns the address this channel is bound to.
     */
    public InetSocketAddress getRemoteAddress() {
        return remote;
    }

    /**
     * Returns the last sequence number sent, or zero before the first datagram.
     */
    public long getLastWireSeq() {
        synchronized (sendLock) {
            return wireSeq;
        }
    }

    /**
     * Returns the id the next access unit will carry.
     */
    public int getNextAuId() {
        return auId.get();
    }
}
