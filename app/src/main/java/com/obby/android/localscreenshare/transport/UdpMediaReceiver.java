package com.obby.android.localscreenshare.transport;

import com.obby.android.localscreenshare.client.MediaChannelLink;
import com.obby.android.localscreenshare.core.metrics.PipelineMetrics;
import com.obby.android.localscreenshare.core.model.AccessUnitFlags;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.JoinInfo;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.net.AccessUnitReassembler;
import com.obby.android.localscreenshare.core.net.DatagramCipher;
import com.obby.android.localscreenshare.core.net.DatagramLossTracker;
import com.obby.android.localscreenshare.core.net.DatagramReorderWindow;
import com.obby.android.localscreenshare.core.net.MediaChannelListener;
import com.obby.android.localscreenshare.core.net.MediaChannelOffer;
import com.obby.android.localscreenshare.core.net.MediaChannelStatusListener;
import com.obby.android.localscreenshare.core.net.MediaFrame;
import com.obby.android.localscreenshare.core.net.UdpBindPacket;
import com.obby.android.localscreenshare.core.net.UdpMediaPacket;
import com.obby.android.localscreenshare.core.net.UdpMediaPacketCodec;
import com.obby.android.localscreenshare.core.util.NanoClock;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;

/**
 * Client side of a datagram media channel: binds the socket, then reassembles access units.
 *
 * <p>{@link #start()} blocks for the life of the channel and must run on a thread of its own. It
 * sends the bind packet of the offer, reports the channel ready while the server is still working on
 * the swap, and then waits up to {@value UdpMediaConstants#FIRST_FRAME_TIMEOUT_MS} milliseconds for
 * the first authenticated datagram - a silent server is reported as {@code udp_bind_timeout}. The
 * window only bounds the first datagram: once the channel carries data it has no timeout at all.</p>
 *
 * <p><b>Order of checks per datagram.</b> The sequence number is compared with the reorder window
 * before anything is decrypted, so a replay of something long past costs nothing; the datagram is
 * then authenticated, and only an authenticated one is recorded in the window. Recording first
 * would let a forged datagram claim a sequence number and the genuine one be discarded as a
 * duplicate.</p>
 *
 * <p><b>Loss.</b> A reassembled unit is handed to the media listener; a unit that had to be
 * abandoned makes the listener catch up and feeds the loss tracker, which asks the owner for a join
 * point when the loss turns into a trend. Units are delivered on the thread that reads the socket,
 * so the listener must marshal them the way it marshals stream messages.</p>
 *
 * <p>Every abnormal end is reported once through the status listener and through
 * {@link MediaChannelListener#onChannelFailed(String)}; a close from the outside reports nothing.</p>
 */
public final class UdpMediaReceiver implements MediaChannelLink {

    private final StreamKey streamKey;

    private final String host;

    private final MediaChannelOffer offer;

    private final MediaChannelListener listener;

    private final MediaChannelStatusListener statusListener;

    private final NanoClock clock;

    private final PipelineMetrics metrics;

    private final DatagramReorderWindow window = new DatagramReorderWindow();

    private final DatagramLossTracker lossTracker;

    private final AccessUnitReassembler reassembler;

    private final Object closeLock = new Object();

    private volatile boolean closed;

    private boolean failed;

    private DatagramSocket socket;

    private long nextSequence;

    // Arrival time of the datagram being processed, handed to the unit it completes.
    private long lastArrivalNs;

    /**
     * Creates a receiver for one datagram offer.
     *
     * @param streamKey      stream identity the frames belong to; required
     * @param host           address of the server, already resolved; required
     * @param offer          offer this channel answers, with its keys; required
     * @param listener       receiver of access units; required
     * @param statusListener receiver of the lifecycle events; required
     * @param clock          clock used by the reassembler and the loss tracker; required
     * @param metrics        scope that counts datagrams; required, may be
     *                       {@link PipelineMetrics#NONE}
     * @throws IllegalArgumentException if a required argument is null
     */
    public UdpMediaReceiver(StreamKey streamKey, String host, MediaChannelOffer offer,
                            MediaChannelListener listener, MediaChannelStatusListener statusListener,
                            NanoClock clock, PipelineMetrics metrics) {
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
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        if (metrics == null) {
            throw new IllegalArgumentException("metrics must not be null");
        }
        this.streamKey = streamKey;
        this.host = host;
        this.offer = offer;
        this.listener = listener;
        this.statusListener = statusListener;
        this.clock = clock;
        this.metrics = metrics;
        this.lossTracker = new DatagramLossTracker(clock);
        this.reassembler = new AccessUnitReassembler(new FrameSink(), clock);
    }

    /**
     * Binds, greets and receives datagrams until the channel ends; blocks the calling thread.
     */
    @Override
    public void start() {
        DatagramSocket datagramSocket = null;
        try {
            datagramSocket = new DatagramSocket();
            datagramSocket.connect(new InetSocketAddress(host, offer.getPort()));
            synchronized (closeLock) {
                if (closed) {
                    closeQuietly(datagramSocket);
                    return;
                }
                socket = datagramSocket;
            }
            bind();
            statusListener.onChannelReady();
            receive(datagramSocket);
        } catch (SocketTimeoutException e) {
            fail("udp_bind_timeout");
        } catch (IOException | RuntimeException e) {
            fail(reasonOf(e));
        } finally {
            closeQuietly(datagramSocket);
        }
    }

    /**
     * Closes the channel from the outside; idempotent and safe from any thread.
     */
    @Override
    public void close() {
        synchronized (closeLock) {
            closed = true;
        }
        closeQuietly(socket);
    }

    /**
     * Returns how many datagrams were dropped by the reorder window as duplicates.
     */
    public long getDuplicateCount() {
        return window.getDuplicates();
    }

    /**
     * Returns how many datagrams failed authentication.
     */
    public long getAuthenticationFailures() {
        return metrics.count(PipelineMetrics.Counter.MEDIA_AUTH_FAILURES);
    }

    private void bind() throws IOException {
        DatagramCipher cipher = new DatagramCipher(offer.getKeyC2s(), offer.getNonceSalt());
        byte[] datagram = UdpBindPacket.encode(offer.getChannelId(), offer.getToken(), cipher,
                ++nextSequence);
        DatagramSocket datagramSocket = socket;
        if (datagramSocket == null) {
            throw new IOException("channel closed during bind");
        }
        datagramSocket.send(new DatagramPacket(datagram, datagram.length,
                new InetSocketAddress(host, offer.getPort())));
        // READY is reported right after this write, but the first datagram needs a control-plane
        // round trip, the server's swap and a join point; the window covers all three.
        datagramSocket.setSoTimeout((int) UdpMediaConstants.FIRST_FRAME_TIMEOUT_MS);
    }

    private void receive(DatagramSocket datagramSocket) throws IOException {
        DatagramCipher cipher = new DatagramCipher(offer.getKeyS2c(), offer.getNonceSalt());
        int bufferSize = Math.max(UdpMediaConstants.DEFAULT_MTU,
                offer.getMtu() > 0 ? offer.getMtu() : 0) + UdpMediaConstants.RECEIVE_SLACK_BYTES;
        byte[] buffer = new byte[bufferSize];
        boolean ready = false;
        while (!closed) {
            DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
            datagramSocket.receive(datagram);
            if (!ready) {
                // The first authenticated datagram is the bind acknowledgement of this design.
                datagramSocket.setSoTimeout(0);
                ready = true;
            }
            byte[] bytes = new byte[datagram.getLength()];
            System.arraycopy(datagram.getData(), datagram.getOffset(), bytes, 0, bytes.length);
            accept(bytes, cipher);
        }
    }

    private void accept(byte[] datagram, DatagramCipher cipher) {
        long arrivalNs = clock.nowNs();
        if (datagram.length < UdpMediaPacketCodec.SEQUENCE_BYTES) {
            return;
        }
        long wireSeq = readSequence(datagram);
        if (window.isStale(wireSeq)) {
            return;
        }
        UdpMediaPacket packet;
        try {
            packet = UdpMediaPacketCodec.decode(datagram, cipher);
        } catch (DatagramCipher.AuthenticationException e) {
            metrics.increment(PipelineMetrics.Counter.MEDIA_AUTH_FAILURES);
            return;
        } catch (IOException e) {
            return;
        }
        metrics.increment(PipelineMetrics.Counter.DATAGRAMS_RECEIVED);
        long lostBefore = window.getLost();
        long reorderedBefore = window.getReordered();
        DatagramReorderWindow.Decision decision = window.accept(wireSeq);
        long lostAfter = window.getLost();
        if (lostAfter > lostBefore) {
            metrics.add(PipelineMetrics.Counter.DATAGRAMS_LOST, lostAfter - lostBefore);
        }
        if (decision == DatagramReorderWindow.Decision.DUPLICATE) {
            metrics.increment(PipelineMetrics.Counter.DATAGRAMS_DUPLICATE);
            return;
        }
        if (window.getReordered() > reorderedBefore) {
            metrics.increment(PipelineMetrics.Counter.DATAGRAMS_REORDERED);
        }
        lastArrivalNs = arrivalNs;
        reassembler.onPacket(packet);
        reassembler.onTick();
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

    private static long readSequence(byte[] datagram) {
        long value = 0L;
        for (int i = 0; i < UdpMediaPacketCodec.SEQUENCE_BYTES; i++) {
            value = (value << 8) | (datagram[i] & 0xffL);
        }
        return value;
    }

    private static String reasonOf(Throwable error) {
        if (error instanceof java.net.SocketTimeoutException) {
            return "udp_bind_timeout";
        }
        if (error instanceof IOException) {
            return "channel_io";
        }
        return "channel_failed";
    }

    private static void closeQuietly(DatagramSocket socket) {
        if (socket != null) {
            socket.close();
        }
    }

    /**
     * Turns reassembled units into the framework model and reports drops.
     */
    private final class FrameSink implements AccessUnitReassembler.Listener {

        @Override
        public void onFrame(MediaFrame frame) {
            lossTracker.onAccessUnitComplete();
            AccessUnitFlags flags = AccessUnitFlags.of(frame.isSyncPoint(), frame.isJoinPoint(),
                    frame.isConfig());
            JoinInfo joinInfo = (frame.isJoinPoint() || !frame.getJoinParts().isEmpty())
                    ? new JoinInfo(frame.getConfigEpoch(), frame.getJoinParts()) : null;
            // A datagram channel has no sequence field; the channel is the only ordering there is,
            // so the counter here is exact for "what the decoder received".
            EncodedAccessUnit accessUnit = new EncodedAccessUnit(streamKey, frame.getConfigEpoch(),
                    nextSequence++, frame.getPtsUs(),
                    frame.hasDtsUs() ? frame.getDtsUs() : frame.getPtsUs(), flags, joinInfo, null,
                    frame.getPayload());
            listener.onAccessUnit(accessUnit, lastArrivalNs);
        }

        @Override
        public void onAccessUnitDropped(int auId, String reason) {
            metrics.increment(PipelineMetrics.Counter.ACCESS_UNITS_INCOMPLETE);
            lossTracker.onAccessUnitIncomplete();
            listener.onCatchUp();
            // The decision belongs to the drop itself: by the time the next complete unit is
            // applied it would already have cleared the consecutive evidence.
            if (lossTracker.shouldRequestJoinPoint()) {
                metrics.increment(PipelineMetrics.Counter.UDP_JOIN_POINT_REQUESTS);
                statusListener.onJoinPointNeeded();
            }
        }
    }
}
