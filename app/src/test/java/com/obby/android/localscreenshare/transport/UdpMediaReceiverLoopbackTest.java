package com.obby.android.localscreenshare.transport;

import com.obby.android.localscreenshare.core.metrics.PipelineMetrics;
import com.obby.android.localscreenshare.core.model.AccessUnitFlags;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.net.AccessUnitReassembler;
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

    /**
     * A unit of the size a JPEG compatibility frame needs is reassembled whole.
     *
     * <p>A screen frame of the JPEG mode is roughly six times the size of an H.264 one, so it needs
     * dozens of fragments instead of a handful; nothing in the datagram format bounds that, and the
     * receiver has to keep the unit while its fragments arrive. See
     * {@code AccessUnitReassemblerTest} for the hold-up boundary that made such a unit impossible
     * on the device.</p>
     */
    @Test
    public void aJpegSizedUnit_isReassembledWhole() throws Exception {
        Recorder recorder = new Recorder();
        mPeer = new Peer();
        start(recorder);
        assertTrue(recorder.awaitReady());
        mPeer.awaitBind();
        int size = 70 * UdpMediaPacketCodec.MAX_FRAGMENT_BYTES;

        mPeer.sendUnit(0, size);

        assertTrue(recorder.awaitUnits(1));
        assertEquals(size, recorder.units.get(0).getPayloadLength());
        assertArrayEquals(payload(size), recorder.units.get(0).getPayload());
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

    /**
     * Fragments of one unit yield the interval between their arrivals, and only those.
     *
     * <p>The gap distribution is the evidence TB-MEDIA-008 needs before its hold-up can be
     * calibrated: the r3 device report could only infer it from the delivery rate because the
     * receiver measured no arrival intervals at all. An interval across two different units is not
     * a fragment gap - it contains the frame interval - so it must not enter the window.</p>
     */
    @Test
    public void fragmentGapsOfTheSameUnit_areRecorded() throws Exception {
        Recorder recorder = new Recorder();
        mPeer = new Peer();
        start(recorder);
        assertTrue(recorder.awaitReady());
        mPeer.awaitBind();

        int size = 2 * UdpMediaPacketCodec.MAX_FRAGMENT_BYTES;
        MediaFrame first = MediaFrame.accessUnit(0L, MediaFrame.DTS_UNSET, 1, false, true, true,
                null, payload(size));
        mPeer.sendFragment(first, 0, 0);
        assertTrue(recorder.awaitDatagrams(1));

        clock.advance(4_000_000L);
        mPeer.sendFragment(first, 0, 1);
        assertTrue(recorder.awaitUnits(1));
        assertTrue(recorder.awaitGapSamples(1));
        assertEquals(4_000_000L, recorder.metrics.stats(PipelineMetrics.Window.FRAGMENT_GAP)
                .getP50());

        // A gap wider than the hold-up is recorded as well, because the probe runs before the
        // expiry pass: that is what makes the window a calibration input for the threshold instead
        // of a replay of the healthy path. The unit itself is still abandoned - this batch only
        // observes the behaviour of TB-MEDIA-008, it does not widen the window.
        MediaFrame second = MediaFrame.accessUnit(33_000L, MediaFrame.DTS_UNSET, 1, false, true,
                true, null, payload(size));
        mPeer.sendFragment(second, 1, 0);
        assertTrue(recorder.awaitDatagrams(3));
        clock.advance(25_000_000L);
        mPeer.sendFragment(second, 1, 1);
        assertTrue(recorder.awaitDatagrams(4));
        assertTrue(recorder.awaitGapSamples(2));
        assertEquals(25_000_000L, recorder.metrics.stats(PipelineMetrics.Window.FRAGMENT_GAP)
                .getMax());

        // A datagram of another unit carries the frame interval, not a fragment gap.
        clock.advance(40_000_000L);
        mPeer.sendUnit(2, 10);
        assertTrue(recorder.awaitDatagrams(5));
        assertEquals(2, recorder.metrics.stats(PipelineMetrics.Window.FRAGMENT_GAP).getCount());
    }

    /**
     * A unit abandoned for silence is counted by its reason as well as in the total.
     *
     * <p>The total cannot tell the two defects of TB-MEDIA-008 apart: a widened window is judged by
     * {@code udp.auTimeout} going to zero on connections that lose no fragment, while a
     * {@code superseded} drop is the healthy path and must keep its own meaning.</p>
     */
    @Test
    public void aTimedOutUnit_isCountedByReason() throws Exception {
        Recorder recorder = new Recorder();
        mPeer = new Peer();
        start(recorder);
        assertTrue(recorder.awaitReady());
        mPeer.awaitBind();

        int size = 2 * UdpMediaPacketCodec.MAX_FRAGMENT_BYTES;
        MediaFrame incomplete = MediaFrame.accessUnit(0L, MediaFrame.DTS_UNSET, 1, false, true,
                true, null, payload(size));
        mPeer.sendFragment(incomplete, 0, 0);
        assertTrue(recorder.awaitDatagrams(1));

        clock.advance(AccessUnitReassembler.HOLD_UP_NS);
        mPeer.sendUnit(1, 10);
        assertTrue(recorder.awaitUnits(1));

        // A second advance covers the case where the receiver processed the incomplete fragment
        // after the first one: its pending unit is then created at the later time and expires on
        // this pass instead. Either way the unit is abandoned exactly once.
        clock.advance(AccessUnitReassembler.HOLD_UP_NS);
        mPeer.sendUnit(2, 10);
        assertTrue(recorder.awaitUnits(2));

        assertEquals(1L, recorder.metrics.count(PipelineMetrics.Counter.ACCESS_UNITS_TIMED_OUT));
        assertEquals(1L, recorder.metrics.count(PipelineMetrics.Counter.ACCESS_UNITS_INCOMPLETE));
    }

    /**
     * A fragment that lands after its unit was delivered is counted as a late datagram.
     *
     * <p>{@code udp.late} was registered by the datagram tier with no producer. In this build the
     * reassembler only recognises an already delivered unit, so the field means "arrived after
     * delivery" - not yet "after delivery or abandonment", which needs the tombstone of the next
     * batch.</p>
     */
    @Test
    public void aFragmentAfterDelivery_isCountedLate() throws Exception {
        Recorder recorder = new Recorder();
        mPeer = new Peer();
        start(recorder);
        assertTrue(recorder.awaitReady());
        mPeer.awaitBind();

        mPeer.sendUnit(0, 10);
        assertTrue(recorder.awaitUnits(1));

        // Same identifier and a fresh wire sequence: the reorder window accepts it, the reassembler
        // rejects it as a fragment of a unit that is already gone.
        mPeer.sendUnit(0, 10);
        assertTrue(recorder.awaitCounter(PipelineMetrics.Counter.DATAGRAMS_LATE, 1L));

        assertEquals(1L, recorder.metrics.count(PipelineMetrics.Counter.DATAGRAMS_LATE));
        assertEquals(0L, recorder.metrics.count(PipelineMetrics.Counter.ACCESS_UNITS_INCOMPLETE));
        assertEquals(1, recorder.units.size());
    }

    /**
     * The residue of an abandoned unit is late, not a second unit with a second drop.
     *
     * <p>A unit the hold-up ended has lost fragments for good, so every later fragment of that id
     * can only be late. Rebuilding it allocated a buffer again, counted a second drop and fed the
     * loss tracker a second time - the device report measured 2.4-2.7 timeouts per unit that really
     * lost a fragment. Here a late datagram of an already delivered unit drives the expiry pass, so
     * the abandoned id stays newer than everything delivered and the delivered-late rule cannot
     * stand in for the tombstone.</p>
     */
    @Test
    public void residueOfAnAbandonedUnit_raisesNoSecondDrop() throws Exception {
        Recorder recorder = new Recorder();
        mPeer = new Peer();
        start(recorder);
        assertTrue(recorder.awaitReady());
        mPeer.awaitBind();

        mPeer.sendUnit(0, 10);
        assertTrue(recorder.awaitUnits(1));

        int size = 2 * UdpMediaPacketCodec.MAX_FRAGMENT_BYTES;
        MediaFrame incomplete = MediaFrame.accessUnit(33_000L, MediaFrame.DTS_UNSET, 1, false, true,
                true, null, payload(size));
        mPeer.sendFragment(incomplete, 5, 0);
        assertTrue(recorder.awaitDatagrams(2));

        clock.advance(AccessUnitReassembler.HOLD_UP_NS);
        mPeer.sendUnit(0, 10);
        assertTrue(recorder.awaitCounter(PipelineMetrics.Counter.DATAGRAMS_LATE, 1L));
        assertEquals(1L, recorder.metrics.count(PipelineMetrics.Counter.ACCESS_UNITS_INCOMPLETE));

        mPeer.sendFragment(incomplete, 5, 1);
        clock.advance(AccessUnitReassembler.HOLD_UP_NS);
        mPeer.sendUnit(0, 10);
        assertTrue(recorder.awaitCounter(PipelineMetrics.Counter.DATAGRAMS_LATE, 3L));

        assertEquals("one drop per abandoned unit",
                1L, recorder.metrics.count(PipelineMetrics.Counter.ACCESS_UNITS_INCOMPLETE));
        assertEquals(1L, recorder.metrics.count(PipelineMetrics.Counter.ACCESS_UNITS_TIMED_OUT));
        assertEquals(3L, recorder.metrics.count(PipelineMetrics.Counter.DATAGRAMS_LATE));
        assertEquals("no half-built unit may reach the viewer", 1, recorder.units.size());
    }

    /**
     * A unit a newer delivery made useless is counted as superseded, not as a timeout.
     *
     * <p>The two reasons lead to opposite conclusions: a timeout says the hold-up expired on a unit
     * that was still arriving, a superseded unit says a newer frame overtook this one, which is the
     * healthy path of a pace that outran assembly.</p>
     */
    @Test
    public void aSupersededUnit_isCountedByItsOwnReason() throws Exception {
        Recorder recorder = new Recorder();
        mPeer = new Peer();
        start(recorder);
        assertTrue(recorder.awaitReady());
        mPeer.awaitBind();

        int size = 2 * UdpMediaPacketCodec.MAX_FRAGMENT_BYTES;
        MediaFrame incomplete = MediaFrame.accessUnit(0L, MediaFrame.DTS_UNSET, 1, false, true,
                true, null, payload(size));
        mPeer.sendFragment(incomplete, 3, 0);
        assertTrue(recorder.awaitDatagrams(1));

        mPeer.sendUnit(4, 10);
        assertTrue(recorder.awaitUnits(1));
        assertTrue(recorder.awaitCounter(PipelineMetrics.Counter.ACCESS_UNITS_SUPERSEDED, 1L));

        assertEquals(1L, recorder.metrics.count(PipelineMetrics.Counter.ACCESS_UNITS_INCOMPLETE));
        assertEquals(0L, recorder.metrics.count(PipelineMetrics.Counter.ACCESS_UNITS_TIMED_OUT));
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

        private boolean awaitDatagrams(long count) throws InterruptedException {
            return awaitCounter(PipelineMetrics.Counter.DATAGRAMS_RECEIVED, count);
        }

        private boolean awaitGapSamples(int count) throws InterruptedException {
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (metrics.stats(PipelineMetrics.Window.FRAGMENT_GAP).getCount() < count
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L);
            }
            return metrics.stats(PipelineMetrics.Window.FRAGMENT_GAP).getCount() >= count;
        }

        private boolean awaitCounter(PipelineMetrics.Counter counter, long count)
                throws InterruptedException {
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (metrics.count(counter) < count && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L);
            }
            return metrics.count(counter) >= count;
        }
    }
}
