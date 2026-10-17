package com.obby.android.localscreenshare.core.stream;

import com.obby.android.localscreenshare.core.client.ClientMediaSession;
import com.obby.android.localscreenshare.core.codec.CodecIds;
import com.obby.android.localscreenshare.core.codec.CodecRegistration;
import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.codec.ConfigEpochException;
import com.obby.android.localscreenshare.core.codec.jpeg.JpegCodec;
import com.obby.android.localscreenshare.core.codec.jpeg.JpegCodecPolicy;
import com.obby.android.localscreenshare.core.codec.jpeg.JpegDecoderFactory;
import com.obby.android.localscreenshare.core.codec.jpeg.JpegEncoder;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.model.ClientState;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.model.StreamParameters;
import com.obby.android.localscreenshare.core.render.EncodedImageFrame;
import com.obby.android.localscreenshare.core.support.CapturingJpegEncoderFactory;
import com.obby.android.localscreenshare.core.support.FakeCodec;
import com.obby.android.localscreenshare.core.support.FakeRenderTarget;
import com.obby.android.localscreenshare.core.support.FakeTransport;
import com.obby.android.localscreenshare.core.support.JpegFixtures;
import com.obby.android.localscreenshare.core.support.MutableClock;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class StreamFrameworkEndToEndTest {

    private static final String SESSION_INSTANCE_ID = "session-e2e";

    private static final StreamKey STREAM_KEY = new StreamKey(SESSION_INSTANCE_ID, "stream-e2e");

    private static final ClientSessionKey CLIENT_A = new ClientSessionKey(STREAM_KEY, "client-A");

    private static final ClientSessionKey CLIENT_B = new ClientSessionKey(STREAM_KEY, "client-B");

    private static final ClientSessionKey CLIENT_C = new ClientSessionKey(STREAM_KEY, "client-C");

    private static final String ACTIVE_CODEC_ID = FakeCodec.DEFAULT_CODEC_ID;

    private static final long MAX_AGE_MS = 5_000L;

    @Test
    public void twoClientsJoinAtDifferentTimes_joinRequestsAndDescriptorOrder() throws Exception {
        FakeFixture fixture = fakeFixture(128L, 8, 0L, true);
        FakeTransport transportA = new FakeTransport();
        FakeTransport transportB = new FakeTransport();

        assertEquals(JoinDecision.Status.ACCEPTED,
                fixture.hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), transportA).getStatus());
        assertEquals("joining must trigger one runtime join request",
                1, fixture.fake.getRequestCount());
        assertEquals("descriptor and join access unit", 2, drainUntilEmpty(fixture.hub, CLIENT_A));

        fixture.clock.advance(1L);
        assertEquals(JoinDecision.Status.ACCEPTED,
                fixture.hub.join(CLIENT_B, capabilities(ACTIVE_CODEC_ID), transportB).getStatus());
        assertEquals("the late client must trigger the next join request",
                2, fixture.fake.getRequestCount());
        assertEquals(2, drainUntilEmpty(fixture.hub, CLIENT_B));
        assertEquals(1, drainUntilEmpty(fixture.hub, CLIENT_A));

        fixture.fake.emitNonJoin(new byte[]{0x11});
        assertEquals(1, drainUntilEmpty(fixture.hub, CLIENT_A));
        assertEquals(1, drainUntilEmpty(fixture.hub, CLIENT_B));

        fixture.fake.emitNonJoin(new byte[]{0x12});
        assertEquals(1, drainUntilEmpty(fixture.hub, CLIENT_A));
        assertEquals(1, drainUntilEmpty(fixture.hub, CLIENT_B));

        List<FakeTransport.Event> eventsA = transportA.getEvents();
        List<FakeTransport.Event> eventsB = transportB.getEvents();
        assertEquals(5, eventsA.size());
        assertEquals(4, eventsB.size());
        assertDescriptorPrecedesEveryEpoch(eventsA);
        assertDescriptorPrecedesEveryEpoch(eventsB);
        assertEquals(Arrays.asList(0L, 1L, 2L, 3L), accessUnitSequences(eventsA));
        assertEquals(Arrays.asList(1L, 2L, 3L), accessUnitSequences(eventsB));
        assertFalse("client B must not receive access units from before its join",
                accessUnitSequences(eventsB).contains(0L));

        FakeRenderTarget renderTargetA = new FakeRenderTarget();
        FakeRenderTarget renderTargetB = new FakeRenderTarget();
        ClientMediaSession clientA = client(CLIENT_A, fixture.registry, ACTIVE_CODEC_ID,
                renderTargetA);
        ClientMediaSession clientB = client(CLIENT_B, fixture.registry, ACTIVE_CODEC_ID,
                renderTargetB);

        feed(clientA, eventsA);
        feed(clientB, eventsB);

        assertEquals(ClientState.STREAMING, clientA.getState());
        assertEquals(ClientState.STREAMING, clientB.getState());
        assertEquals(4, renderTargetA.getImages().size());
        assertEquals(3, renderTargetB.getImages().size());
    }

    @Test
    public void slowClientOverflow_doesNotBlockOtherClient() throws Exception {
        FakeFixture fixture = fakeFixture(128L, 2, 0L, false);
        FakeTransport transportA = new FakeTransport();
        FakeTransport transportB = new FakeTransport();

        assertEquals(JoinDecision.Status.ACCEPTED,
                fixture.hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), transportA).getStatus());
        assertEquals(JoinDecision.Status.ACCEPTED,
                fixture.hub.join(CLIENT_B, capabilities(ACTIVE_CODEC_ID), transportB).getStatus());
        assertEquals("concurrent joins must coalesce into one runtime request",
                1, fixture.fake.getRequestCount());

        fixture.fake.emitJoin(new byte[]{0x01});
        assertEquals(2, drainUntilEmpty(fixture.hub, CLIENT_A));
        assertEquals(2, drainUntilEmpty(fixture.hub, CLIENT_B));

        ClientSendSession sessionA = fixture.hub.getSession(CLIENT_A);
        ClientSendSession sessionB = fixture.hub.getSession(CLIENT_B);
        assertEquals(ClientState.STREAMING, sessionA.getState());
        assertEquals(ClientState.STREAMING, sessionB.getState());

        // A stops draining from here; B keeps draining every frame.
        for (int i = 1; i <= 5; i++) {
            fixture.fake.emitNonJoin(new byte[]{(byte) i});
            assertEquals(1, drainUntilEmpty(fixture.hub, CLIENT_B));
        }

        assertEquals(ClientState.RESYNCING, sessionA.getState());
        assertTrue("an overflowing send queue must count dropped units",
                sessionA.getStats().getDroppedUnits() > 0L);
        assertEquals(1L, sessionA.getStats().getResyncCount());
        assertEquals(ClientState.STREAMING, sessionB.getState());
        assertEquals("the slow client must not receive the burst",
                2, transportA.getEvents().size());
        assertEquals(7, transportB.getEvents().size());
        assertEquals(Arrays.asList(0L, 1L, 2L, 3L, 4L, 5L),
                accessUnitSequences(transportB.getEvents()));

        fixture.fake.emitJoin(new byte[]{0x7F});
        assertEquals("A must recover from RESYNCING at the new join point",
                1, drainUntilEmpty(fixture.hub, CLIENT_A));
        assertEquals(1, drainUntilEmpty(fixture.hub, CLIENT_B));

        assertEquals(ClientState.STREAMING, sessionA.getState());
        assertEquals(3, transportA.getEvents().size());
        assertEquals(8, transportB.getEvents().size());
        assertEquals(Arrays.asList(0L, 1L, 2L, 3L, 4L, 5L, 6L),
                accessUnitSequences(transportB.getEvents()));
        assertDescriptorPrecedesEveryEpoch(transportA.getEvents());
        assertDescriptorPrecedesEveryEpoch(transportB.getEvents());

        FakeRenderTarget renderTargetB = new FakeRenderTarget();
        ClientMediaSession clientB = client(CLIENT_B, fixture.registry, ACTIVE_CODEC_ID,
                renderTargetB);
        feed(clientB, transportB.getEvents());
        assertEquals(ClientState.STREAMING, clientB.getState());
        assertEquals(7, renderTargetB.getImages().size());
    }

    @Test
    public void configSwitch_keepsSessionAndTransportObjects() throws Exception {
        FakeFixture fixture = fakeFixture(128L, 8, 0L, true);
        FakeTransport transportA = new FakeTransport();
        FakeTransport transportB = new FakeTransport();

        assertEquals(JoinDecision.Status.ACCEPTED,
                fixture.hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), transportA).getStatus());
        assertEquals(2, drainUntilEmpty(fixture.hub, CLIENT_A));

        fixture.clock.advance(1L);
        assertEquals(JoinDecision.Status.ACCEPTED,
                fixture.hub.join(CLIENT_B, capabilities(ACTIVE_CODEC_ID), transportB).getStatus());
        assertEquals(2, drainUntilEmpty(fixture.hub, CLIENT_B));
        assertEquals(1, drainUntilEmpty(fixture.hub, CLIENT_A));

        ClientSendSession sessionA = fixture.hub.getSession(CLIENT_A);
        ClientSendSession sessionB = fixture.hub.getSession(CLIENT_B);
        assertEquals(ClientState.STREAMING, sessionA.getState());
        assertEquals(ClientState.STREAMING, sessionB.getState());

        FakeCodec.FakeEncoder oldEncoder = fixture.fake.getLastEncoder();
        StreamDescriptor activeBefore = fixture.runtime.getDescriptor();
        StreamDescriptor next = descriptor(2, 64, 48, 48, 36);
        assertFalse(activeBefore.sameDecodeConfig(next));

        fixture.runtime.switchDescriptor(next);

        assertSame(fixture.runtime, fixture.hub.getActiveStream());
        assertSame(sessionA, fixture.hub.getSession(CLIENT_A));
        assertSame(sessionB, fixture.hub.getSession(CLIENT_B));
        assertSame(CLIENT_A, sessionA.getKey());
        assertSame(CLIENT_B, sessionB.getKey());
        assertEquals(2, fixture.runtime.getConfigEpoch());
        assertEquals(next.getConfigHash(), fixture.runtime.getDescriptor().getConfigHash());
        assertNotSame("a switch must prewarm a new encoder", oldEncoder,
                fixture.fake.getLastEncoder());
        assertTrue("the old encoder must be stopped", oldEncoder.isStopped());
        assertTrue("the retired encoder must be released after the new join point",
                oldEncoder.isClosed());
        assertEquals(ClientState.WAIT_RANDOM_ACCESS, sessionA.getState());
        assertEquals(2, sessionA.getCurrentDescriptor().getConfigEpoch());
        assertEquals(3, transportA.getEvents().size());
        assertEquals("B drained one descriptor and its join AU", 2,
                transportB.getEvents().size());

        assertEquals("new descriptor plus new epoch join", 2,
                drainUntilEmpty(fixture.hub, CLIENT_A));
        assertEquals(2, drainUntilEmpty(fixture.hub, CLIENT_B));
        assertEquals(5, transportA.getEvents().size());
        assertEquals(4, transportB.getEvents().size());
        assertDescriptorPrecedesEveryEpoch(transportA.getEvents());
        assertDescriptorPrecedesEveryEpoch(transportB.getEvents());

        List<FakeTransport.Event> eventsA = transportA.getEvents();
        assertEquals(2, eventsA.get(3).getDescriptor().getConfigEpoch());
        assertEquals(2, eventsA.get(4).getAccessUnit().getConfigEpoch());
        assertEquals(0L, eventsA.get(4).getAccessUnit().getSequence());

        oldEncoder.emit(1, false, new byte[]{0x55});
        EncodedAccessUnit staleAccessUnit =
                oldEncoder.getEmitted().get(oldEncoder.getEmitted().size() - 1);
        assertFalse("an AU from a retired encoder must not reach the new session",
                fixture.hub.drainOne(CLIENT_A));
        assertFalse(fixture.hub.drainOne(CLIENT_B));
        assertEquals(5, transportA.getEvents().size());
        assertEquals(QueueOfferResult.REJECTED_EPOCH, sessionA.offer(staleAccessUnit));

        FakeRenderTarget renderTargetA = new FakeRenderTarget();
        ClientMediaSession clientA = client(CLIENT_A, fixture.registry, ACTIVE_CODEC_ID,
                renderTargetA);
        feed(clientA, eventsA.subList(0, 3));
        assertEquals(ClientState.STREAMING, clientA.getState());

        assertTrue(clientA.onDescriptor(eventsA.get(3).getDescriptor()));
        assertEquals(ClientState.WAIT_RANDOM_ACCESS, clientA.getState());
        assertTrue(clientA.onAccessUnit(eventsA.get(4).getAccessUnit()));
        assertEquals(ClientState.STREAMING, clientA.getState());
        assertEquals(3, renderTargetA.getImages().size());
    }

    @Test
    public void fallbackUsesMetadataAndDoesNotAutoSwitch() throws Exception {
        MutableClock clock = new MutableClock();
        FakeCodec fake = new FakeCodec(ACTIVE_CODEC_ID, 0, false, false);
        CodecRegistry registry = new CodecRegistry();
        registry.register(fake.registration());
        registry.register(JpegCodec.registration());
        StreamRuntime runtime = new StreamRuntime(registry, STREAM_KEY,
                descriptor(1, 64, 48, 64, 48), StreamParameters.DEFAULT, clock, 0L);
        runtime.start();
        StreamHub hub = new StreamHub(registry,
                new StreamQueueLimits(128L, 8, MAX_AGE_MS), clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        FakeTransport transportA = new FakeTransport();
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID, CodecIds.JPEG), transportA)
                        .getStatus());
        assertEquals(2, drainUntilEmpty(hub, CLIENT_A));

        ClientSendSession sessionA = hub.getSession(CLIENT_A);
        ClientState stateBefore = sessionA.getState();
        StreamDescriptor activeBefore = runtime.getDescriptor();
        int eventsBefore = transportA.getEvents().size();

        JoinDecision fallback = hub.join(CLIENT_C, capabilities(CodecIds.JPEG),
                new FakeTransport());

        assertEquals(JoinDecision.Status.REQUIRES_EXPLICIT_FALLBACK, fallback.getStatus());
        assertEquals(CodecIds.JPEG, fallback.getFallbackCodecId());
        assertNull(fallback.getSession());
        assertEquals(1, hub.getSessionCount());
        assertNull(hub.getSession(CLIENT_C));
        assertSame(sessionA, hub.getSession(CLIENT_A));
        assertEquals(stateBefore, sessionA.getState());
        assertEquals(activeBefore.getConfigEpoch(), runtime.getDescriptor().getConfigEpoch());
        assertEquals(activeBefore.getConfigHash(), runtime.getDescriptor().getConfigHash());
        assertEquals("fallback must not recreate the active encoder",
                1, fake.getEncoderCreateCount());
        assertEquals(eventsBefore, transportA.getEvents().size());

        JoinDecision rejected = hub.join(CLIENT_C, capabilities("unsupported"),
                new FakeTransport());

        assertEquals(JoinDecision.Status.REJECTED, rejected.getStatus());
        assertNull(rejected.getSession());
        assertEquals(1, hub.getSessionCount());
        assertEquals(activeBefore.getConfigHash(), runtime.getDescriptor().getConfigHash());
        assertEquals(ClientState.STREAMING, sessionA.getState());
    }

    @Test
    public void duplicateEpochSwitch_isRejectedWithoutTouchingSessions() throws Exception {
        FakeFixture fixture = fakeFixture(128L, 8, 0L, true);
        FakeTransport transportA = new FakeTransport();

        assertEquals(JoinDecision.Status.ACCEPTED,
                fixture.hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), transportA).getStatus());
        assertEquals(2, drainUntilEmpty(fixture.hub, CLIENT_A));

        ClientSendSession sessionA = fixture.hub.getSession(CLIENT_A);
        StreamDescriptor descriptorBefore = fixture.runtime.getDescriptor();
        FakeCodec.FakeEncoder encoder = fixture.fake.getLastEncoder();
        int requestsBefore = fixture.fake.getRequestCount();
        int createsBefore = fixture.fake.getEncoderCreateCount();
        int eventsBefore = transportA.getEvents().size();

        StreamDescriptor duplicateEpoch = descriptor(1, 64, 48, 48, 36);
        assertFalse(descriptorBefore.sameDecodeConfig(duplicateEpoch));

        assertThrows(ConfigEpochException.class,
                () -> fixture.runtime.switchDescriptor(duplicateEpoch));

        assertSame(encoder, fixture.fake.getLastEncoder());
        assertEquals(createsBefore, fixture.fake.getEncoderCreateCount());
        assertEquals(requestsBefore, fixture.fake.getRequestCount());
        assertEquals(descriptorBefore.getConfigEpoch(),
                fixture.runtime.getDescriptor().getConfigEpoch());
        assertEquals(descriptorBefore.getConfigHash(),
                fixture.runtime.getDescriptor().getConfigHash());
        assertSame(sessionA, fixture.hub.getSession(CLIENT_A));
        assertEquals(ClientState.STREAMING, sessionA.getState());
        assertEquals(eventsBefore, transportA.getEvents().size());
        assertFalse(fixture.hub.drainOne(CLIENT_A));
    }

    @Test
    public void jpegEmptyConfig_worksEndToEnd() throws Exception {
        CapturingJpegEncoderFactory encoderFactory = new CapturingJpegEncoderFactory();
        CodecRegistration registration = new CodecRegistration(
                JpegCodec.registration().getDescriptor(), encoderFactory,
                new JpegDecoderFactory(), new JpegCodecPolicy(), 100);
        CodecRegistry registry = new CodecRegistry();
        registry.register(registration);

        StreamDescriptor descriptor = StreamDescriptor.builder(STREAM_KEY, 1, CodecIds.JPEG,
                        JpegCodec.MIME, BitstreamFormat.JPEG_FRAME)
                .codedSize(1, 1)
                .build();
        assertTrue("the empty JPEG configuration must carry no codec config parts",
                descriptor.getConfigParts().isEmpty());

        MutableClock clock = new MutableClock();
        StreamRuntime runtime = new StreamRuntime(registry, STREAM_KEY, descriptor,
                StreamParameters.DEFAULT, clock, 0L);
        runtime.start();
        StreamHub hub = new StreamHub(registry,
                new StreamQueueLimits(4_096L, 8, MAX_AGE_MS), clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        FakeTransport transport = new FakeTransport();
        JoinDecision decision = hub.join(CLIENT_A, capabilities(CodecIds.JPEG), transport);

        assertEquals(JoinDecision.Status.ACCEPTED, decision.getStatus());
        assertEquals("the hub must still request a join point for JPEG",
                1, encoderFactory.getRequestCount());

        JpegEncoder encoder = encoderFactory.getLastEncoder();
        assertNotNull(encoder);
        assertTrue(encoder.offerJpegFrame(0L, JpegFixtures.VALID_FRAME));
        assertEquals(2, drainUntilEmpty(hub, CLIENT_A));

        List<FakeTransport.Event> events = transport.getEvents();
        assertEquals(FakeTransport.Kind.DESCRIPTOR, events.get(0).getKind());
        assertEquals(FakeTransport.Kind.ACCESS_UNIT, events.get(1).getKind());

        StreamDescriptor sentDescriptor = events.get(0).getDescriptor();
        assertEquals(1, sentDescriptor.getConfigEpoch());
        assertTrue(sentDescriptor.getConfigParts().isEmpty());

        EncodedAccessUnit accessUnit = events.get(1).getAccessUnit();
        assertEquals(1, accessUnit.getConfigEpoch());
        assertTrue(accessUnit.getFlags().isJoinPoint());
        assertArrayEquals(JpegFixtures.VALID_FRAME, accessUnit.getPayload());

        FakeRenderTarget renderTarget = new FakeRenderTarget();
        ClientMediaSession client = client(CLIENT_A, registry, CodecIds.JPEG, renderTarget);

        assertTrue(client.onDescriptor(sentDescriptor));
        assertEquals(ClientState.WAIT_RANDOM_ACCESS, client.getState());
        assertTrue(client.onAccessUnit(accessUnit));
        assertEquals(ClientState.STREAMING, client.getState());
        assertEquals(1, renderTarget.getImages().size());

        EncodedImageFrame frame = renderTarget.getImages().get(0);
        assertArrayEquals(JpegFixtures.VALID_FRAME, frame.getPayload());
        assertEquals(1, frame.getWidth());
        assertEquals(1, frame.getHeight());
    }

    private static FakeFixture fakeFixture(long maxBytes, int maxUnits, long joinIntervalMs,
                                           boolean autoJoin) {
        MutableClock clock = new MutableClock();
        FakeCodec fake = new FakeCodec(ACTIVE_CODEC_ID, 0, false, false);
        fake.setAutoEmitJoinOnRequest(autoJoin);
        CodecRegistry registry = new CodecRegistry();
        registry.register(fake.registration());
        StreamRuntime runtime = new StreamRuntime(registry, STREAM_KEY,
                descriptor(1, 64, 48, 64, 48), StreamParameters.DEFAULT, clock, joinIntervalMs);
        runtime.start();
        StreamHub hub = new StreamHub(registry,
                new StreamQueueLimits(maxBytes, maxUnits, MAX_AGE_MS), clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);
        return new FakeFixture(clock, registry, fake, runtime, hub);
    }

    private static StreamDescriptor descriptor(int configEpoch, int codedWidth, int codedHeight,
                                               int visibleWidth, int visibleHeight) {
        return StreamDescriptor.builder(STREAM_KEY, configEpoch, ACTIVE_CODEC_ID,
                        FakeCodec.DEFAULT_MIME, BitstreamFormat.ANNEX_B)
                .codedSize(codedWidth, codedHeight)
                .visibleSize(visibleWidth, visibleHeight)
                .build();
    }

    private static ClientCapabilities capabilities(String... codecIds) {
        return new ClientCapabilities(new HashSet<>(Arrays.asList(codecIds)));
    }

    private static ClientMediaSession client(ClientSessionKey key, CodecRegistry registry,
                                             String codecId, FakeRenderTarget renderTarget) {
        return new ClientMediaSession(key, registry,
                new ClientCapabilities(Collections.singleton(codecId)), renderTarget, null);
    }

    private static int drainUntilEmpty(StreamHub hub, ClientSessionKey key) throws IOException {
        int drained = 0;
        while (hub.drainOne(key)) {
            drained++;
            assertTrue("drain did not terminate for " + key, drained < 100);
        }
        return drained;
    }

    private static void feed(ClientMediaSession client, List<FakeTransport.Event> events) {
        for (FakeTransport.Event event : events) {
            if (event.isDescriptor()) {
                client.onDescriptor(event.getDescriptor());
            } else {
                client.onAccessUnit(event.getAccessUnit());
            }
        }
    }

    private static List<Long> accessUnitSequences(List<FakeTransport.Event> events) {
        List<Long> sequences = new ArrayList<>();
        for (FakeTransport.Event event : events) {
            if (event.isAccessUnit()) {
                sequences.add(event.getAccessUnit().getSequence());
            }
        }
        return sequences;
    }

    private static void assertDescriptorPrecedesEveryEpoch(List<FakeTransport.Event> events) {
        Map<Integer, Boolean> descriptorSeen = new HashMap<>();
        Set<Integer> accessUnitEpochs = new HashSet<>();
        for (FakeTransport.Event event : events) {
            if (event.isDescriptor()) {
                descriptorSeen.put(event.getDescriptor().getConfigEpoch(), Boolean.TRUE);
            } else {
                int epoch = event.getAccessUnit().getConfigEpoch();
                accessUnitEpochs.add(epoch);
                assertEquals("descriptor for epoch " + epoch
                                + " must precede its first access unit",
                        Boolean.TRUE, descriptorSeen.get(epoch));
            }
        }
        for (Integer epoch : accessUnitEpochs) {
            assertTrue("epoch " + epoch + " must announce a descriptor",
                    descriptorSeen.containsKey(epoch));
        }
    }

    private static final class FakeFixture {

        final MutableClock clock;

        final CodecRegistry registry;

        final FakeCodec fake;

        final StreamRuntime runtime;

        final StreamHub hub;

        FakeFixture(MutableClock clock, CodecRegistry registry, FakeCodec fake,
                    StreamRuntime runtime, StreamHub hub) {
            this.clock = clock;
            this.registry = registry;
            this.fake = fake;
            this.runtime = runtime;
            this.hub = hub;
        }
    }
}
