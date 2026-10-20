package com.obby.android.localscreenshare.core.stream;

import com.obby.android.localscreenshare.core.client.ClientMediaSession;
import com.obby.android.localscreenshare.core.codec.CodecIds;
import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.codec.EncoderException;
import com.obby.android.localscreenshare.core.codec.h264.H264Codec;
import com.obby.android.localscreenshare.core.codec.mediacodec.MediaCodecBridgeFactory;
import com.obby.android.localscreenshare.core.codec.mediacodec.MediaCodecDecoderBridge;
import com.obby.android.localscreenshare.core.codec.mediacodec.MediaCodecEncoderBridge;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.model.ClientState;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.model.StreamParameters;
import com.obby.android.localscreenshare.core.support.FakeMediaCodecBridgeFactory;
import com.obby.android.localscreenshare.core.support.FakeMediaCodecDecoderBridge;
import com.obby.android.localscreenshare.core.support.FakeMediaCodecEncoderBridge;
import com.obby.android.localscreenshare.core.support.FakeRenderTarget;
import com.obby.android.localscreenshare.core.support.FakeTransport;
import com.obby.android.localscreenshare.core.support.H264Fixtures;
import com.obby.android.localscreenshare.core.support.MutableClock;
import com.obby.android.localscreenshare.core.support.TestH264Registration;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
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
 * JVM end-to-end tests for a single H.264 stream: encoder adapter, runtime, hub, per-client send
 * session and client media session, wired exactly as production wires them but over the JVM fakes.
 *
 * <p><b>Assembly.</b> The registry holds a
 * {@link TestH264Registration#withoutSurfaceRequirement(MediaCodecBridgeFactory)} registration.
 * Both surface flags are therefore false: the JVM cannot create an Android {@code Surface}, so
 * these tests exercise the bitstream and state machine only. They prove nothing about real Surface
 * availability or about Surface availability changes; that belongs to device tests.</p>
 *
 * <p><b>Join-point pacing.</b> The runtime is built with {@code joinPointMinIntervalMs=100}. A
 * second join request is only possible after the first join's access unit has passed through the
 * runtime (which clears {@code pendingJoin} and records {@code lastJoinMs}); the test then advances
 * the {@link MutableClock} past the interval. Advancing the clock alone is not enough, and no test
 * sleeps to wait for pacing.</p>
 *
 * <p><b>Queue bounds.</b> The hub uses {@link StreamQueueLimits} {@code (1024 bytes, 2 units,
 * 2000 ms)}. The unit bound is deliberately tiny so a client that stops draining overflows on the
 * third queued access unit instead of requiring a long burst.</p>
 *
 * <p><b>Fixed drain order.</b> After a join (or any broadcast) a client is drained with
 * {@code hub.drainOne(key)} until it returns {@code false}; only then are events read from
 * {@link FakeTransport#getEvents()} in order. Every epoch's first event must be a
 * {@code DESCRIPTOR}, and events are fed to {@link ClientMediaSession} in exactly that order, never
 * access unit first.</p>
 *
 * <p><b>Asynchronous waits.</b> Encoder output arrives on the H.264 drain thread. Every wait is a
 * {@link CountDownLatch} armed by a runtime listener before the triggering action and awaited with
 * a bounded timeout; there is no raw {@code Thread.sleep} and no unbounded wait.</p>
 */
public class H264StreamEndToEndTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session-7", "stream-7");

    private static final ClientSessionKey CLIENT_A = new ClientSessionKey(STREAM_KEY, "client-A");

    private static final ClientSessionKey CLIENT_B = new ClientSessionKey(STREAM_KEY, "client-B");

    private static final ClientSessionKey CLIENT_C = new ClientSessionKey(STREAM_KEY, "client-C");

    private static final int CONFIG_EPOCH = 1;

    private static final int WIDTH = 640;

    private static final int HEIGHT = 480;

    private static final long JOIN_INTERVAL_MS = 100L;

    /** Upper bound for an expected callback; generous because it only guards against hangs. */
    private static final long AWAIT_TIMEOUT_MS = 5_000L;

    private static final byte[] IDR = H264Fixtures.annexB(H264Fixtures.idrSlice());

    private static final byte[] P_FRAME = H264Fixtures.annexB(H264Fixtures.pSlice());

    /**
     * A NAL unit with {@code nal_unit_type=1} (non-IDR) whose slice type is I. MediaCodec-style
     * key-frame flags are not evidence of an IDR; this payload is the counter-example used by the
     * join-point test.
     */
    private static final byte[] NON_IDR_I_SLICE =
            H264Fixtures.annexB(H264Fixtures.iSliceTypeOne());

    @Test
    public void initialConfigThenJoin_descriptorBeforeFirstIdr() throws Exception {
        FakeFixture fixture = newFixture();
        try {
            fixture.runtime.start();

            assertEquals(RuntimeState.CONFIGURING, fixture.runtime.getState());
            assertNull(fixture.runtime.getDescriptor());

            FakeTransport transport = new FakeTransport();
            JoinDecision early = fixture.hub.join(CLIENT_A, capabilities(), transport);
            assertEquals(JoinDecision.Status.NOT_READY, early.getStatus());
            assertNull(early.getSession());
            assertEquals(0, fixture.hub.getSessionCount());
            assertEquals("a rejected join must not request a join point",
                    0, fixture.bridges.getLastEncoder().getRequestSyncFrameCount());

            CountDownLatch descriptorGate = fixture.probe.expectDescriptor();
            fixture.bridges.getLastEncoder().enqueueFormatChange(
                    H264Fixtures.sps(), H264Fixtures.pps(), WIDTH, HEIGHT);
            await(descriptorGate, "H.264 descriptor publication");

            assertEquals(RuntimeState.READY, fixture.runtime.getState());
            StreamDescriptor published = fixture.runtime.getDescriptor();
            assertNotNull(published);
            assertEquals(CONFIG_EPOCH, published.getConfigEpoch());
            assertEquals(2, published.getConfigParts().size());

            fixture.bridges.getLastEncoder().setSyncResponse(IDR);
            CountDownLatch joinPointGate = fixture.probe.expectAccessUnit();
            JoinDecision joined = fixture.hub.join(CLIENT_A, capabilities(), transport);
            assertEquals(JoinDecision.Status.ACCEPTED, joined.getStatus());
            assertSame(joined.getSession(), fixture.hub.getSession(CLIENT_A));
            await(joinPointGate, "first IDR requested by the join");

            assertEquals(2, drainUntilEmpty(fixture.hub, CLIENT_A));

            List<FakeTransport.Event> events = transport.getEvents();
            assertEquals(2, events.size());
            assertTrue("the descriptor must be the first event of the epoch",
                    events.get(0).isDescriptor());
            assertEquals(published.getConfigHash(), events.get(0).getDescriptor().getConfigHash());
            assertTrue(events.get(1).isAccessUnit());

            EncodedAccessUnit firstIdr = events.get(1).getAccessUnit();
            assertEquals(0L, firstIdr.getSequence());
            assertTrue(firstIdr.getFlags().isJoinPoint());
            assertArrayEquals(IDR, firstIdr.getPayload());
            assertDescriptorFirstPerEpoch(events);
            assertEquals(ClientState.STREAMING, fixture.hub.getSession(CLIENT_A).getState());

            ClientMediaSession client = client(CLIENT_A, fixture.registry);
            feed(client, events);
            assertEquals(ClientState.STREAMING, client.getState());

            FakeMediaCodecDecoderBridge decoder = fixture.bridges.getLastDecoder();
            assertNotNull(decoder);
            assertEquals(1, decoder.getQueuedInputData().size());
            assertArrayEquals(IDR, decoder.getQueuedInputData().get(0));
            client.close();
        } finally {
            cleanup(fixture);
        }
    }

    @Test
    public void lateJoin_advancesClockAndReceivesNextIdrOnly() throws Exception {
        FakeFixture fixture = newFixture();
        try {
            startConfigured(fixture);
            FakeTransport transportA = new FakeTransport();
            FakeTransport transportB = new FakeTransport();

            connectTwoStreamingClients(fixture, transportA, transportB);

            assertEquals("each accepted join must request one sync frame",
                    2, fixture.bridges.getLastEncoder().getRequestSyncFrameCount());

            List<FakeTransport.Event> eventsA = transportA.getEvents();
            List<FakeTransport.Event> eventsB = transportB.getEvents();
            assertEquals(3, eventsA.size());
            assertEquals(2, eventsB.size());
            assertEquals(Arrays.asList(0L, 1L), sequences(eventsA));
            assertEquals(Collections.singletonList(1L), sequences(eventsB));
            assertFalse("the late client must not receive an access unit from before its join",
                    sequences(eventsB).contains(0L));
            assertDescriptorFirstPerEpoch(eventsA);
            assertDescriptorFirstPerEpoch(eventsB);

            // Client A keeps streaming: the second join's IDR is broadcast to it as well.
            assertEquals(ClientState.STREAMING, fixture.hub.getSession(CLIENT_A).getState());

            ClientMediaSession clientB = client(CLIENT_B, fixture.registry);
            feed(clientB, eventsB);
            assertEquals(ClientState.STREAMING, clientB.getState());
            clientB.close();

            ClientMediaSession clientA = client(CLIENT_A, fixture.registry);
            feed(clientA, eventsA);
            assertEquals(ClientState.STREAMING, clientA.getState());
            clientA.close();
        } finally {
            cleanup(fixture);
        }
    }

    @Test
    public void slowClientOverflow_resyncsAndRecoversOnlyOnIdr() throws Exception {
        FakeFixture fixture = newFixture();
        try {
            startConfigured(fixture);
            FakeMediaCodecEncoderBridge encoder = fixture.bridges.getLastEncoder();
            FakeTransport transportA = new FakeTransport();
            FakeTransport transportB = new FakeTransport();

            connectTwoStreamingClients(fixture, transportA, transportB);
            ClientSendSession sessionA = fixture.hub.getSession(CLIENT_A);
            ClientSendSession sessionB = fixture.hub.getSession(CLIENT_B);
            assertNotNull(sessionA);
            assertNotNull(sessionB);

            // A stops draining while B keeps up. With maxUnits=2, the third queued P frame
            // overflows A's queue and should not disturb B.
            long ptsUs = 100_000L;
            for (int i = 0; i < 4; i++) {
                CountDownLatch gate = fixture.probe.expectAccessUnit();
                encoder.enqueueSample(P_FRAME, ptsUs, false, false);
                await(gate, "P frame " + i);
                ptsUs += 33_333L;
                assertEquals(1, drainUntilEmpty(fixture.hub, CLIENT_B));
            }

            assertEquals(ClientState.RESYNCING, sessionA.getState());
            assertEquals(1L, sessionA.getStats().getResyncCount());
            assertTrue("the overflowing client must count dropped units",
                    sessionA.getStats().getDroppedUnits() > 0L);
            assertEquals("B must remain unaffected by A's overflow",
                    ClientState.STREAMING, sessionB.getState());

            CountDownLatch recoveryGate = fixture.probe.expectAccessUnit();
            encoder.enqueueSample(IDR, ptsUs, true, false);
            await(recoveryGate, "recovery IDR");
            assertEquals(1, drainUntilEmpty(fixture.hub, CLIENT_B));
            assertEquals("A must recover from RESYNCING only on the IDR",
                    1, drainUntilEmpty(fixture.hub, CLIENT_A));
            assertEquals(ClientState.STREAMING, sessionA.getState());

            List<FakeTransport.Event> eventsA = transportA.getEvents();
            List<FakeTransport.Event> eventsB = transportB.getEvents();
            assertEquals("A missed the overflowed P frames and resumed at the IDR",
                    Arrays.asList(0L, 1L, 6L), sequences(eventsA));
            assertEquals("B's sequence stays contiguous across A's overflow",
                    Arrays.asList(1L, 2L, 3L, 4L, 5L, 6L), sequences(eventsB));
            assertEquals(4, eventsA.size());
            assertEquals(7, eventsB.size());
            assertDescriptorFirstPerEpoch(eventsA);
            assertDescriptorFirstPerEpoch(eventsB);

            ClientMediaSession clientB = client(CLIENT_B, fixture.registry);
            feed(clientB, eventsB);
            assertEquals(ClientState.STREAMING, clientB.getState());
            clientB.close();

            ClientMediaSession clientA = client(CLIENT_A, fixture.registry);
            feed(clientA, eventsA);
            assertEquals(ClientState.STREAMING, clientA.getState());
            clientA.close();
        } finally {
            cleanup(fixture);
        }
    }

    @Test
    public void dynamicBitrate_doesNotChangeEpochOrDescriptor() throws Exception {
        FakeFixture fixture = newFixture();
        try {
            startConfigured(fixture);
            FakeMediaCodecEncoderBridge encoder = fixture.bridges.getLastEncoder();
            StreamDescriptor before = fixture.runtime.getDescriptor();
            assertNotNull(before);
            int createdBefore = fixture.bridges.getCreatedEncoders().size();
            assertEquals(0, encoder.getBitrateUpdates().size());

            fixture.runtime.updateParameters(
                    StreamParameters.DEFAULT.withTargetBitrate(1_500_000));

            assertEquals("only the new bitrate may reach the bridge",
                    Collections.singletonList(1_500_000), encoder.getBitrateUpdates());
            assertEquals(1, encoder.getConfigureCount());
            assertEquals(1, encoder.getCreateInputSurfaceCount());
            assertEquals(1, encoder.getStartCount());
            assertEquals("a bitrate change must not recreate the encoder",
                    createdBefore, fixture.bridges.getCreatedEncoders().size());
            assertEquals(RuntimeState.READY, fixture.runtime.getState());
            assertEquals(CONFIG_EPOCH, fixture.runtime.getConfigEpoch());
            assertEquals(before.getConfigHash(), fixture.runtime.getDescriptor().getConfigHash());
            assertEquals("a bitrate change must not publish a descriptor",
                    1, fixture.probe.getDescriptorCount());
            assertEquals(0, fixture.probe.getAccessUnitCount());
            assertEquals(1_500_000, fixture.runtime.getParameters().getTargetBitrateBps());
        } finally {
            cleanup(fixture);
        }
    }

    @Test
    public void encoderFailure_detachesClientsAndRejectsJoin() throws Exception {
        FakeFixture fixture = newFixture();
        try {
            startConfigured(fixture);
            FakeMediaCodecEncoderBridge encoder = fixture.bridges.getLastEncoder();
            FakeTransport transportA = new FakeTransport();
            FakeTransport transportB = new FakeTransport();

            connectTwoStreamingClients(fixture, transportA, transportB);
            ClientSendSession sessionA = fixture.hub.getSession(CLIENT_A);
            ClientSendSession sessionB = fixture.hub.getSession(CLIENT_B);
            assertNotNull(sessionA);
            assertNotNull(sessionB);

            CountDownLatch failureGate = fixture.probe.expectFailure();
            encoder.failNextDequeue(new RuntimeException("injected dequeue failure"));
            await(failureGate, "encoder failure notification");

            assertEquals(RuntimeState.FAILED, fixture.runtime.getState());
            assertEquals(1, fixture.probe.getFailureCount());
            assertEquals("the hub must detach every session on encoder failure",
                    0, fixture.hub.getSessionCount());
            assertNull(fixture.hub.getSession(CLIENT_A));
            assertNull(fixture.hub.getSession(CLIENT_B));
            assertTrue(sessionA.isDetached());
            assertTrue(sessionB.isDetached());
            assertEquals(ClientState.CLOSED, sessionA.getState());
            assertEquals(ClientState.CLOSED, sessionB.getState());
            assertTrue("a detached client transport must be closed", transportA.isClosed());
            assertTrue(transportB.isClosed());

            JoinDecision late = fixture.hub.join(CLIENT_C, capabilities(), new FakeTransport());
            assertEquals(JoinDecision.Status.NOT_READY, late.getStatus());
            assertNull(late.getSession());
        } finally {
            cleanup(fixture);
        }
    }

    @Test
    public void transportRuntimeException_isolatedToFailedClient() throws Exception {
        FakeFixture fixture = newFixture();
        try {
            startConfigured(fixture);
            FakeMediaCodecEncoderBridge encoder = fixture.bridges.getLastEncoder();
            FakeTransport transportA = new FakeTransport();
            FakeTransport transportB = new FakeTransport();

            connectTwoStreamingClients(fixture, transportA, transportB);
            ClientSendSession sessionA = fixture.hub.getSession(CLIENT_A);
            ClientSendSession sessionB = fixture.hub.getSession(CLIENT_B);
            assertNotNull(sessionA);
            assertNotNull(sessionB);

            transportA.failNextAccessUnitSendUnchecked();
            CountDownLatch firstGate = fixture.probe.expectAccessUnit();
            encoder.enqueueSample(P_FRAME, 100_000L, false, false);
            await(firstGate, "P frame broadcast");

            assertThrows(IllegalStateException.class, () -> fixture.hub.drainOne(CLIENT_A));
            assertNull("the failing client must be removed from the hub",
                    fixture.hub.getSession(CLIENT_A));
            assertEquals(1, fixture.hub.getSessionCount());
            assertTrue(sessionA.isDetached());
            assertEquals(ClientState.CLOSED, sessionA.getState());
            assertTrue(transportA.isClosed());

            assertTrue("the healthy client must still drain", fixture.hub.drainOne(CLIENT_B));
            assertEquals(ClientState.STREAMING, sessionB.getState());

            CountDownLatch secondGate = fixture.probe.expectAccessUnit();
            encoder.enqueueSample(P_FRAME, 133_333L, false, false);
            await(secondGate, "P frame after the transport failure");
            assertTrue("the encoding loop must not block on a failed client",
                    fixture.hub.drainOne(CLIENT_B));

            List<FakeTransport.Event> eventsB = transportB.getEvents();
            assertEquals(Arrays.asList(1L, 2L, 3L), sequences(eventsB));
            assertDescriptorFirstPerEpoch(eventsB);
            assertEquals(0, encoder.getPendingSampleCount());
        } finally {
            cleanup(fixture);
        }
    }

    @Test
    public void configRetryAfterFailure_noDescriptorFromFailedAttempt() throws Exception {
        FailFirstStartBridgeFactory bridges = new FailFirstStartBridgeFactory();
        FakeFixture fixture = newFixture(bridges, null);
        try {
            assertThrows(EncoderException.class, fixture.runtime::start);

            assertEquals(RuntimeState.FAILED, fixture.runtime.getState());
            assertNull("a failed start must not publish a descriptor",
                    fixture.runtime.getDescriptor());
            assertEquals(1, fixture.probe.getFailureCount());

            FakeMediaCodecEncoderBridge failedAttempt = bridges.getFirstEncoder();
            assertEquals(1, failedAttempt.getConfigureCount());
            assertEquals(1, failedAttempt.getCreateInputSurfaceCount());
            assertEquals(0, failedAttempt.getStartCount());
            assertEquals(1, failedAttempt.getReleaseCount());
            assertEquals("a failed start must not leave a drain consuming the bridge",
                    0, failedAttempt.getPendingSampleCount());

            // Because bridge.start() failed, H264Encoder never launched a drain thread. Injecting
            // a format change and an ordinary sample therefore leaves both events queued and
            // unconsumed, which is what this E2E establishes: a failed H.264 attempt has no drain
            // and cannot surface late bridge events. It is not direct evidence of generation
            // filtering; stale callbacks from an attempt that did drain are covered by the Task 3
            // unit test StreamRuntimeDeferredConfigTest.lateConfigAndAuFromFailedAttempt_areIgnored.
            failedAttempt.enqueueFormatChange(H264Fixtures.sps(), H264Fixtures.pps(), WIDTH, HEIGHT);
            failedAttempt.enqueueSample(IDR, 0L, true, false);
            assertEquals("the failed attempt's bridge must keep both events unconsumed",
                    2, failedAttempt.getPendingSampleCount());
            assertEquals(RuntimeState.FAILED, fixture.runtime.getState());
            assertNull(fixture.runtime.getDescriptor());
            assertEquals(0, fixture.probe.getDescriptorCount());
            assertEquals(0, fixture.probe.getAccessUnitCount());

            fixture.runtime.start();
            assertEquals(RuntimeState.CONFIGURING, fixture.runtime.getState());
            FakeMediaCodecEncoderBridge retryAttempt = bridges.getActiveEncoder();
            assertNotNull(retryAttempt);
            assertNotSame(failedAttempt, retryAttempt);
            assertEquals("the retry must not attach a consumer to the failed attempt",
                    2, failedAttempt.getPendingSampleCount());

            CountDownLatch descriptorGate = fixture.probe.expectDescriptor();
            retryAttempt.enqueueFormatChange(H264Fixtures.sps(), H264Fixtures.pps(), WIDTH, HEIGHT);
            await(descriptorGate, "descriptor from the retry attempt");

            assertEquals(RuntimeState.READY, fixture.runtime.getState());
            StreamDescriptor published = fixture.runtime.getDescriptor();
            assertNotNull(published);
            assertEquals(CONFIG_EPOCH, published.getConfigEpoch());
            assertEquals(WIDTH, published.getCodedWidth());
            assertEquals(HEIGHT, published.getCodedHeight());

            FakeTransport transport = new FakeTransport();
            assertEquals(JoinDecision.Status.ACCEPTED,
                    fixture.hub.join(CLIENT_A, capabilities(), transport).getStatus());
            assertEquals("the join drains the descriptor before any access unit",
                    1, drainUntilEmpty(fixture.hub, CLIENT_A));

            CountDownLatch joinPointGate = fixture.probe.expectAccessUnit();
            retryAttempt.enqueueSample(IDR, 33_000L, true, false);
            await(joinPointGate, "retry attempt IDR");
            assertEquals(1, drainUntilEmpty(fixture.hub, CLIENT_A));

            List<FakeTransport.Event> events = transport.getEvents();
            assertEquals(2, events.size());
            assertTrue(events.get(0).isDescriptor());
            assertEquals(published.getConfigHash(), events.get(0).getDescriptor().getConfigHash());
            assertTrue(events.get(1).isAccessUnit());
            assertTrue(events.get(1).getAccessUnit().getFlags().isJoinPoint());
            assertDescriptorFirstPerEpoch(events);

            assertEquals("the client must see exactly one descriptor", 1,
                    fixture.probe.getDescriptorCount());
            assertEquals("only the retry attempt produced an access unit", 1,
                    fixture.probe.getAccessUnitCount());

            ClientMediaSession client = client(CLIENT_A, fixture.registry);
            feed(client, events);
            assertEquals(ClientState.STREAMING, client.getState());
            assertEquals(published.getConfigHash(),
                    client.getCurrentDescriptor().getConfigHash());
            assertEquals("the failed attempt must stay unconsumed for the whole scenario",
                    2, failedAttempt.getPendingSampleCount());
            client.close();
        } finally {
            cleanup(fixture);
        }
    }

    @Test
    public void firstNonIdrNeverBecomesJoinPoint() throws Exception {
        FakeFixture fixture = newFixture();
        try {
            startConfigured(fixture);
            FakeMediaCodecEncoderBridge encoder = fixture.bridges.getLastEncoder();
            encoder.setSyncResponse(IDR);

            FakeTransport transportA = new FakeTransport();
            CountDownLatch firstJoinPoint = fixture.probe.expectAccessUnit();
            assertEquals(JoinDecision.Status.ACCEPTED,
                    fixture.hub.join(CLIENT_A, capabilities(), transportA).getStatus());
            await(firstJoinPoint, "client A join point");
            assertEquals(2, drainUntilEmpty(fixture.hub, CLIENT_A));

            // B joins late; its sync request must not fabricate an IDR, so the only pending
            // frames are the ones injected explicitly below.
            fixture.clock.advance(101L);
            encoder.setSyncResponse(null);
            FakeTransport transportB = new FakeTransport();
            assertEquals(JoinDecision.Status.ACCEPTED,
                    fixture.hub.join(CLIENT_B, capabilities(), transportB).getStatus());
            assertEquals("only the descriptor is available for B so far",
                    1, drainUntilEmpty(fixture.hub, CLIENT_B));
            assertEquals(ClientState.WAIT_RANDOM_ACCESS,
                    fixture.hub.getSession(CLIENT_B).getState());

            CountDownLatch nonIdrGate = fixture.probe.expectAccessUnit();
            encoder.enqueueSample(NON_IDR_I_SLICE, 100_000L, true, false);
            await(nonIdrGate, "keyFrame-flagged non-IDR I slice");

            assertEquals("the already streaming client receives ordinary frames",
                    1, drainUntilEmpty(fixture.hub, CLIENT_A));
            assertEquals("a non-IDR must never be accepted while waiting for random access",
                    0, drainUntilEmpty(fixture.hub, CLIENT_B));
            assertEquals(ClientState.WAIT_RANDOM_ACCESS,
                    fixture.hub.getSession(CLIENT_B).getState());

            List<FakeTransport.Event> eventsA = transportA.getEvents();
            EncodedAccessUnit nonIdr = eventsA.get(eventsA.size() - 1).getAccessUnit();
            assertFalse("the keyFrame flag must not turn a non-IDR into a join point",
                    nonIdr.getFlags().isJoinPoint());
            assertArrayEquals(NON_IDR_I_SLICE, nonIdr.getPayload());

            CountDownLatch idrGate = fixture.probe.expectAccessUnit();
            encoder.enqueueSample(IDR, 133_333L, true, false);
            await(idrGate, "valid IDR after the non-IDR slice");
            assertEquals(1, drainUntilEmpty(fixture.hub, CLIENT_B));
            assertEquals(1, drainUntilEmpty(fixture.hub, CLIENT_A));
            assertEquals(ClientState.STREAMING, fixture.hub.getSession(CLIENT_B).getState());

            List<FakeTransport.Event> eventsB = transportB.getEvents();
            assertEquals(2, eventsB.size());
            assertEquals(Collections.singletonList(2L), sequences(eventsB));
            EncodedAccessUnit idr = eventsB.get(1).getAccessUnit();
            assertTrue(idr.getFlags().isJoinPoint());
            assertArrayEquals(IDR, idr.getPayload());
            assertDescriptorFirstPerEpoch(eventsB);

            ClientMediaSession clientB = client(CLIENT_B, fixture.registry);
            feed(clientB, eventsB);
            assertEquals(ClientState.STREAMING, clientB.getState());

            FakeMediaCodecDecoderBridge decoder = fixture.bridges.getLastDecoder();
            assertNotNull(decoder);
            assertEquals("the decoder must only ever see the validated IDR",
                    1, decoder.getQueuedInputData().size());
            assertArrayEquals(IDR, decoder.getQueuedInputData().get(0));
            clientB.close();
        } finally {
            cleanup(fixture);
        }
    }

    private static FakeFixture newFixture() {
        FakeMediaCodecBridgeFactory bridges = new FakeMediaCodecBridgeFactory();
        return newFixture(bridges, bridges);
    }

    private static FakeFixture newFixture(MediaCodecBridgeFactory factory,
                                          FakeMediaCodecBridgeFactory bridges) {
        MutableClock clock = new MutableClock();
        CodecRegistry registry = new CodecRegistry();
        registry.register(TestH264Registration.withoutSurfaceRequirement(factory));
        StreamRuntime runtime = new StreamRuntime(registry, STREAM_KEY, requestedDescriptor(),
                StreamParameters.DEFAULT, clock, JOIN_INTERVAL_MS);
        // The deliberately low unit bound is what makes the slow-client overflow reachable
        // without a long burst.
        StreamHub hub = new StreamHub(registry, new StreamQueueLimits(1024L, 2, 2000L), clock);
        Probe probe = new Probe();
        hub.register(runtime);
        runtime.addListener(probe);
        hub.activate(STREAM_KEY);
        return new FakeFixture(clock, registry, runtime, hub, probe, bridges);
    }

    private static StreamDescriptor requestedDescriptor() {
        return StreamDescriptor.builder(STREAM_KEY, CONFIG_EPOCH, CodecIds.H264, H264Codec.MIME,
                        BitstreamFormat.ANNEX_B)
                .profile("baseline")
                .level("3.1")
                .codedSize(WIDTH, HEIGHT)
                .visibleSize(WIDTH, HEIGHT)
                .build();
    }

    private static void startConfigured(FakeFixture fixture) {
        fixture.runtime.start();
        assertEquals(RuntimeState.CONFIGURING, fixture.runtime.getState());

        CountDownLatch descriptorGate = fixture.probe.expectDescriptor();
        fixture.bridges.getLastEncoder().enqueueFormatChange(
                H264Fixtures.sps(), H264Fixtures.pps(), WIDTH, HEIGHT);
        await(descriptorGate, "H.264 descriptor publication");

        assertEquals(RuntimeState.READY, fixture.runtime.getState());
        assertNotNull(fixture.runtime.getDescriptor());
    }

    /**
     * Connects two clients through the normal join path: A joins and receives the requested IDR,
     * the clock passes the join interval, B joins and receives the next IDR, and A drains that IDR
     * too. Both send sessions end in {@link ClientState#STREAMING}.
     */
    private static void connectTwoStreamingClients(FakeFixture fixture, FakeTransport transportA,
                                                   FakeTransport transportB) throws IOException {
        fixture.bridges.getLastEncoder().setSyncResponse(IDR);

        joinWithJoinPoint(fixture, CLIENT_A, transportA);
        fixture.clock.advance(JOIN_INTERVAL_MS + 1L);
        joinWithJoinPoint(fixture, CLIENT_B, transportB);

        assertEquals("the second join point is broadcast to the first client as well",
                1, drainUntilEmpty(fixture.hub, CLIENT_A));
        assertEquals(ClientState.STREAMING, fixture.hub.getSession(CLIENT_A).getState());
        assertEquals(ClientState.STREAMING, fixture.hub.getSession(CLIENT_B).getState());
    }

    private static void joinWithJoinPoint(FakeFixture fixture, ClientSessionKey key,
                                          FakeTransport transport) throws IOException {
        CountDownLatch gate = fixture.probe.expectAccessUnit();
        JoinDecision decision = fixture.hub.join(key, capabilities(), transport);
        assertEquals(JoinDecision.Status.ACCEPTED, decision.getStatus());
        await(gate, "join point for " + key.getClientId());
        assertEquals("descriptor followed by the requested join point",
                2, drainUntilEmpty(fixture.hub, key));
    }

    private static ClientCapabilities capabilities() {
        Set<String> codecIds = new HashSet<>();
        codecIds.add(CodecIds.H264);
        return new ClientCapabilities(codecIds);
    }

    private static ClientMediaSession client(ClientSessionKey key, CodecRegistry registry) {
        return new ClientMediaSession(key, registry, capabilities(), new FakeRenderTarget(), null);
    }

    private static void cleanup(FakeFixture fixture) {
        fixture.hub.unregister(STREAM_KEY);
        fixture.runtime.close();
    }

    private static int drainUntilEmpty(StreamHub hub, ClientSessionKey key) throws IOException {
        int drained = 0;
        while (hub.drainOne(key)) {
            drained++;
            if (drained > 64) {
                fail("drain did not terminate for " + key.getClientId());
            }
        }
        return drained;
    }

    /** Feeds events to a client media session strictly in their transport order. */
    private static void feed(ClientMediaSession client, List<FakeTransport.Event> events) {
        for (FakeTransport.Event event : events) {
            if (event.isDescriptor()) {
                client.onDescriptor(event.getDescriptor());
            } else {
                client.onAccessUnit(event.getAccessUnit());
            }
        }
    }

    private static List<Long> sequences(List<FakeTransport.Event> events) {
        List<Long> result = new ArrayList<>();
        for (FakeTransport.Event event : events) {
            if (event.isAccessUnit()) {
                result.add(event.getAccessUnit().getSequence());
            }
        }
        return result;
    }

    /**
     * Fails when an access unit arrives before the descriptor of its epoch; every epoch that
     * announces access units must also have announced a descriptor.
     */
    private static void assertDescriptorFirstPerEpoch(List<FakeTransport.Event> events) {
        Set<Integer> announcedEpochs = new HashSet<>();
        Set<Integer> accessUnitEpochs = new HashSet<>();
        for (FakeTransport.Event event : events) {
            if (event.isDescriptor()) {
                announcedEpochs.add(event.getDescriptor().getConfigEpoch());
            } else {
                int epoch = event.getAccessUnit().getConfigEpoch();
                accessUnitEpochs.add(epoch);
                assertTrue("descriptor for epoch " + epoch + " must precede its first access unit",
                        announcedEpochs.contains(epoch));
            }
        }
        for (Integer epoch : accessUnitEpochs) {
            assertTrue("epoch " + epoch + " must announce a descriptor",
                    announcedEpochs.contains(epoch));
        }
    }

    private static void await(CountDownLatch gate, String what) {
        try {
            assertTrue("timed out waiting for " + what,
                    gate.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted while waiting for " + what);
        }
    }

    private static final class FakeFixture {

        final MutableClock clock;

        final CodecRegistry registry;

        final StreamRuntime runtime;

        final StreamHub hub;

        final Probe probe;

        /** Null when the fixture is built around a custom bridge factory. */
        final FakeMediaCodecBridgeFactory bridges;

        FakeFixture(MutableClock clock, CodecRegistry registry, StreamRuntime runtime,
                    StreamHub hub, Probe probe, FakeMediaCodecBridgeFactory bridges) {
            this.clock = clock;
            this.registry = registry;
            this.runtime = runtime;
            this.hub = hub;
            this.probe = probe;
            this.bridges = bridges;
        }
    }

    /**
     * Runtime listener that counts callbacks and exposes per-expectation latches. A latch is armed
     * before the action that triggers the callback; the callback clears whatever latch is armed, so
     * no notification can be missed between arming and awaiting.
     */
    private static final class Probe implements StreamRuntimeListener {

        private final AtomicInteger descriptorCount = new AtomicInteger();
        private final AtomicInteger accessUnitCount = new AtomicInteger();
        private final AtomicInteger failureCount = new AtomicInteger();

        private final AtomicReference<CountDownLatch> descriptorGate = new AtomicReference<>();
        private final AtomicReference<CountDownLatch> accessUnitGate = new AtomicReference<>();
        private final AtomicReference<CountDownLatch> failureGate = new AtomicReference<>();

        CountDownLatch expectDescriptor() {
            return arm(descriptorGate);
        }

        CountDownLatch expectAccessUnit() {
            return arm(accessUnitGate);
        }

        CountDownLatch expectFailure() {
            return arm(failureGate);
        }

        int getDescriptorCount() {
            return descriptorCount.get();
        }

        int getAccessUnitCount() {
            return accessUnitCount.get();
        }

        int getFailureCount() {
            return failureCount.get();
        }

        @Override
        public void onAccessUnit(EncodedAccessUnit accessUnit) {
            accessUnitCount.incrementAndGet();
            fire(accessUnitGate);
        }

        @Override
        public void onDescriptorChanged(StreamDescriptor descriptor) {
            descriptorCount.incrementAndGet();
            fire(descriptorGate);
        }

        @Override
        public void onEncoderFailed(StreamKey streamKey, EncoderException failure) {
            failureCount.incrementAndGet();
            fire(failureGate);
        }

        private static CountDownLatch arm(AtomicReference<CountDownLatch> gate) {
            CountDownLatch latch = new CountDownLatch(1);
            gate.set(latch);
            return latch;
        }

        private static void fire(AtomicReference<CountDownLatch> gate) {
            CountDownLatch latch = gate.getAndSet(null);
            if (latch != null) {
                latch.countDown();
            }
        }
    }

    /**
     * Bridge factory that arms the very first encoder bridge with a start failure, so
     * {@code StreamRuntime.start()} fails before any encoder configuration is published. Later
     * encoder requests are delegated to a fresh {@link FakeMediaCodecBridgeFactory}. This only
     * wires the existing fake; it adds no fake capability.
     */
    private static final class FailFirstStartBridgeFactory implements MediaCodecBridgeFactory {

        private final FakeMediaCodecBridgeFactory delegate = new FakeMediaCodecBridgeFactory();
        private final FakeMediaCodecEncoderBridge firstEncoder = new FakeMediaCodecEncoderBridge();

        private boolean firstServed;

        @Override
        public MediaCodecEncoderBridge createEncoder(String mime) {
            if (!firstServed) {
                firstServed = true;
                firstEncoder.failNextStart(new RuntimeException("injected first start failure"));
                return firstEncoder;
            }
            return delegate.createEncoder(mime);
        }

        @Override
        public MediaCodecDecoderBridge createDecoder(String mime) {
            return delegate.createDecoder(mime);
        }

        FakeMediaCodecEncoderBridge getFirstEncoder() {
            return firstEncoder;
        }

        FakeMediaCodecEncoderBridge getActiveEncoder() {
            return delegate.getLastEncoder();
        }
    }
}
