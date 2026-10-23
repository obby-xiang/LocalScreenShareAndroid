package com.obby.android.localscreenshare.transport;

import com.obby.android.localscreenshare.core.model.AccessUnitFlags;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.JoinInfo;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.net.MediaChannelHandshake;
import com.obby.android.localscreenshare.core.net.MediaChannelListener;
import com.obby.android.localscreenshare.core.net.MediaChannelStatusListener;
import com.obby.android.localscreenshare.core.net.MediaChannelOffer;
import com.obby.android.localscreenshare.core.net.MediaChannelTicket;
import com.obby.android.localscreenshare.core.net.MediaTransportKind;
import com.obby.android.localscreenshare.core.stream.MediaTransport;
import com.obby.android.localscreenshare.core.util.TimeSource;
import com.obby.android.localscreenshare.identity.tls.TlsMaterial;
import com.obby.android.localscreenshare.trust.TlsPin;
import com.obby.android.localscreenshare.trust.TlsPinLookup;

import org.junit.After;
import org.junit.Test;

import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Real TLS loopback of the dedicated media channel.
 *
 * <p>Both ends are the production classes: the listener builds its context from
 * {@link TlsMaterial#getKeyManager()} through {@link MediaTlsFactory}, and so does the receiver, so
 * a handshake that works here is evidence that the identity key adapter can feed a raw
 * {@code SSLSocket} and not only the gRPC stack. Both endpoints use software EC keys, exactly like
 * {@code TlsHandshakeEndToEndTest}.</p>
 */
public class TcpMediaLoopbackTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session-1", "stream-1");

    private static final long TIMEOUT_SECONDS = 5L;

    private MediaChannelServer mServer;

    @After
    public void tearDown() {
        if (mServer != null) {
            mServer.stop();
            mServer = null;
        }
    }

    @Test
    public void descriptorArrivesBeforeAccessUnitsAndTheDecodeTimestampIsDerived() throws Exception {
        Fixture fixture = fixture();
        fixture.authority.ticket = ticket(fixture, fixture.client.getDeviceId());
        fixture.server.start(0);

        RecordingListener listener = new RecordingListener();
        RecordingStatus status = new RecordingStatus();
        TcpMediaReceiver receiver = receiver(fixture, listener, status, fixture.authority.ticket);
        Thread thread = start(receiver);

        assertTrue("channel not ready", status.awaitReady());
        assertTrue("channel not established", fixture.authority.awaitEstablished());
        MediaTransport channel = fixture.authority.transport;
        assertNotNull(channel);

        channel.sendDescriptor(descriptor());
        channel.sendAccessUnit(accessUnit(0L));
        channel.sendAccessUnit(accessUnit(1L));

        assertTrue("units not delivered, failure = " + status.failure.get(),
                listener.awaitAccessUnits(2));
        assertEquals(1, listener.descriptors.size());
        assertTrue(listener.order.get(0).equals("descriptor"));
        assertEquals(1, listener.descriptors.get(0).getConfigEpoch());
        assertEquals(2, listener.accessUnits.size());
        assertEquals(0L, listener.accessUnits.get(0).getSequence());
        assertEquals(1L, listener.accessUnits.get(1).getSequence());
        // Version one sends no decode timestamp; the receiver rebuilds it from the presentation one.
        assertTrue(listener.accessUnits.get(0).hasDtsUs());
        assertEquals(listener.accessUnits.get(0).getPtsUs(), listener.accessUnits.get(0).getDtsUs());

        receiver.close();
        thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        assertNull(status.failure.get());
    }

    @Test
    public void endOfTheServerSide_closesTheChannelOnce() throws Exception {
        Fixture fixture = fixture();
        fixture.authority.ticket = ticket(fixture, fixture.client.getDeviceId());
        fixture.server.start(0);
        RecordingListener listener = new RecordingListener();
        RecordingStatus status = new RecordingStatus();
        TcpMediaReceiver receiver = receiver(fixture, listener, status, fixture.authority.ticket);
        start(receiver);
        assertTrue(status.awaitReady());
        assertTrue(fixture.authority.awaitEstablished());

        fixture.authority.transport.close();

        assertTrue("failure not reported", status.awaitFailure());
        assertEquals("channel_closed", status.failure.get());
        receiver.close();
    }

    /**
     * Closing the channel from the caller does not close the socket on the caller's thread.
     *
     * <p>This is the fix of the device crash: the viewer's teardown runs on the main thread, and a
     * TLS close writes its {@code close_notify} there, which {@code StrictMode} turns into a fatal
     * {@code NetworkOnMainThreadException}. The channel counts as closed the moment the call
     * returns - the read loop stops - while the socket itself is torn down on the closer.</p>
     */
    @Test
    public void close_handsTheSocketToItsCloserInsteadOfClosingItInline() throws Exception {
        Fixture fixture = fixture();
        fixture.authority.ticket = ticket(fixture, fixture.client.getDeviceId());
        fixture.server.start(0);
        RecordingStatus status = new RecordingStatus();
        RecordingQueue closes = new RecordingQueue();
        TcpMediaReceiver receiver = receiver(fixture, new RecordingListener(), status,
                fixture.authority.ticket, new MediaChannelCloser(closes));
        Thread thread = start(receiver);
        assertTrue(status.awaitReady());
        assertTrue(fixture.authority.awaitEstablished());

        receiver.close();
        receiver.close();

        assertEquals("repeated closes must queue one socket close", 1, closes.pending());
        assertTrue("the caller must not close the greeting socket itself", thread.isAlive());
        assertNull("a close from outside reports nothing", status.failure.get());

        closes.runAll();

        thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        assertFalse("the reader must end once the socket is closed", thread.isAlive());
        assertNull(status.failure.get());
    }

    @Test
    public void wrongToken_isRefusedBeforeAnyFrame() throws Exception {
        Fixture fixture = fixture();
        MediaChannelTicket issued = ticket(fixture, fixture.client.getDeviceId());
        fixture.authority.ticket = issued;
        fixture.server.start(0);

        RecordingStatus status = new RecordingStatus();
        MediaChannelOffer forged = MediaChannelOffer
                .builder(issued.getChannelId(), MediaTransportKind.TCP)
                .host("127.0.0.1")
                .port(fixture.server.getPort())
                .secure(true)
                .token(new byte[MediaChannelTicket.TOKEN_BYTES])
                .build();
        TcpMediaReceiver receiver = new TcpMediaReceiver(STREAM_KEY, "127.0.0.1", forged,
                new RecordingListener(), status, fixture.clientContext);
        start(receiver);

        assertTrue(status.awaitFailure());
        assertEquals("handshake_bad_token", status.failure.get());
        assertNull(fixture.authority.transport);
    }

    @Test
    public void unknownChannel_isRefused() throws Exception {
        Fixture fixture = fixture();
        MediaChannelTicket issued = ticket(fixture, fixture.client.getDeviceId());
        fixture.server.start(0);

        RecordingStatus status = new RecordingStatus();
        TcpMediaReceiver receiver = receiver(fixture, new RecordingListener(), status, issued);
        start(receiver);

        assertTrue(status.awaitFailure());
        assertEquals("handshake_bad_channel", status.failure.get());
    }

    @Test
    public void identityOfAnotherDevice_isRefused() throws Exception {
        Fixture fixture = fixture();
        fixture.authority.ticket = ticket(fixture, "another-device");
        fixture.server.start(0);

        RecordingStatus status = new RecordingStatus();
        TcpMediaReceiver receiver = receiver(fixture, new RecordingListener(), status,
                fixture.authority.ticket);
        start(receiver);

        assertTrue(status.awaitFailure());
        assertEquals("handshake_peer_mismatch", status.failure.get());
    }

    private TcpMediaReceiver receiver(Fixture fixture, RecordingListener listener,
                                      RecordingStatus status, MediaChannelTicket issue)
            throws GeneralSecurityException {
        return receiver(fixture, listener, status, issue, MediaChannelCloser.daemon());
    }

    private TcpMediaReceiver receiver(Fixture fixture, RecordingListener listener,
                                      RecordingStatus status, MediaChannelTicket issue,
                                      MediaChannelCloser closer)
            throws GeneralSecurityException {
        MediaChannelOffer offer = MediaChannelOffer
                .builder(issue.getChannelId(), MediaTransportKind.TCP)
                .host("127.0.0.1")
                .port(fixture.server.getPort())
                .secure(true)
                .token(issue.getToken())
                .build();
        return new TcpMediaReceiver(STREAM_KEY, "127.0.0.1", offer, listener, status,
                fixture.clientContext, closer);
    }

    private static Thread start(TcpMediaReceiver receiver) {
        Thread thread = new Thread(receiver::start, "media-loopback");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static MediaChannelTicket ticket(Fixture fixture, String peerDeviceId) {
        return MediaChannelTicket.issue(MediaTransportKind.TCP, peerDeviceId,
                TimeSource.MONOTONIC.nowMs());
    }

    private static StreamDescriptor descriptor() {
        return StreamDescriptor.builder(STREAM_KEY, 1, "h264", "video/avc",
                        BitstreamFormat.ANNEX_B)
                .codedSize(64, 48)
                .visibleSize(64, 48)
                .configParts(Collections.emptyList())
                .build();
    }

    private static EncodedAccessUnit accessUnit(long sequence) {
        boolean joinPoint = sequence == 0L;
        AccessUnitFlags flags = joinPoint
                ? AccessUnitFlags.of(true, true, false) : AccessUnitFlags.none();
        JoinInfo joinInfo = joinPoint
                ? new JoinInfo(1, Collections.emptyList()) : null;
        return new EncodedAccessUnit(STREAM_KEY, 1, sequence, sequence * 33_000L,
                EncodedAccessUnit.DTS_UNSET, flags, joinInfo, null, new byte[]{0x01, 0x02});
    }

    private Fixture fixture() throws Exception {
        Fixture fixture = new Fixture();
        fixture.authority = new RecordingAuthority();
        mServer = new MediaChannelServer(
                MediaTlsFactory.serverContext(fixture.serverMaterial, id -> null),
                fixture.authority, TimeSource.MONOTONIC);
        fixture.server = mServer;
        return fixture;
    }

    private static TlsMaterial material() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return TlsMaterial.fromKeyPair(generator.generateKeyPair());
    }

    /**
     * Fixture of one loopback session.
     */
    private static final class Fixture {

        private final TlsMaterial serverMaterial;

        private final TlsMaterial client;

        private final javax.net.ssl.SSLContext clientContext;

        private MediaChannelServer server;

        private RecordingAuthority authority;

        private Fixture() throws Exception {
            serverMaterial = material();
            client = material();
            TlsPinLookup pins = deviceId -> new TlsPin(serverMaterial.getDeviceId(),
                    serverMaterial.getFingerprint(), 0L);
            clientContext = MediaTlsFactory.clientContext(client, serverMaterial.getDeviceId(),
                    pins);
        }
    }

    /**
     * Authority that remembers one outstanding offer and the channel it received.
     */
    private static final class RecordingAuthority implements MediaChannelTicketAuthority {

        private MediaChannelTicket ticket;

        private volatile MediaTransport transport;

        private final CountDownLatch established = new CountDownLatch(1);

        @Override
        public MediaChannelTicket ticketFor(String channelIdHex) {
            MediaChannelTicket current = ticket;
            return current != null && current.getChannelIdHex().equals(channelIdHex)
                    ? current : null;
        }

        @Override
        public com.obby.android.localscreenshare.core.net.DatagramKeys udpKeysFor(
                String channelIdHex) {
            return null;
        }

        @Override
        public void onChannelEstablished(String channelIdHex, MediaTransport channel) {
            transport = channel;
            established.countDown();
        }

        boolean awaitEstablished() throws InterruptedException {
            return established.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * Records what the channel delivered, in order.
     */
    private static final class RecordingListener implements MediaChannelListener {

        private final List<String> order = Collections.synchronizedList(new ArrayList<>());

        private final List<StreamDescriptor> descriptors =
                Collections.synchronizedList(new ArrayList<>());

        private final List<EncodedAccessUnit> accessUnits =
                Collections.synchronizedList(new ArrayList<>());

        private final CountDownLatch units = new CountDownLatch(2);

        @Override
        public void onDescriptor(StreamDescriptor descriptor, boolean secure) {
            order.add("descriptor");
            descriptors.add(descriptor);
        }

        @Override
        public void onAccessUnit(EncodedAccessUnit accessUnit, long arrivalNs) {
            order.add("unit");
            accessUnits.add(accessUnit);
            units.countDown();
        }

        @Override
        public void onChannelFailed(String reason) {
            order.add("failed:" + reason);
        }

        boolean awaitAccessUnits(int count) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            while (accessUnits.size() < count && System.nanoTime() < deadline) {
                Thread.sleep(10L);
            }
            return accessUnits.size() >= count;
        }

    }

    /**
     * Records the lifecycle of the channel.
     */
    private static final class RecordingStatus implements MediaChannelStatusListener {
        private final CountDownLatch ready = new CountDownLatch(1);

        private final CountDownLatch failed = new CountDownLatch(1);

        private final AtomicReference<String> failure = new AtomicReference<>();

        @Override
        public void onChannelReady() {
            ready.countDown();
        }

        @Override
        public void onChannelFailed(String reason) {
            failure.set(reason);
            failed.countDown();
        }

        boolean awaitReady() throws InterruptedException {
            return ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        boolean awaitFailure() throws InterruptedException {
            return failed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * Executor that queues tasks until the test runs them, so a dispatch is observable.
     */
    private static final class RecordingQueue implements Executor {

        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(final Runnable command) {
            tasks.add(command);
        }

        int pending() {
            return tasks.size();
        }

        void runAll() {
            for (Runnable task : new ArrayList<>(tasks)) {
                task.run();
            }
        }
    }
}
