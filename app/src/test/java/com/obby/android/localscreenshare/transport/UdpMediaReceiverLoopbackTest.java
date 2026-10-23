package com.obby.android.localscreenshare.transport;

import com.obby.android.localscreenshare.core.metrics.PipelineMetrics;
import com.obby.android.localscreenshare.core.model.AccessUnitFlags;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.net.DatagramCipher;
import com.obby.android.localscreenshare.core.net.DatagramKeys;
import com.obby.android.localscreenshare.core.net.MediaChannelListener;
import com.obby.android.localscreenshare.core.net.MediaChannelOffer;
import com.obby.android.localscreenshare.core.net.MediaChannelStatusListener;
import com.obby.android.localscreenshare.core.net.MediaFrame;
import com.obby.android.localscreenshare.core.net.MediaTransportKind;
import com.obby.android.localscreenshare.core.net.UdpBindPacket;
import com.obby.android.localscreenshare.core.net.UdpMediaPacketCodec;
import com.obby.android.localscreenshare.core.support.MutableNanoClock;

import org.junit.After;
import org.junit.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Loopback tests of {@link UdpMediaReceiver} against a datagram peer built from the same codec.
 *
 * <p>The peer plays the server: it reads the bind, then writes fragments. Every behaviour the plan
 * requires of the receiver is exercised on real sockets - readiness after the first datagram, the
 * order of the checks, reassembly across reordering, the loss chain up to a key-frame request, the
 * bind timeout and the counter of forged datagrams.</p>
 */
public class UdpMediaReceiverLoopbackTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session-1", "stream-1");

    private static final long TIMEOUT_MS = 3_000L;

    private final MutableNanoClock clock = new MutableNanoClock();

    private Peer mPeer;

    private UdpMediaReceiver mReceiver;

    private Thread mThread;

    @After
    public void tearDown() {
        if (mReceiver != null) {
            mReceiver.close();
        }
        if (mThread != null) {
            try {
                mThread.join(TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (mPeer != null) {
            mPeer.close();
        }
    }

    @Test
    public void bindAndFirstFrame_makeTheChannelReady() throws Exception {
        Recorder recorder = new Recorder();
        mPeer = new Peer();
        start(recorder);

        assertTrue("channel never became ready", recorder.awaitReady());
        mPeer.awaitBind();
        mPeer.sendUnit(0, 60);

        assertTrue(recorder.awaitUnits(1));
        assertEquals(60, recorder.units.get(0).getPayloadLength());
        assertNull(recorder.failure.get());
    }

    @Test
    public void reorderedFragments_areReassembledIntoOneUnit() throws Exception {
        Recorder recorder = new Recorder();
        mPeer = new Peer();
        start(recorder);
        assertTrue(recorder.awaitReady());
        mPeer.awaitBind();

        int size = 2 * UdpMediaPacketCodec.MAX_FRAGMENT_BYTES + 10;
        MediaFrame frame = MediaFrame.accessUnit(0L, MediaFrame.DTS_UNSET, 1, false, true,
                false, null, payload(size));
        mPeer.sendFragment(frame, 0, 2);
        mPeer.sendFragment(frame, 0, 0);
        mPeer.sendFragment(frame, 0, 1);

        assertTrue(recorder.awaitUnits(1));
        assertArrayEquals(payload(size), recorder.units.get(0).getPayload());
    }

    @Test
    public void firstFrameMayArriveAfterTheOldBindWindow() throws Exception {
        Recorder recorder = new Recorder();
        mPeer = new Peer();
        start(recorder);
        assertTrue(recorder.awaitReady());
        mPeer.awaitBind();

        // The server only sends after it has received READY, swapped and asked for a join point;
        // on a loaded device that took more than the old 300 ms window.
        Thread.sleep(800L);

        mPeer.sendUnit(0, 60);

        assertTrue("the channel was abandoned before the first frame", recorder.awaitUnits(1));
        assertNull(recorder.failure.get());
    }

    @Test
    public void framesAfterTheFirstOne_areNotBoundedByTheWindow() throws Exception {
        Recorder recorder = new Recorder();
        mPeer = new Peer();
        start(recorder);
        assertTrue(recorder.awaitReady());
        mPeer.awaitBind();

        mPeer.sendUnit(0, 60);
        assertTrue(recorder.awaitUnits(1));

        Thread.sleep(2_100L);
        mPeer.sendUnit(1, 60);

        assertTrue("a quiet stretch must not end the channel", recorder.awaitUnits(2));
        assertNull(recorder.failure.get());
    }

    @Test
    public void lostUnits_catchUpAndAskForAJoinPoint() throws Exception {
        Recorder recorder = new Recorder();
        mPeer = new Peer();
        start(recorder);
        assertTrue(recorder.awaitReady());
        mPeer.awaitBind();

        int size = 2 * UdpMediaPacketCodec.MAX_FRAGMENT_BYTES;
        MediaFrame incomplete = MediaFrame.accessUnit(0L, MediaFrame.DTS_UNSET, 1, false, true,
                false, null, payload(size));
        // Two units lose their second fragment.
        mPeer.sendFragment(incomplete, 0, 0);
        mPeer.sendFragment(incomplete, 1, 0);
        clock.advance(com.obby.android.localscreenshare.core.net.AccessUnitReassembler.HOLD_UP_NS);

        // The next complete unit drives the expiry pass.
        mPeer.sendUnit(2, 10);

        assertTrue(recorder.awaitUnits(1));
        assertTrue("no catch-up was requested", recorder.awaitCatchUp());
        assertTrue("no join point was requested", recorder.awaitJoinPoint());
        assertEquals(1L, recorder.metrics.count(PipelineMetrics.Counter.UDP_JOIN_POINT_REQUESTS));

        // A second burst inside the tracker cooldown raises nothing new, so a loss storm cannot
        // turn into a key-frame storm either.
        clock.advance(500_000_000L);
        mPeer.sendFragment(incomplete, 3, 0);
        mPeer.sendFragment(incomplete, 4, 0);
        clock.advance(com.obby.android.localscreenshare.core.net.AccessUnitReassembler.HOLD_UP_NS);
        mPeer.sendUnit(5, 10);
        Thread.sleep(200L);
        assertEquals(1L, recorder.metrics.count(PipelineMetrics.Counter.UDP_JOIN_POINT_REQUESTS));

        // Past the cooldown the next burst asks again.
        clock.advance(com.obby.android.localscreenshare.core.net.DatagramLossTracker.COOLDOWN_NS);
        mPeer.sendFragment(incomplete, 6, 0);
        mPeer.sendFragment(incomplete, 7, 0);
        clock.advance(com.obby.android.localscreenshare.core.net.AccessUnitReassembler.HOLD_UP_NS);
        mPeer.sendUnit(8, 10);
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline
                && recorder.metrics.count(PipelineMetrics.Counter.UDP_JOIN_POINT_REQUESTS) < 2L) {
            Thread.sleep(10L);
        }
        assertEquals(2L, recorder.metrics.count(PipelineMetrics.Counter.UDP_JOIN_POINT_REQUESTS));
    }

    @Test
    public void silentServer_isABindTimeout() throws Exception {
        Recorder recorder = new Recorder();
        mPeer = new Peer(false);
        start(recorder);

        assertTrue(recorder.awaitFailure());
        assertEquals("udp_bind_timeout", recorder.failure.get());
        assertTrue(recorder.units.isEmpty());
    }

    @Test
    public void forgedDatagram_isCountedAndTheChannelSurvives() throws Exception {
        Recorder recorder = new Recorder();
        mPeer = new Peer();
        start(recorder);
        assertTrue(recorder.awaitReady());
        mPeer.awaitBind();

        mPeer.sendForged(512);
        assertTrue(recorder.awaitAuthFailures(1));

        mPeer.sendUnit(0, 12);
        assertTrue(recorder.awaitUnits(1));
        assertNull(recorder.failure.get());
    }

    private void start(Recorder recorder) {
        mReceiver = new UdpMediaReceiver(STREAM_KEY, "127.0.0.1", mPeer.offer, recorder,
                recorder, clock, recorder.metrics);
        mThread = new Thread(mReceiver::start, "udp-receiver");
        mThread.setDaemon(true);
        mThread.start();
    }

    private static byte[] payload(int size) {
        byte[] payload = new byte[size];
        for (int i = 0; i < size; i++) {
            payload[i] = (byte) (i * 17 + 3);
        }
        return payload;
    }

    /**
     * Datagram peer that plays the server side of one channel.
     */
    private static final class Peer {

        private final DatagramSocket socket;

        private final DatagramKeys keys = DatagramKeys.generate(new java.security.SecureRandom());

        private final byte[] channelId = new byte[16];

        private final byte[] token = new byte[DatagramCipher.KEY_BYTES];

        private final MediaChannelOffer offer;

        private final boolean answersBind;

        private InetSocketAddress client;

        private long wireSeq = 100L;

        private Peer() throws Exception {
            this(true);
        }

        private Peer(boolean answersBind) throws Exception {
            this.answersBind = answersBind;
            Arrays.fill(channelId, (byte) 0x5a);
            Arrays.fill(token, (byte) 0x6b);
            socket = new DatagramSocket(0);
            offer = MediaChannelOffer.builder(channelId, MediaTransportKind.UDP)
                    .host("127.0.0.1")
                    .port(socket.getLocalPort())
                    .secure(false)
                    .token(token)
                    .keyS2c(keys.getKeyS2c())
                    .keyC2s(keys.getKeyC2s())
                    .nonceSalt(keys.getNonceSalt())
                    .mtu(UdpMediaPacketCodec.MAX_DATAGRAM_BYTES)
                    .holdMs(10)
                    .build();
        }

        private void awaitBind() throws Exception {
            socket.setSoTimeout((int) TIMEOUT_MS);
            byte[] buffer = new byte[UdpBindPacket.BIND_BYTES];
            DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
            socket.receive(datagram);
            client = new InetSocketAddress(datagram.getAddress(), datagram.getPort());
        }

        private void sendUnit(int auId, int size) throws Exception {
            MediaFrame frame = MediaFrame.accessUnit(auId * 33_000L, MediaFrame.DTS_UNSET, 1,
                    false, true, true, null, payload(size));
            for (int index = 0; index < UdpMediaPacketCodec.fragmentCount(size); index++) {
                sendFragment(frame, auId, index);
            }
        }

        private void sendFragment(MediaFrame frame, int auId, int index) throws Exception {
            DatagramCipher cipher = new DatagramCipher(keys.getKeyS2c(), keys.getNonceSalt());
            byte[] datagram = UdpMediaPacketCodec.encodeFragment(frame, auId, index, ++wireSeq,
                    cipher);
            socket.send(new DatagramPacket(datagram, datagram.length, client));
        }

        private void sendForged(int size) throws Exception {
            byte[] datagram = new byte[size];
            datagram[0] = 0;
            datagram[7] = (byte) 0x7f;
            socket.send(new DatagramPacket(datagram, datagram.length, client));
        }

        private void close() {
            socket.close();
        }
    }

    /**
     * Records what the channel delivered and answered.
     */
    private static final class Recorder implements MediaChannelListener,
            MediaChannelStatusListener {

        private final PipelineMetrics metrics = PipelineMetrics.create(new MutableNanoClock(), 8);

        private final List<EncodedAccessUnit> units = Collections.synchronizedList(
                new ArrayList<>());

        private final CountDownLatch ready = new CountDownLatch(1);

        private final CountDownLatch failed = new CountDownLatch(1);

        private final CountDownLatch catchUp = new CountDownLatch(1);

        private final CountDownLatch joinPoint = new CountDownLatch(1);

        private final AtomicReference<String> failure = new AtomicReference<>();

        private final AtomicInteger catchUps = new AtomicInteger();

        @Override
        public void onDescriptor(com.obby.android.localscreenshare.core.model.StreamDescriptor d,
                                 boolean secure) {
            // Datagram channels carry no descriptors.
        }

        @Override
        public void onAccessUnit(EncodedAccessUnit accessUnit, long arrivalNs) {
            units.add(accessUnit);
        }

        @Override
        public void onChannelFailed(String reason) {
            failure.set(reason);
            failed.countDown();
        }

        @Override
        public void onCatchUp() {
            catchUps.incrementAndGet();
            catchUp.countDown();
        }

        @Override
        public void onChannelReady() {
            ready.countDown();
        }

        @Override
        public void onJoinPointNeeded() {
            joinPoint.countDown();
        }

        private boolean awaitReady() throws InterruptedException {
            return ready.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        private boolean awaitFailure() throws InterruptedException {
            return failed.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        private boolean awaitCatchUp() throws InterruptedException {
            return catchUp.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        private boolean awaitJoinPoint() throws InterruptedException {
            return joinPoint.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        private boolean awaitUnits(int count) throws InterruptedException {
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (units.size() < count && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L);
            }
            return units.size() >= count;
        }

        private boolean awaitAuthFailures(long count) throws InterruptedException {
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (metrics.count(PipelineMetrics.Counter.MEDIA_AUTH_FAILURES) < count
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L);
            }
            return metrics.count(PipelineMetrics.Counter.MEDIA_AUTH_FAILURES) >= count;
        }
    }
}
