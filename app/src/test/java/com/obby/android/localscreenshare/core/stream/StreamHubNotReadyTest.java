package com.obby.android.localscreenshare.core.stream;

import com.obby.android.localscreenshare.core.codec.CodecRegistration;
import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.codec.EncoderConfig;
import com.obby.android.localscreenshare.core.codec.EncoderException;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.model.ClientState;
import com.obby.android.localscreenshare.core.model.CodecConfigPart;
import com.obby.android.localscreenshare.core.model.ColorInfo;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.model.StreamParameters;
import com.obby.android.localscreenshare.core.support.FakeCodec;
import com.obby.android.localscreenshare.core.support.FakeDeferredCodec;
import com.obby.android.localscreenshare.core.support.FakeTransport;
import com.obby.android.localscreenshare.core.support.MutableClock;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Readiness gate and encoder-failure isolation for {@link StreamHub}.
 *
 * <p>A deferred stream stays in {@link RuntimeState#CONFIGURING} until its encoder
 * publishes a configuration. Until then joins must be rejected with
 * {@link JoinDecision.Status#NOT_READY} without side effects, and hub listeners must
 * never dereference the missing descriptor.</p>
 */
public class StreamHubNotReadyTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session-1", "stream-1");

    private static final StreamKey OTHER_STREAM_KEY = new StreamKey("session-1", "stream-2");

    private static final ClientSessionKey CLIENT_A = new ClientSessionKey(STREAM_KEY, "client-A");

    private static final ClientSessionKey CLIENT_B = new ClientSessionKey(STREAM_KEY, "client-B");

    private static final ClientSessionKey CLIENT_C = new ClientSessionKey(STREAM_KEY, "client-C");

    private static final ClientSessionKey EXISTING_CLIENT =
            new ClientSessionKey(STREAM_KEY, "client-existing");

    private static final ClientSessionKey CANDIDATE_CLIENT =
            new ClientSessionKey(STREAM_KEY, "client-candidate");

    private static final String ACTIVE_CODEC_ID = FakeDeferredCodec.DEFAULT_CODEC_ID;

    private static final String OTHER_CODEC_ID = "fake-other";

    private static final long JOIN_INTERVAL_MS = 100L;

    private static final long MAX_AGE_MS = 5_000L;

    @Test
    public void notReadyFactory_exposesStatusAndReason() {
        JoinDecision decision = JoinDecision.notReady("stream is configuring");

        assertEquals(JoinDecision.Status.NOT_READY, decision.getStatus());
        assertEquals("stream is configuring", decision.getReason());
        assertNull(decision.getSession());
        assertNull(decision.getFallbackCodecId());
    }

    @Test
    public void register_deferredRuntimeUsesStreamKeyEvenWhenDescriptorNull() {
        MutableClock clock = new MutableClock();
        FakeDeferredCodec codec = new FakeDeferredCodec();
        CodecRegistry registry = registryOf(codec.registration());
        StreamRuntime runtime = deferredRuntime(registry, STREAM_KEY, clock);

        runtime.start();

        assertEquals(RuntimeState.CONFIGURING, runtime.getState());
        assertNull("a deferred runtime must not publish a descriptor before its config",
                runtime.getDescriptor());

        StreamHub hub = newHub(registry, clock);
        hub.register(runtime);
        assertThrows(RuntimeException.class, () -> hub.register(runtime));

        hub.activate(STREAM_KEY);

        assertSame("the runtime must be registered under getStreamKey()", runtime,
                hub.getActiveStream());
        assertEquals(0, hub.getSessionCount());
    }

    @Test
    public void joinWhileConfiguring_returnsNotReadyAndCreatesNoSession() {
        MutableClock clock = new MutableClock();
        CodecRegistry registry = new CodecRegistry();
        FakeDeferredCodec codec = new FakeDeferredCodec();
        registry.register(codec.registration());
        register(registry, "fallbackA", 10);
        register(registry, "fallbackB", 20);
        StreamRuntime runtime = deferredRuntime(registry, STREAM_KEY, clock);
        runtime.start();
        StreamHub hub = newHub(registry, clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        FakeTransport transport = new FakeTransport();
        JoinDecision decision =
                hub.join(CANDIDATE_CLIENT, capabilities("fallbackB"), transport);

        assertEquals(JoinDecision.Status.NOT_READY, decision.getStatus());
        assertNull(decision.getSession());
        assertNotNull("a not-ready decision must explain why", decision.getReason());
        assertNull(decision.getFallbackCodecId());
        assertEquals(0, hub.getSessionCount());
        assertNull(hub.getSession(CANDIDATE_CLIENT));
        assertTrue(transport.getEvents().isEmpty());
        assertEquals("a rejected join must not close the client transport", 0,
                transport.getCloseCount());

        codec.getLastEncoder().emitConfig(encoderConfig(1280, 720));
        assertEquals(RuntimeState.READY, runtime.getState());

        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(EXISTING_CLIENT,
                        capabilities(ACTIVE_CODEC_ID, "fallbackA", "fallbackB"),
                        new FakeTransport()).getStatus());

        // The not-ready attempt must not have stored capabilities or reserved the key:
        // a leaked "fallbackB"-only capability set would degrade the fallback codec.
        JoinDecision retry = hub.join(CANDIDATE_CLIENT,
                capabilities("fallbackA", "fallbackB"), new FakeTransport());
        assertEquals(JoinDecision.Status.REQUIRES_EXPLICIT_FALLBACK, retry.getStatus());
        assertEquals("fallbackA", retry.getFallbackCodecId());
        assertEquals(1, hub.getSessionCount());
    }

    @Test
    public void joinWhileConfiguring_doesNotRequestJoinPoint() {
        MutableClock clock = new MutableClock();
        FakeDeferredCodec codec = new FakeDeferredCodec();
        CodecRegistry registry = registryOf(codec.registration());
        StreamRuntime runtime = deferredRuntime(registry, STREAM_KEY, clock);
        runtime.start();
        FakeDeferredCodec.FakeEncoder encoder = codec.getLastEncoder();
        StreamHub hub = newHub(registry, clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        JoinDecision decision =
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), new FakeTransport());

        assertEquals(JoinDecision.Status.NOT_READY, decision.getStatus());
        assertEquals("no join point may be requested before the stream is ready", 0,
                encoder.getRequestCount());

        encoder.emitConfig(encoderConfig(1280, 720));
        assertEquals(RuntimeState.READY, runtime.getState());

        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), new FakeTransport())
                        .getStatus());
        assertEquals("the accepted join must request a join point", 1,
                encoder.getRequestCount());
    }

    @Test
    public void joinAfterConfig_acceptedAndDescriptorPrecedesAu() throws Exception {
        MutableClock clock = new MutableClock();
        FakeDeferredCodec codec = new FakeDeferredCodec();
        CodecRegistry registry = registryOf(codec.registration());
        StreamRuntime runtime = deferredRuntime(registry, STREAM_KEY, clock);
        runtime.start();
        FakeDeferredCodec.FakeEncoder encoder = codec.getLastEncoder();
        encoder.emitConfig(encoderConfig(1280, 720));
        StreamHub hub = newHub(registry, clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        FakeTransport transport = new FakeTransport();
        JoinDecision decision =
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), transport);

        assertEquals(JoinDecision.Status.ACCEPTED, decision.getStatus());
        assertNotNull(decision.getSession());
        assertSame(decision.getSession(), hub.getSession(CLIENT_A));
        assertEquals(1, encoder.getRequestCount());

        StreamDescriptor published = runtime.getDescriptor();
        assertNotNull(published);
        ClientSendSession session = hub.getSession(CLIENT_A);
        assertEquals(published.getConfigHash(), session.getCurrentDescriptor().getConfigHash());
        assertEquals(ClientState.WAIT_RANDOM_ACCESS, session.getState());

        assertTrue(hub.drainOne(CLIENT_A));
        assertEquals(1, transport.getEvents().size());
        assertTrue(transport.getEvents().get(0).isDescriptor());
        assertEquals(published.getConfigHash(),
                transport.getEvents().get(0).getDescriptor().getConfigHash());

        encoder.emit(true, published.getConfigEpoch(), new byte[]{0x11});

        assertTrue(hub.drainOne(CLIENT_A));
        assertEquals(2, transport.getEvents().size());
        assertTrue("the descriptor must be sent before the first access unit",
                transport.getEvents().get(1).isAccessUnit());
        assertEquals(ClientState.STREAMING, session.getState());
    }

    @Test
    public void joinAfterConfig_existingClientsUnaffected() throws Exception {
        MutableClock clock = new MutableClock();
        FakeDeferredCodec codec = new FakeDeferredCodec();
        CodecRegistry registry = registryOf(codec.registration());
        StreamRuntime runtime = deferredRuntime(registry, STREAM_KEY, clock);
        runtime.start();
        FakeDeferredCodec.FakeEncoder encoder = codec.getLastEncoder();
        StreamHub hub = newHub(registry, clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        FakeTransport candidateTransport = new FakeTransport();
        assertEquals(JoinDecision.Status.NOT_READY,
                hub.join(CANDIDATE_CLIENT, capabilities(ACTIVE_CODEC_ID), candidateTransport)
                        .getStatus());
        assertTrue(candidateTransport.getEvents().isEmpty());

        encoder.emitConfig(encoderConfig(1280, 720));

        FakeTransport existingTransport = new FakeTransport();
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(EXISTING_CLIENT, capabilities(ACTIVE_CODEC_ID), existingTransport)
                        .getStatus());
        ClientSendSession existingSession = hub.getSession(EXISTING_CLIENT);
        assertNotNull(existingSession);
        encoder.emit(true, 1, new byte[]{0x21});
        drainToStreaming(hub, EXISTING_CLIENT, existingTransport);
        StreamDescriptor existingDescriptor = existingSession.getCurrentDescriptor();

        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CANDIDATE_CLIENT, capabilities(ACTIVE_CODEC_ID), candidateTransport)
                        .getStatus());

        assertSame(existingSession, hub.getSession(EXISTING_CLIENT));
        assertEquals(ClientState.STREAMING, existingSession.getState());
        assertFalse(existingSession.isDetached());
        assertEquals(existingDescriptor.getConfigHash(),
                existingSession.getCurrentDescriptor().getConfigHash());
        assertEquals("a later join must not resend to the existing client", 2,
                existingTransport.getEvents().size());
        assertEquals(0, existingTransport.getCloseCount());
        assertEquals(2, hub.getSessionCount());

        assertTrue(hub.drainOne(CANDIDATE_CLIENT));
        assertEquals(1, candidateTransport.getEvents().size());
        assertTrue(candidateTransport.getEvents().get(0).isDescriptor());
    }

    @Test
    public void activeDeferredConfiguring_ignoresNonActiveRuntimeAuAndDescriptorWithoutNpe()
            throws Exception {
        MutableClock clock = new MutableClock();
        CodecRegistry registry = new CodecRegistry();
        FakeDeferredCodec activeCodec = new FakeDeferredCodec();
        registry.register(activeCodec.registration());
        FakeCodec otherCodec = new FakeCodec(OTHER_CODEC_ID, 10, false, false);
        registry.register(otherCodec.registration());
        StreamRuntime active = deferredRuntime(registry, STREAM_KEY, clock);
        StreamRuntime inactive = newRuntime(registry, OTHER_STREAM_KEY,
                fakeDescriptor(OTHER_STREAM_KEY, 1, OTHER_CODEC_ID, 64, 48), clock);
        active.start();
        inactive.start();
        assertEquals(RuntimeState.READY, inactive.getState());
        StreamHub hub = newHub(registry, clock);
        hub.register(active);
        hub.register(inactive);
        hub.activate(STREAM_KEY);

        assertNull(active.getDescriptor());
        assertEquals(RuntimeState.CONFIGURING, active.getState());

        // Neither call may throw: the hub must guard on stream keys only.
        otherCodec.getLastEncoder().emitNonJoin(new byte[]{0x51});
        inactive.switchDescriptor(fakeDescriptor(OTHER_STREAM_KEY, 2, OTHER_CODEC_ID, 32, 24));

        assertEquals("a non-active stream's AU must not reach any session", 0,
                hub.getSessionCount());
        assertFalse(hub.drainOne(CLIENT_A));
        assertSame(active, hub.getActiveStream());
        assertEquals(RuntimeState.CONFIGURING, active.getState());
        assertNull(active.getDescriptor());
        assertFalse(activeCodec.getLastEncoder().isClosed());
        assertEquals(0, activeCodec.getLastEncoder().getRequestCount());
    }

    @Test
    public void encoderFailureAfterPublish_detachesAllSessionsAndClosesTransports()
            throws Exception {
        MutableClock clock = new MutableClock();
        FakeDeferredCodec codec = new FakeDeferredCodec();
        CodecRegistry registry = registryOf(codec.registration());
        StreamRuntime runtime = deferredRuntime(registry, STREAM_KEY, clock);
        runtime.start();
        FakeDeferredCodec.FakeEncoder encoder = codec.getLastEncoder();
        encoder.emitConfig(encoderConfig(1280, 720));
        final StreamHub hub = newHub(registry, clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        final AtomicBoolean hubLockFree = new AtomicBoolean();
        final CountDownLatch probeDone = new CountDownLatch(1);
        FakeTransport transportA = new FakeTransport() {
            @Override
            public void close() {
                super.close();
                Thread probe = new Thread(() -> {
                    hub.getActiveStream();
                    hubLockFree.set(true);
                    probeDone.countDown();
                }, "hub-lock-probe");
                probe.setDaemon(true);
                probe.start();
                try {
                    probeDone.await(5L, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        FakeTransport transportB = new FakeTransport();
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), transportA).getStatus());
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_B, capabilities(ACTIVE_CODEC_ID), transportB).getStatus());
        ClientSendSession sessionA = hub.getSession(CLIENT_A);
        ClientSendSession sessionB = hub.getSession(CLIENT_B);
        encoder.emit(true, 1, new byte[]{0x31});
        drainToStreaming(hub, CLIENT_A, transportA);
        drainToStreaming(hub, CLIENT_B, transportB);
        assertEquals(ClientState.STREAMING, sessionA.getState());
        assertEquals(ClientState.STREAMING, sessionB.getState());

        encoder.fail(new EncoderException("injected encoder failure after publish"));

        assertEquals(RuntimeState.FAILED, runtime.getState());
        assertEquals(0, hub.getSessionCount());
        assertNull(hub.getSession(CLIENT_A));
        assertNull(hub.getSession(CLIENT_B));
        assertTrue(sessionA.isDetached());
        assertTrue(sessionB.isDetached());
        assertEquals(ClientState.CLOSED, sessionA.getState());
        assertEquals(ClientState.CLOSED, sessionB.getState());
        assertEquals(1, transportA.getCloseCount());
        assertEquals(1, transportB.getCloseCount());
        assertTrue(transportA.isClosed());
        assertTrue(transportB.isClosed());
        assertTrue("the hub must detach sessions outside its lock", hubLockFree.get());
        assertEquals(0L, probeDone.getCount());
        assertSame("the failed runtime must stay active", runtime, hub.getActiveStream());

        // A repeated failure must not detach the same sessions twice.
        encoder.fail(new EncoderException("second failure"));
        assertEquals(1, transportA.getCloseCount());
        assertEquals(1, transportB.getCloseCount());

        encoder.emit(true, 1, new byte[]{0x32});
        assertEquals(0, hub.getSessionCount());
        assertEquals(2, transportA.getEvents().size());
    }

    @Test
    public void encoderFailureAfterPublish_joinReturnsNotReady() {
        MutableClock clock = new MutableClock();
        FakeDeferredCodec codec = new FakeDeferredCodec();
        CodecRegistry registry = registryOf(codec.registration());
        StreamRuntime runtime = deferredRuntime(registry, STREAM_KEY, clock);
        runtime.start();
        FakeDeferredCodec.FakeEncoder encoder = codec.getLastEncoder();
        encoder.emitConfig(encoderConfig(1280, 720));
        StreamHub hub = newHub(registry, clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), new FakeTransport())
                        .getStatus());
        assertEquals(1, encoder.getRequestCount());

        encoder.fail(new EncoderException("injected encoder failure"));

        assertEquals(RuntimeState.FAILED, runtime.getState());
        assertEquals(0, hub.getSessionCount());
        assertSame(runtime, hub.getActiveStream());

        FakeTransport transport = new FakeTransport();
        JoinDecision decision = hub.join(CLIENT_B, capabilities(ACTIVE_CODEC_ID), transport);

        assertEquals(JoinDecision.Status.NOT_READY, decision.getStatus());
        assertNull(decision.getSession());
        assertNotNull(decision.getReason());
        assertEquals(0, hub.getSessionCount());
        assertNull(hub.getSession(CLIENT_B));
        assertTrue(transport.getEvents().isEmpty());
        assertEquals(0, transport.getCloseCount());
        assertEquals("a failed runtime must not request join points", 1,
                encoder.getRequestCount());
        assertSame("the hub must not rebuild or switch streams", runtime,
                hub.getActiveStream());
    }

    @Test
    public void encoderFailureFirstDetachThrows_othersStillClosedAndJoinNotReady() throws Exception {
        MutableClock clock = new MutableClock();
        FakeDeferredCodec codec = new FakeDeferredCodec();
        CodecRegistry registry = registryOf(codec.registration());
        StreamRuntime runtime = deferredRuntime(registry, STREAM_KEY, clock);
        runtime.start();
        FakeDeferredCodec.FakeEncoder encoder = codec.getLastEncoder();
        encoder.emitConfig(encoderConfig(1280, 720));
        StreamHub hub = newHub(registry, clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        // Exactly one detach throws: the first close attempt consumes the shared pending failure,
        // independent of the hub's session iteration order.
        AtomicBoolean closeFailurePending = new AtomicBoolean(true);
        CloseFailingTransport transportA = new CloseFailingTransport(closeFailurePending);
        CloseFailingTransport transportB = new CloseFailingTransport(closeFailurePending);
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), transportA).getStatus());
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_B, capabilities(ACTIVE_CODEC_ID), transportB).getStatus());
        ClientSendSession sessionA = hub.getSession(CLIENT_A);
        ClientSendSession sessionB = hub.getSession(CLIENT_B);
        assertNotNull(sessionA);
        assertNotNull(sessionB);
        encoder.emit(true, 1, new byte[]{0x31});
        drainToStreaming(hub, CLIENT_A, transportA);
        drainToStreaming(hub, CLIENT_B, transportB);

        try {
            encoder.fail(new EncoderException("injected encoder failure"));
        } catch (RuntimeException e) {
            fail("onEncoderFailed must not propagate a detach failure: " + e);
        }

        assertEquals(RuntimeState.FAILED, runtime.getState());
        assertSame("the failed runtime must stay active", runtime, hub.getActiveStream());
        assertEquals(0, hub.getSessionCount());
        assertNull(hub.getSession(CLIENT_A));
        assertNull(hub.getSession(CLIENT_B));
        assertTrue("every session must be detached even when one detach throws",
                sessionA.isDetached());
        assertTrue("a failing detach must not stop the remaining sessions",
                sessionB.isDetached());
        assertEquals(ClientState.CLOSED, sessionA.getState());
        assertEquals(ClientState.CLOSED, sessionB.getState());
        assertEquals("each transport close must be attempted once", 1,
                transportA.getCloseAttempts());
        assertEquals("each transport close must be attempted once", 1,
                transportB.getCloseAttempts());
        assertEquals(1, transportA.getCloseCount());
        assertEquals("the transport after the throwing detach must still be closed", 1,
                transportB.getCloseCount());
        assertTrue(transportA.isClosed());
        assertTrue(transportB.isClosed());

        FakeTransport lateTransport = new FakeTransport();
        JoinDecision decision = hub.join(CLIENT_C, capabilities(ACTIVE_CODEC_ID), lateTransport);
        assertEquals(JoinDecision.Status.NOT_READY, decision.getStatus());
        assertNull(decision.getSession());
        assertEquals(0, hub.getSessionCount());
        assertTrue(lateTransport.getEvents().isEmpty());
        assertEquals(0, lateTransport.getCloseCount());

        encoder.fail(new EncoderException("second failure"));
        assertEquals("a repeated failure must not close transports twice", 1,
                transportA.getCloseAttempts());
        assertEquals(1, transportB.getCloseCount());
    }

    @Test
    public void encoderFailureMultipleTransportCloseFailures_areIsolated() throws Exception {
        MutableClock clock = new MutableClock();
        FakeDeferredCodec codec = new FakeDeferredCodec();
        CodecRegistry registry = registryOf(codec.registration());
        StreamRuntime runtime = deferredRuntime(registry, STREAM_KEY, clock);
        runtime.start();
        FakeDeferredCodec.FakeEncoder encoder = codec.getLastEncoder();
        encoder.emitConfig(encoderConfig(1280, 720));
        StreamHub hub = newHub(registry, clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        CloseFailingTransport transportA = new CloseFailingTransport();
        CloseFailingTransport transportB = new CloseFailingTransport();
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), transportA).getStatus());
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_B, capabilities(ACTIVE_CODEC_ID), transportB).getStatus());
        ClientSendSession sessionA = hub.getSession(CLIENT_A);
        ClientSendSession sessionB = hub.getSession(CLIENT_B);
        assertNotNull(sessionA);
        assertNotNull(sessionB);
        encoder.emit(true, 1, new byte[]{0x41});
        drainToStreaming(hub, CLIENT_A, transportA);
        drainToStreaming(hub, CLIENT_B, transportB);

        try {
            encoder.fail(new EncoderException("injected encoder failure"));
        } catch (RuntimeException e) {
            fail("every detach failure must be isolated: " + e);
        }

        assertEquals(RuntimeState.FAILED, runtime.getState());
        assertSame(runtime, hub.getActiveStream());
        assertEquals(0, hub.getSessionCount());
        assertNull(hub.getSession(CLIENT_A));
        assertNull(hub.getSession(CLIENT_B));
        assertTrue(sessionA.isDetached());
        assertTrue(sessionB.isDetached());
        assertEquals(ClientState.CLOSED, sessionA.getState());
        assertEquals(ClientState.CLOSED, sessionB.getState());
        assertEquals(1, transportA.getCloseAttempts());
        assertEquals(1, transportB.getCloseAttempts());
        assertTrue(transportA.isClosed());
        assertTrue(transportB.isClosed());

        FakeTransport lateTransport = new FakeTransport();
        assertEquals(JoinDecision.Status.NOT_READY,
                hub.join(CLIENT_C, capabilities(ACTIVE_CODEC_ID), lateTransport).getStatus());
        assertEquals(0, hub.getSessionCount());
        assertTrue(lateTransport.getEvents().isEmpty());
        assertEquals(0, lateTransport.getCloseCount());
    }

    @Test
    public void encoderFailureBeforePublish_hasNoSessionsAndAllowsRetry() throws Exception {
        MutableClock clock = new MutableClock();
        FakeDeferredCodec codec = new FakeDeferredCodec();
        CodecRegistry registry = registryOf(codec.registration());
        StreamRuntime runtime = deferredRuntime(registry, STREAM_KEY, clock);
        runtime.start();
        FakeDeferredCodec.FakeEncoder failedAttempt = codec.getLastEncoder();
        StreamHub hub = newHub(registry, clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        FakeTransport rejectedTransport = new FakeTransport();
        assertEquals(JoinDecision.Status.NOT_READY,
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), rejectedTransport)
                        .getStatus());
        assertEquals(0, hub.getSessionCount());

        failedAttempt.fail(new EncoderException("injected failure before publish"));

        assertEquals(RuntimeState.FAILED, runtime.getState());
        assertNull(runtime.getDescriptor());
        assertEquals(0, hub.getSessionCount());
        assertSame(runtime, hub.getActiveStream());
        assertEquals(0, rejectedTransport.getCloseCount());
        assertTrue(rejectedTransport.getEvents().isEmpty());
        assertEquals(JoinDecision.Status.NOT_READY,
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), rejectedTransport)
                        .getStatus());

        runtime.start();

        FakeDeferredCodec.FakeEncoder retried = codec.getLastEncoder();
        assertNotSame(failedAttempt, retried);
        assertEquals(RuntimeState.CONFIGURING, runtime.getState());
        assertNull(runtime.getDescriptor());
        assertEquals(JoinDecision.Status.NOT_READY,
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), rejectedTransport)
                        .getStatus());
        assertEquals(0, hub.getSessionCount());

        retried.emitConfig(encoderConfig(640, 360));
        assertEquals(RuntimeState.READY, runtime.getState());

        FakeTransport acceptedTransport = new FakeTransport();
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), acceptedTransport)
                        .getStatus());
        assertEquals(1, retried.getRequestCount());
        assertTrue(hub.drainOne(CLIENT_A));
        assertEquals(1, acceptedTransport.getEvents().size());
        assertTrue(acceptedTransport.getEvents().get(0).isDescriptor());
        assertEquals(640,
                acceptedTransport.getEvents().get(0).getDescriptor().getCodedWidth());
        assertTrue(rejectedTransport.getEvents().isEmpty());
    }

    @Test
    public void encoderFailureFromNonActiveStream_isIgnored() throws Exception {
        MutableClock clock = new MutableClock();
        CodecRegistry registry = new CodecRegistry();
        FakeCodec activeCodec = register(registry, FakeCodec.DEFAULT_CODEC_ID, 0, false);
        FakeDeferredCodec otherCodec = new FakeDeferredCodec(OTHER_CODEC_ID);
        registry.register(otherCodec.registration());
        StreamRuntime active = newRuntime(registry, STREAM_KEY,
                fakeDescriptor(STREAM_KEY, 1, FakeCodec.DEFAULT_CODEC_ID, 64, 48), clock);
        StreamRuntime inactive = newRuntime(registry, OTHER_STREAM_KEY,
                deferredDescriptor(OTHER_STREAM_KEY, 1, OTHER_CODEC_ID, 1920, 1080), clock);
        active.start();
        inactive.start();
        StreamHub hub = newHub(registry, clock);
        hub.register(active);
        hub.register(inactive);
        hub.activate(STREAM_KEY);

        FakeTransport transport = new FakeTransport();
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_A, capabilities(FakeCodec.DEFAULT_CODEC_ID), transport)
                        .getStatus());
        ClientSendSession session = hub.getSession(CLIENT_A);
        assertNotNull(session);
        activeCodec.emitJoin(new byte[]{0x41});
        drainToStreaming(hub, CLIENT_A, transport);
        assertEquals(ClientState.STREAMING, session.getState());
        StreamDescriptor activeDescriptor = runtimeDescriptor(hub);

        otherCodec.getLastEncoder().fail(new EncoderException("inactive stream failure"));

        assertEquals(RuntimeState.FAILED, inactive.getState());
        assertSame(active, hub.getActiveStream());
        assertEquals(RuntimeState.READY, active.getState());
        assertEquals(1, hub.getSessionCount());
        assertSame(session, hub.getSession(CLIENT_A));
        assertFalse(session.isDetached());
        assertEquals(ClientState.STREAMING, session.getState());
        assertEquals(0, transport.getCloseCount());
        assertEquals(2, transport.getEvents().size());
        assertEquals(activeDescriptor.getConfigHash(),
                session.getCurrentDescriptor().getConfigHash());

        FakeTransport secondTransport = new FakeTransport();
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_B, capabilities(FakeCodec.DEFAULT_CODEC_ID), secondTransport)
                        .getStatus());
        assertEquals(2, hub.getSessionCount());
    }

    @Test
    public void lateJoin_advancesClockToBypassJoinRateLimit() {
        MutableClock clock = new MutableClock();
        FakeDeferredCodec codec = new FakeDeferredCodec();
        CodecRegistry registry = registryOf(codec.registration());
        StreamRuntime runtime = deferredRuntime(registry, STREAM_KEY, clock);
        runtime.start();
        FakeDeferredCodec.FakeEncoder encoder = codec.getLastEncoder();
        encoder.emitConfig(encoderConfig(1280, 720));
        StreamHub hub = newHub(registry, clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), new FakeTransport())
                        .getStatus());
        assertEquals(1, encoder.getRequestCount());

        // Advancing the clock alone is not enough while the first join point is pending.
        clock.advance(JOIN_INTERVAL_MS + 1L);
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_B, capabilities(ACTIVE_CODEC_ID), new FakeTransport())
                        .getStatus());
        assertEquals("the pending join point must coalesce the second request", 1,
                encoder.getRequestCount());

        // The first join point clears pendingJoin and records the join timestamp.
        encoder.emit(true, 1, new byte[]{0x61});
        clock.advance(JOIN_INTERVAL_MS + 1L);

        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_C, capabilities(ACTIVE_CODEC_ID), new FakeTransport())
                        .getStatus());
        assertEquals("the rate limit must allow a request after the interval", 2,
                encoder.getRequestCount());
    }

    /**
     * Transport whose {@link #close()} marks it closed once and then throws, so a detach failure is
     * observable without changing the shared fake. The throw happens on every call; the session is
     * expected to attempt the close only once.
     */
    private static final class CloseFailingTransport extends FakeTransport {

        private final AtomicBoolean throwPending;
        private final AtomicInteger closeAttempts = new AtomicInteger();

        CloseFailingTransport() {
            this(new AtomicBoolean(true));
        }

        CloseFailingTransport(AtomicBoolean throwPending) {
            this.throwPending = throwPending;
        }

        @Override
        public void close() {
            closeAttempts.incrementAndGet();
            super.close();
            if (throwPending.compareAndSet(true, false)) {
                throw new RuntimeException("injected transport close failure");
            }
        }

        int getCloseAttempts() {
            return closeAttempts.get();
        }
    }

    private static CodecRegistry registryOf(CodecRegistration registration) {
        CodecRegistry registry = new CodecRegistry();
        registry.register(registration);
        return registry;
    }

    private static FakeCodec register(CodecRegistry registry, String codecId, int priority) {
        FakeCodec codec = new FakeCodec(codecId, priority, false, false);
        registry.register(codec.registration());
        return codec;
    }

    private static FakeCodec register(CodecRegistry registry, String codecId, int priority,
                                      boolean autoEmitJoinOnRequest) {
        FakeCodec codec = new FakeCodec(codecId, priority, false, false);
        codec.setAutoEmitJoinOnRequest(autoEmitJoinOnRequest);
        registry.register(codec.registration());
        return codec;
    }

    private static StreamHub newHub(CodecRegistry registry, MutableClock clock) {
        return new StreamHub(registry, new StreamQueueLimits(128L, 2, MAX_AGE_MS), clock);
    }

    private static StreamRuntime deferredRuntime(CodecRegistry registry, StreamKey streamKey,
                                                 MutableClock clock) {
        return newRuntime(registry, streamKey, deferredDescriptor(streamKey, 1, 1920, 1080),
                clock);
    }

    private static StreamRuntime newRuntime(CodecRegistry registry, StreamKey streamKey,
                                            StreamDescriptor descriptor, MutableClock clock) {
        return new StreamRuntime(registry, streamKey, descriptor, StreamParameters.DEFAULT,
                clock, JOIN_INTERVAL_MS);
    }

    private static StreamDescriptor deferredDescriptor(StreamKey streamKey, int configEpoch,
                                                       int width, int height) {
        return deferredDescriptor(streamKey, configEpoch, FakeDeferredCodec.DEFAULT_CODEC_ID,
                width, height);
    }

    private static StreamDescriptor deferredDescriptor(StreamKey streamKey, int configEpoch,
                                                       String codecId, int width, int height) {
        return StreamDescriptor.builder(streamKey, configEpoch, codecId,
                        FakeDeferredCodec.DEFAULT_MIME, BitstreamFormat.ANNEX_B)
                .profile("high")
                .level("4.1")
                .codedSize(width, height)
                .visibleSize(width, height)
                .build();
    }

    private static StreamDescriptor fakeDescriptor(StreamKey streamKey, int configEpoch,
                                                   String codecId, int width, int height) {
        return StreamDescriptor.builder(streamKey, configEpoch, codecId,
                        FakeCodec.DEFAULT_MIME, BitstreamFormat.ANNEX_B)
                .codedSize(width, height)
                .visibleSize(width, height)
                .build();
    }

    private static EncoderConfig encoderConfig(int width, int height) {
        return new EncoderConfig(width, height, ColorInfo.UNSPECIFIED, configParts((byte) 0x01));
    }

    private static List<CodecConfigPart> configParts(byte seed) {
        return Arrays.asList(
                new CodecConfigPart("csd-0", new byte[]{(byte) 0x67, seed}),
                new CodecConfigPart("csd-1", new byte[]{(byte) 0x68, seed}));
    }

    private static ClientCapabilities capabilities(String... codecIds) {
        return new ClientCapabilities(new HashSet<>(Arrays.asList(codecIds)));
    }

    private static void drainToStreaming(StreamHub hub, ClientSessionKey key,
                                         FakeTransport transport) throws IOException {
        ClientSendSession session = hub.getSession(key);
        assertNotNull(session);
        assertEquals(ClientState.WAIT_RANDOM_ACCESS, session.getState());
        assertTrue(hub.drainOne(key));
        assertTrue(hub.drainOne(key));
        assertEquals(ClientState.STREAMING, session.getState());
        assertEquals(2, transport.getEvents().size());
        assertTrue(transport.getEvents().get(0).isDescriptor());
        assertTrue(transport.getEvents().get(1).isAccessUnit());
    }

    private static StreamDescriptor runtimeDescriptor(StreamHub hub) {
        StreamRuntime active = hub.getActiveStream();
        assertNotNull(active);
        StreamDescriptor descriptor = active.getDescriptor();
        assertNotNull(descriptor);
        return descriptor;
    }
}
