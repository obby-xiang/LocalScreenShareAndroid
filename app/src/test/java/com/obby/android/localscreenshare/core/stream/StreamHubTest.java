package com.obby.android.localscreenshare.core.stream;

import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.model.ClientState;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.model.StreamParameters;
import com.obby.android.localscreenshare.core.support.BlockingTransport;
import com.obby.android.localscreenshare.core.support.FakeCodec;
import com.obby.android.localscreenshare.core.support.FakeTransport;
import com.obby.android.localscreenshare.core.support.MutableClock;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class StreamHubTest {

    private static final String SESSION_INSTANCE_ID = "session-1";

    private static final StreamKey STREAM_KEY = new StreamKey(SESSION_INSTANCE_ID, "stream-1");

    private static final StreamKey OTHER_STREAM_KEY =
            new StreamKey(SESSION_INSTANCE_ID, "stream-2");

    private static final ClientSessionKey CLIENT_A = new ClientSessionKey(STREAM_KEY, "client-A");

    private static final ClientSessionKey CLIENT_B = new ClientSessionKey(STREAM_KEY, "client-B");

    private static final ClientSessionKey EXISTING_CLIENT =
            new ClientSessionKey(STREAM_KEY, "client-existing");

    private static final ClientSessionKey CANDIDATE_CLIENT =
            new ClientSessionKey(STREAM_KEY, "client-candidate");

    private static final String ACTIVE_CODEC_ID = FakeCodec.DEFAULT_CODEC_ID;

    private static final long JOIN_INTERVAL_MS = 100L;

    private static final long MAX_AGE_MS = 5_000L;

    @Test
    public void clientCapabilities_defensivelyCopiesAndSupports() {
        Set<String> source = new HashSet<>(Arrays.asList("codec-a", "codec-b"));
        ClientCapabilities capabilities = new ClientCapabilities(source);
        source.add("codec-c");

        assertEquals(2, capabilities.getCodecIds().size());
        assertTrue(capabilities.supports("codec-a"));
        assertTrue(capabilities.supports("codec-b"));
        assertFalse(capabilities.supports("codec-c"));
        assertFalse(capabilities.supports("missing"));
        assertFalse(capabilities.supports(null));

        Set<String> returned = capabilities.getCodecIds();
        try {
            returned.add("codec-d");
        } catch (UnsupportedOperationException expected) {
            // An unmodifiable view is also acceptable.
        }
        assertFalse(capabilities.supports("codec-d"));
    }

    @Test
    public void register_rejectsDuplicateAndUnregisterIsIdempotent() {
        MutableClock clock = new MutableClock();
        FakeCodec fake = new FakeCodec(ACTIVE_CODEC_ID, 0, false, false);
        CodecRegistry registry = registry(fake);
        StreamRuntime runtime = startedRuntime(registry, STREAM_KEY, ACTIVE_CODEC_ID, clock);
        StreamHub hub = newHub(registry, clock);

        hub.register(runtime);
        assertThrows(RuntimeException.class, () -> hub.register(runtime));

        hub.unregister(STREAM_KEY);
        hub.unregister(STREAM_KEY);
        hub.unregister(OTHER_STREAM_KEY);
        assertNull(hub.getActiveStream());

        hub.register(runtime);
        assertThrows(RuntimeException.class, () -> hub.register(runtime));
    }

    @Test
    public void unregister_removesRuntimeListener() throws Exception {
        MutableClock clock = new MutableClock();
        FakeCodec fake = new FakeCodec(ACTIVE_CODEC_ID, 0, false, false);
        fake.setAutoEmitJoinOnRequest(false);
        CodecRegistry registry = registry(fake);
        StreamRuntime runtime = startedRuntime(registry, STREAM_KEY, ACTIVE_CODEC_ID, clock);
        StreamHub hub = newHub(registry, clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        FakeTransport firstTransport = new FakeTransport();
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), firstTransport).getStatus());
        fake.emitJoin(new byte[]{0x21});
        drainToStreaming(hub, CLIENT_A, firstTransport);

        hub.unregister(STREAM_KEY);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        FakeTransport secondTransport = new FakeTransport();
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_B, capabilities(ACTIVE_CODEC_ID), secondTransport).getStatus());
        fake.emitJoin(new byte[]{0x22});
        drainToStreaming(hub, CLIENT_B, secondTransport);

        assertFalse("a removed listener must not broadcast twice after re-register",
                hub.drainOne(CLIENT_B));
        assertEquals(2, secondTransport.getEvents().size());
    }

    @Test
    public void unregister_active_detachesSessionsAndClearsActive() {
        MutableClock clock = new MutableClock();
        FakeCodec fake = new FakeCodec(ACTIVE_CODEC_ID, 0, false, false);
        CodecRegistry registry = registry(fake);
        StreamRuntime runtime = startedRuntime(registry, STREAM_KEY, ACTIVE_CODEC_ID, clock);
        StreamHub hub = newHub(registry, clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        FakeTransport transport = new FakeTransport();
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), transport).getStatus());
        ClientSendSession session = hub.getSession(CLIENT_A);
        assertNotNull(session);

        hub.unregister(STREAM_KEY);

        assertEquals(0, hub.getSessionCount());
        assertNull(hub.getActiveStream());
        assertNull(hub.getSession(CLIENT_A));
        assertTrue(session.isDetached());
        assertEquals(1, transport.getCloseCount());

        hub.unregister(STREAM_KEY);
        assertEquals(0, hub.getSessionCount());
    }

    @Test
    public void activate_requiresRegisteredStream() {
        MutableClock clock = new MutableClock();
        FakeCodec fake = new FakeCodec(ACTIVE_CODEC_ID, 0, false, false);
        CodecRegistry registry = registry(fake);
        StreamRuntime runtime = startedRuntime(registry, STREAM_KEY, ACTIVE_CODEC_ID, clock);
        StreamHub hub = newHub(registry, clock);

        assertThrows(RuntimeException.class, () -> hub.activate(STREAM_KEY));
        assertNull(hub.getActiveStream());

        hub.register(runtime);
        hub.activate(STREAM_KEY);

        assertNotNull(hub.getActiveStream());
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), new FakeTransport())
                        .getStatus());
    }

    @Test
    public void activate_differentStreamWithSessionsRejected() {
        MutableClock clock = new MutableClock();
        FakeCodec fake = new FakeCodec(ACTIVE_CODEC_ID, 0, false, false);
        CodecRegistry registry = registry(fake);
        StreamRuntime first = startedRuntime(registry, STREAM_KEY, ACTIVE_CODEC_ID, clock);
        StreamRuntime second = startedRuntime(registry, OTHER_STREAM_KEY, ACTIVE_CODEC_ID, clock);
        StreamHub hub = newHub(registry, clock);
        hub.register(first);
        hub.register(second);
        hub.activate(STREAM_KEY);

        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), new FakeTransport())
                        .getStatus());

        assertThrows(RuntimeException.class, () -> hub.activate(OTHER_STREAM_KEY));
        assertEquals(1, hub.getSessionCount());
        assertNotNull(hub.getActiveStream());

        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_B, capabilities(ACTIVE_CODEC_ID), new FakeTransport())
                        .getStatus());
        ClientSessionKey otherStreamClient =
                new ClientSessionKey(OTHER_STREAM_KEY, "client-other");
        assertEquals(JoinDecision.Status.REJECTED,
                hub.join(otherStreamClient, capabilities(ACTIVE_CODEC_ID), new FakeTransport())
                        .getStatus());
        assertEquals(2, hub.getSessionCount());
    }

    @Test
    public void join_withoutActiveStreamRejected() {
        MutableClock clock = new MutableClock();
        FakeCodec fake = new FakeCodec(ACTIVE_CODEC_ID, 0, false, false);
        CodecRegistry registry = registry(fake);
        StreamRuntime runtime = startedRuntime(registry, STREAM_KEY, ACTIVE_CODEC_ID, clock);
        StreamHub hub = newHub(registry, clock);
        hub.register(runtime);

        FakeTransport transport = new FakeTransport();
        JoinDecision decision = hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), transport);

        assertEquals(JoinDecision.Status.REJECTED, decision.getStatus());
        assertNull(decision.getSession());
        assertEquals(0, hub.getSessionCount());
        assertNull(hub.getSession(CLIENT_A));
        assertTrue(transport.getEvents().isEmpty());
    }

    @Test
    public void join_triggersRuntimeRequestJoinPoint() throws Exception {
        MutableClock clock = new MutableClock();
        FakeCodec fake = new FakeCodec(ACTIVE_CODEC_ID, 0, false, false);
        CodecRegistry registry = registry(fake);
        StreamRuntime runtime = startedRuntime(registry, STREAM_KEY, ACTIVE_CODEC_ID, clock);
        StreamHub hub = newHub(registry, clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        FakeTransport transport = new FakeTransport();
        JoinDecision decision = hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), transport);

        assertEquals(JoinDecision.Status.ACCEPTED, decision.getStatus());
        assertEquals("the hub must request a join point from the runtime",
                1, fake.getRequestCount());

        drainToStreaming(hub, CLIENT_A, transport);
        EncodedAccessUnit join = lastEmitted(fake);
        FakeTransport.Event last = lastEvent(transport);
        assertTrue(last.isAccessUnit());
        assertEquals(join.getConfigEpoch(), last.getAccessUnit().getConfigEpoch());
        assertEquals(join.getSequence(), last.getAccessUnit().getSequence());
        assertTrue(last.getAccessUnit().getFlags().isJoinPoint());
    }

    @Test
    public void multipleJoins_coalesceJoinPointRequest() throws Exception {
        MutableClock clock = new MutableClock();
        FakeCodec fake = new FakeCodec(ACTIVE_CODEC_ID, 0, false, false);
        fake.setAutoEmitJoinOnRequest(false);
        CodecRegistry registry = registry(fake);
        StreamRuntime runtime = startedRuntime(registry, STREAM_KEY, ACTIVE_CODEC_ID, clock);
        StreamHub hub = newHub(registry, clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        FakeTransport transportA = new FakeTransport();
        FakeTransport transportB = new FakeTransport();
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), transportA).getStatus());
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_B, capabilities(ACTIVE_CODEC_ID), transportB).getStatus());

        assertEquals("concurrent joins must coalesce into one runtime request",
                1, fake.getRequestCount());

        fake.emitJoin(new byte[]{0x0A});
        EncodedAccessUnit join = lastEmitted(fake);

        drainToStreaming(hub, CLIENT_A, transportA);
        drainToStreaming(hub, CLIENT_B, transportB);

        assertEquals(ClientState.STREAMING, hub.getSession(CLIENT_A).getState());
        assertEquals(ClientState.STREAMING, hub.getSession(CLIENT_B).getState());
        assertJoinEvent(transportA.getEvents().get(1), join);
        assertJoinEvent(transportB.getEvents().get(1), join);
    }

    @Test
    public void join_descriptorBeforeFirstAu() throws Exception {
        MutableClock clock = new MutableClock();
        FakeCodec fake = new FakeCodec(ACTIVE_CODEC_ID, 0, false, false);
        fake.setAutoEmitJoinOnRequest(false);
        CodecRegistry registry = registry(fake);
        StreamRuntime runtime = startedRuntime(registry, STREAM_KEY, ACTIVE_CODEC_ID, clock);
        StreamHub hub = newHub(registry, clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        FakeTransport transport = new FakeTransport();
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), transport).getStatus());

        assertTrue(hub.drainOne(CLIENT_A));
        assertEquals(1, transport.getEvents().size());
        assertTrue(transport.getEvents().get(0).isDescriptor());
        assertEquals(runtime.getDescriptor().getConfigHash(),
                transport.getEvents().get(0).getDescriptor().getConfigHash());

        fake.emitJoin(new byte[]{0x0B});
        assertTrue(hub.drainOne(CLIENT_A));

        assertEquals(2, transport.getEvents().size());
        assertTrue(transport.getEvents().get(1).isAccessUnit());
        assertEquals(ClientState.STREAMING, hub.getSession(CLIENT_A).getState());
    }

    @Test
    public void join_duplicateSessionKey_takesOver() {
        MutableClock clock = new MutableClock();
        FakeCodec fake = new FakeCodec(ACTIVE_CODEC_ID, 0, false, false);
        fake.setAutoEmitJoinOnRequest(false);
        CodecRegistry registry = registry(fake);
        StreamRuntime runtime = startedRuntime(registry, STREAM_KEY, ACTIVE_CODEC_ID, clock);
        StreamHub hub = newHub(registry, clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        FakeTransport firstTransport = new FakeTransport();
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), firstTransport).getStatus());
        ClientSendSession firstSession = hub.getSession(CLIENT_A);

        FakeTransport secondTransport = new FakeTransport();
        JoinDecision decision =
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), secondTransport);

        // A duplicate key takes over the stale session instead of being rejected.
        assertEquals(JoinDecision.Status.ACCEPTED, decision.getStatus());
        assertNotNull(decision.getSession());
        assertEquals(1, hub.getSessionCount());
        assertNotSame(firstSession, hub.getSession(CLIENT_A));
        assertSame(decision.getSession(), hub.getSession(CLIENT_A));
        assertTrue(firstSession.isDetached());
        // The replaced transport is terminated with an explicit reason, not just released, so the
        // old client does not wait for its own deadline.
        assertEquals(1, firstTransport.getAbortCount());
        assertEquals(0, firstTransport.getCloseCount());
        assertEquals(StreamHub.SESSION_TAKEOVER_REASON, firstTransport.getAbortReason());
        assertEquals(0, secondTransport.getAbortCount());
        assertEquals(0, secondTransport.getCloseCount());
    }

    @Test
    public void join_duplicateSessionKey_leavesOtherSessionsAndTheNewSessionUsable()
            throws Exception {
        MutableClock clock = new MutableClock();
        FakeCodec fake = new FakeCodec(ACTIVE_CODEC_ID, 0, false, false);
        fake.setAutoEmitJoinOnRequest(false);
        CodecRegistry registry = registry(fake);
        StreamRuntime runtime = startedRuntime(registry, STREAM_KEY, ACTIVE_CODEC_ID, clock);
        StreamHub hub = newHub(registry, clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        FakeTransport firstTransport = new FakeTransport();
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), firstTransport).getStatus());
        FakeTransport otherTransport = new FakeTransport();
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_B, capabilities(ACTIVE_CODEC_ID), otherTransport).getStatus());

        FakeTransport secondTransport = new FakeTransport();
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), secondTransport).getStatus());

        // Only the replaced transport is terminated; another client's stream is untouched.
        assertEquals(1, firstTransport.getAbortCount());
        assertEquals(0, otherTransport.getAbortCount());
        assertEquals(0, otherTransport.getCloseCount());
        assertNotNull(hub.getSession(CLIENT_B));
        assertEquals(2, hub.getSessionCount());

        // Both the replacement and the unaffected client can still be drained.
        assertTrue(hub.drainOne(CLIENT_A));
        assertTrue(secondTransport.getEvents().get(0).isDescriptor());
        assertTrue(hub.drainOne(CLIENT_B));
        assertTrue(otherTransport.getEvents().get(0).isDescriptor());
        assertTrue(firstTransport.getEvents().isEmpty());
    }

    @Test
    public void detachIfCurrent_doesNotDetachAReplacingSession() {
        MutableClock clock = new MutableClock();
        FakeCodec fake = new FakeCodec(ACTIVE_CODEC_ID, 0, false, false);
        fake.setAutoEmitJoinOnRequest(false);
        CodecRegistry registry = registry(fake);
        StreamRuntime runtime = startedRuntime(registry, STREAM_KEY, ACTIVE_CODEC_ID, clock);
        StreamHub hub = newHub(registry, clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        FakeTransport firstTransport = new FakeTransport();
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), firstTransport).getStatus());
        ClientSendSession firstSession = hub.getSession(CLIENT_A);

        FakeTransport secondTransport = new FakeTransport();
        JoinDecision decision =
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), secondTransport);
        assertEquals(JoinDecision.Status.ACCEPTED, decision.getStatus());

        // An old handler ending after the takeover must not detach its replacement.
        hub.detachIfCurrent(CLIENT_A, firstSession);
        hub.detachIfCurrent(CLIENT_A, null);
        hub.detachIfCurrent(null, decision.getSession());

        assertSame(decision.getSession(), hub.getSession(CLIENT_A));
        assertEquals(1, hub.getSessionCount());
        assertFalse(decision.getSession().isDetached());
        assertEquals(0, secondTransport.getAbortCount());
        assertEquals(0, secondTransport.getCloseCount());

        // The current session can still be detached through the same entry point.
        hub.detachIfCurrent(CLIENT_A, decision.getSession());
        assertNull(hub.getSession(CLIENT_A));
        assertTrue(decision.getSession().isDetached());
        assertEquals(1, secondTransport.getCloseCount());
    }

    @Test
    public void join_duplicateKeyWithoutCommonCodec_keepsTheAttachedSession() {
        MutableClock clock = new MutableClock();
        FakeCodec fake = new FakeCodec(ACTIVE_CODEC_ID, 0, false, false);
        CodecRegistry registry = registry(fake);
        StreamRuntime runtime = startedRuntime(registry, STREAM_KEY, ACTIVE_CODEC_ID, clock);
        StreamHub hub = newHub(registry, clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        FakeTransport attachedTransport = new FakeTransport();
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), attachedTransport).getStatus());
        ClientSendSession attached = hub.getSession(CLIENT_A);

        FakeTransport rejectedTransport = new FakeTransport();
        JoinDecision decision =
                hub.join(CLIENT_A, capabilities("unsupported-codec"), rejectedTransport);

        // A refused join is not a takeover: the attached session keeps the stream.
        assertEquals(JoinDecision.Status.REJECTED, decision.getStatus());
        assertSame(attached, hub.getSession(CLIENT_A));
        assertFalse(attached.isDetached());
        assertEquals(1, hub.getSessionCount());
        assertEquals(0, attachedTransport.getAbortCount());
        assertEquals(0, attachedTransport.getCloseCount());
        assertEquals(0, rejectedTransport.getAbortCount());
        assertEquals(0, rejectedTransport.getCloseCount());
    }

    @Test
    public void join_streamKeyMismatchRejected() {
        MutableClock clock = new MutableClock();
        FakeCodec fake = new FakeCodec(ACTIVE_CODEC_ID, 0, false, false);
        CodecRegistry registry = registry(fake);
        StreamRuntime runtime = startedRuntime(registry, STREAM_KEY, ACTIVE_CODEC_ID, clock);
        StreamHub hub = newHub(registry, clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        ClientSessionKey mismatchedKey =
                new ClientSessionKey(OTHER_STREAM_KEY, "client-other");
        JoinDecision decision =
                hub.join(mismatchedKey, capabilities(ACTIVE_CODEC_ID), new FakeTransport());

        assertEquals(JoinDecision.Status.REJECTED, decision.getStatus());
        assertNull(decision.getSession());
        assertEquals(0, hub.getSessionCount());
        assertNull(hub.getSession(mismatchedKey));
    }

    @Test
    public void join_compatibleClientAttachesSession() {
        MutableClock clock = new MutableClock();
        FakeCodec fake = new FakeCodec(ACTIVE_CODEC_ID, 0, false, false);
        CodecRegistry registry = registry(fake);
        StreamRuntime runtime = startedRuntime(registry, STREAM_KEY, ACTIVE_CODEC_ID, clock);
        StreamHub hub = newHub(registry, clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        JoinDecision decision =
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), new FakeTransport());

        assertEquals(JoinDecision.Status.ACCEPTED, decision.getStatus());
        assertNotNull(decision.getSession());
        assertSame(decision.getSession(), hub.getSession(CLIENT_A));
        assertEquals(CLIENT_A, decision.getSession().getKey());
        assertEquals(1, hub.getSessionCount());
    }

    @Test
    public void join_unsupportedClient_rejectedWithoutChangingExisting() {
        MutableClock clock = new MutableClock();
        FakeCodec fake = new FakeCodec(ACTIVE_CODEC_ID, 0, false, false);
        fake.setAutoEmitJoinOnRequest(false);
        CodecRegistry registry = registry(fake);
        StreamRuntime runtime = startedRuntime(registry, STREAM_KEY, ACTIVE_CODEC_ID, clock);
        StreamHub hub = newHub(registry, clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        FakeTransport existingTransport = new FakeTransport();
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(EXISTING_CLIENT, capabilities(ACTIVE_CODEC_ID), existingTransport)
                        .getStatus());
        ClientSendSession existingSession = hub.getSession(EXISTING_CLIENT);
        int existingEvents = existingTransport.getEvents().size();

        FakeTransport candidateTransport = new FakeTransport();
        JoinDecision decision =
                hub.join(CANDIDATE_CLIENT, capabilities("unsupported"), candidateTransport);

        assertEquals(JoinDecision.Status.REJECTED, decision.getStatus());
        assertNull(decision.getSession());
        assertEquals(1, hub.getSessionCount());
        assertSame(existingSession, hub.getSession(EXISTING_CLIENT));
        assertNull(hub.getSession(CANDIDATE_CLIENT));
        assertEquals(existingEvents, existingTransport.getEvents().size());
        assertTrue(candidateTransport.getEvents().isEmpty());
    }

    @Test
    public void join_fallbackUsesRegistrationPriorityNotCodecName() {
        FallbackFixture fixture = fallbackFixture();

        JoinDecision decision = fixture.hub.join(CANDIDATE_CLIENT,
                capabilities("fallbackA", "fallbackB"), new FakeTransport());

        assertEquals(JoinDecision.Status.REQUIRES_EXPLICIT_FALLBACK, decision.getStatus());
        assertEquals("fallbackB", decision.getFallbackCodecId());
        assertNull(decision.getSession());
        assertEquals(1, fixture.hub.getSessionCount());
        assertEquals(1, fixture.activeCodec.getEncoderCreateCount());
        assertEquals(1, fixture.runtime.getDescriptor().getConfigEpoch());
    }

    @Test
    public void join_fallbackPriorityTieBreaksByCodecId() {
        MutableClock clock = new MutableClock();
        CodecRegistry registry = new CodecRegistry();
        register(registry, ACTIVE_CODEC_ID, 0);
        register(registry, "codec-z", 10);
        register(registry, "codec-a", 10);
        StreamRuntime runtime = startedRuntime(registry, STREAM_KEY, ACTIVE_CODEC_ID, clock);
        StreamHub hub = newHub(registry, clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(EXISTING_CLIENT,
                        capabilities(ACTIVE_CODEC_ID, "codec-z", "codec-a"),
                        new FakeTransport()).getStatus());

        JoinDecision decision = hub.join(CANDIDATE_CLIENT,
                capabilities("codec-z", "codec-a"), new FakeTransport());

        assertEquals(JoinDecision.Status.REQUIRES_EXPLICIT_FALLBACK, decision.getStatus());
        assertEquals("codec-a", decision.getFallbackCodecId());
    }

    @Test
    public void join_fallbackDoesNotAutoSwitch() {
        FallbackFixture fixture = fallbackFixture();
        ClientSendSession existingSession = fixture.hub.getSession(EXISTING_CLIENT);
        StreamDescriptor before = fixture.runtime.getDescriptor();

        JoinDecision decision = fixture.hub.join(CANDIDATE_CLIENT,
                capabilities("fallbackA", "fallbackB"), new FakeTransport());

        assertEquals(JoinDecision.Status.REQUIRES_EXPLICIT_FALLBACK, decision.getStatus());
        assertSame(existingSession, fixture.hub.getSession(EXISTING_CLIENT));
        assertEquals(before.getConfigEpoch(), fixture.runtime.getDescriptor().getConfigEpoch());
        assertEquals(before.getConfigHash(),
                fixture.runtime.getDescriptor().getConfigHash());
        assertEquals(1, fixture.activeCodec.getEncoderCreateCount());
        assertEquals(1, fixture.hub.getSessionCount());
    }

    @Test
    public void broadcast_isolatesSlowClientOverflow() throws Exception {
        MutableClock clock = new MutableClock();
        FakeCodec fake = new FakeCodec(ACTIVE_CODEC_ID, 0, false, false);
        fake.setAutoEmitJoinOnRequest(false);
        CodecRegistry registry = registry(fake);
        StreamRuntime runtime = startedRuntime(registry, STREAM_KEY, ACTIVE_CODEC_ID, clock);
        StreamHub hub = new StreamHub(registry, new StreamQueueLimits(128L, 2, MAX_AGE_MS),
                clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        FakeTransport transportA = new FakeTransport();
        FakeTransport transportB = new FakeTransport();
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), transportA).getStatus());
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_B, capabilities(ACTIVE_CODEC_ID), transportB).getStatus());
        assertEquals(1, fake.getRequestCount());

        fake.emitJoin(new byte[]{0x01});

        // Both clients must drain the initial descriptor and join before A stops.
        drainToStreaming(hub, CLIENT_A, transportA);
        drainToStreaming(hub, CLIENT_B, transportB);
        assertEquals(ClientState.STREAMING, hub.getSession(CLIENT_A).getState());
        assertEquals(ClientState.STREAMING, hub.getSession(CLIENT_B).getState());

        for (int i = 1; i <= 5; i++) {
            fake.emitNonJoin(new byte[]{(byte) i});
            assertTrue(hub.drainOne(CLIENT_B));
        }

        ClientSendSession sessionA = hub.getSession(CLIENT_A);
        ClientSendSession sessionB = hub.getSession(CLIENT_B);
        assertEquals(ClientState.RESYNCING, sessionA.getState());
        assertEquals(1L, sessionA.getStats().getResyncCount());
        assertEquals(ClientState.STREAMING, sessionB.getState());
        assertEquals("A stopped draining and must not receive the burst",
                2, transportA.getEvents().size());
        assertEquals(7, transportB.getEvents().size());
        for (int i = 0; i < 5; i++) {
            FakeTransport.Event event = transportB.getEvents().get(2 + i);
            assertTrue(event.isAccessUnit());
            assertEquals(i + 1L, event.getAccessUnit().getSequence());
        }

        clock.advance(JOIN_INTERVAL_MS + 1L);
        ClientSessionKey clientC = new ClientSessionKey(STREAM_KEY, "client-C");
        FakeTransport transportC = new FakeTransport();
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(clientC, capabilities(ACTIVE_CODEC_ID), transportC).getStatus());
        assertEquals(2, fake.getRequestCount());

        fake.emitJoin(new byte[]{0x7F});
        assertTrue(hub.drainOne(CLIENT_A));

        assertEquals(ClientState.STREAMING, sessionA.getState());
        assertTrue(lastEvent(transportA).isAccessUnit());
        assertTrue(lastEvent(transportA).getAccessUnit().getFlags().isJoinPoint());
        assertEquals(ClientState.STREAMING, sessionB.getState());
    }

    @Test
    public void descriptorChange_notifiesAllSessionsAndKeepsObjects() throws Exception {
        MutableClock clock = new MutableClock();
        FakeCodec fake = new FakeCodec(ACTIVE_CODEC_ID, 0, false, false);
        fake.setAutoEmitJoinOnRequest(false);
        CodecRegistry registry = registry(fake);
        StreamRuntime runtime = startedRuntime(registry, STREAM_KEY, ACTIVE_CODEC_ID, clock);
        StreamHub hub = newHub(registry, clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        FakeTransport transportA = new FakeTransport();
        FakeTransport transportB = new FakeTransport();
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), transportA).getStatus());
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_B, capabilities(ACTIVE_CODEC_ID), transportB).getStatus());
        fake.emitJoin(new byte[]{0x11});
        drainToStreaming(hub, CLIENT_A, transportA);
        drainToStreaming(hub, CLIENT_B, transportB);

        ClientSendSession sessionA = hub.getSession(CLIENT_A);
        ClientSendSession sessionB = hub.getSession(CLIENT_B);
        StreamDescriptor next = descriptor(STREAM_KEY, 2, ACTIVE_CODEC_ID, 64, 48, 48, 36);

        runtime.switchDescriptor(next);

        assertEquals(next.getConfigHash(), sessionA.getCurrentDescriptor().getConfigHash());
        assertEquals(next.getConfigHash(), sessionB.getCurrentDescriptor().getConfigHash());
        assertEquals(2, sessionA.getCurrentDescriptor().getConfigEpoch());
        assertEquals(2, sessionB.getCurrentDescriptor().getConfigEpoch());
        assertSame(sessionA, hub.getSession(CLIENT_A));
        assertSame(sessionB, hub.getSession(CLIENT_B));
        assertEquals(2, transportA.getEvents().size());
        assertEquals(2, transportB.getEvents().size());

        assertTrue(hub.drainOne(CLIENT_A));
        assertTrue(hub.drainOne(CLIENT_B));
        assertEquals(FakeTransport.Kind.DESCRIPTOR, transportA.getEvents().get(2).getKind());
        assertEquals(FakeTransport.Kind.DESCRIPTOR, transportB.getEvents().get(2).getKind());
        assertEquals(next.getConfigHash(),
                transportA.getEvents().get(2).getDescriptor().getConfigHash());
        assertEquals(next.getConfigHash(),
                transportB.getEvents().get(2).getDescriptor().getConfigHash());
    }

    @Test
    public void descriptorChange_fromNonActiveRuntimeIsIgnored() throws Exception {
        MutableClock clock = new MutableClock();
        FakeCodec fake = new FakeCodec(ACTIVE_CODEC_ID, 0, false, false);
        fake.setAutoEmitJoinOnRequest(false);
        CodecRegistry registry = registry(fake);
        StreamRuntime active = startedRuntime(registry, STREAM_KEY, ACTIVE_CODEC_ID, clock);
        StreamRuntime inactive =
                startedRuntime(registry, OTHER_STREAM_KEY, ACTIVE_CODEC_ID, clock);
        StreamHub hub = newHub(registry, clock);
        hub.register(active);
        hub.register(inactive);
        hub.activate(STREAM_KEY);

        FakeTransport transport = new FakeTransport();
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), transport).getStatus());
        assertTrue(hub.drainOne(CLIENT_A));

        ClientSendSession session = hub.getSession(CLIENT_A);
        StreamDescriptor before = session.getCurrentDescriptor();
        ClientState stateBefore = session.getState();
        int eventsBefore = transport.getEvents().size();

        inactive.switchDescriptor(
                descriptor(OTHER_STREAM_KEY, 2, ACTIVE_CODEC_ID, 64, 48, 48, 36));

        assertEquals(before.getConfigHash(), session.getCurrentDescriptor().getConfigHash());
        assertEquals(stateBefore, session.getState());
        assertEquals(eventsBefore, transport.getEvents().size());
        assertFalse(hub.drainOne(CLIENT_A));
        assertEquals(1, active.getDescriptor().getConfigEpoch());
    }

    @Test
    public void drainFailure_removesSession() throws Exception {
        MutableClock clock = new MutableClock();
        FakeCodec fake = new FakeCodec(ACTIVE_CODEC_ID, 0, false, false);
        fake.setAutoEmitJoinOnRequest(false);
        CodecRegistry registry = registry(fake);
        StreamRuntime runtime = startedRuntime(registry, STREAM_KEY, ACTIVE_CODEC_ID, clock);
        StreamHub hub = newHub(registry, clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        FakeTransport transport = new FakeTransport();
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), transport).getStatus());
        ClientSendSession session = hub.getSession(CLIENT_A);
        transport.failNextSend();

        assertThrows(IOException.class, () -> hub.drainOne(CLIENT_A));

        assertEquals(0, hub.getSessionCount());
        assertNull(hub.getSession(CLIENT_A));
        assertTrue(session.isDetached());
        assertEquals(ClientState.CLOSED, session.getState());
        assertEquals(1, transport.getCloseCount());
    }

    @Test
    public void drainUncheckedDescriptorFailure_removesSessionAndCapabilities()
            throws Exception {
        MutableClock clock = new MutableClock();
        CodecRegistry registry = new CodecRegistry();
        register(registry, ACTIVE_CODEC_ID, 0);
        register(registry, "fallbackA", 20);
        register(registry, "fallbackB", 10);
        StreamRuntime runtime = startedRuntime(registry, STREAM_KEY, ACTIVE_CODEC_ID, clock);
        StreamHub hub = newHub(registry, clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        FakeTransport transport = new FakeTransport();
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID, "fallbackA"), transport)
                        .getStatus());
        ClientSendSession session = hub.getSession(CLIENT_A);
        assertNotNull(session);

        transport.failNextDescriptorSendUnchecked();
        assertThrows(IllegalStateException.class, () -> hub.drainOne(CLIENT_A));

        assertEquals(0, hub.getSessionCount());
        assertNull(hub.getSession(CLIENT_A));
        assertTrue(session.isDetached());
        assertEquals(ClientState.CLOSED, session.getState());
        assertEquals(1, transport.getCloseCount());
        assertTrue(transport.getEvents().isEmpty());
        assertFalse(hub.drainOne(CLIENT_A));

        JoinDecision fallback = hub.join(CANDIDATE_CLIENT,
                capabilities("fallbackA", "fallbackB"), new FakeTransport());
        assertEquals(JoinDecision.Status.REQUIRES_EXPLICIT_FALLBACK, fallback.getStatus());
        assertEquals("fallbackB", fallback.getFallbackCodecId());
    }

    @Test
    public void drainUncheckedAccessUnitFailure_removesSessionAndCapabilities() throws Exception {
        MutableClock clock = new MutableClock();
        CodecRegistry registry = new CodecRegistry();
        FakeCodec fake = register(registry, ACTIVE_CODEC_ID, 0);
        register(registry, "fallbackA", 20);
        register(registry, "fallbackB", 10);
        fake.setAutoEmitJoinOnRequest(false);
        StreamRuntime runtime = startedRuntime(registry, STREAM_KEY, ACTIVE_CODEC_ID, clock);
        StreamHub hub = newHub(registry, clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        FakeTransport transport = new FakeTransport();
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID, "fallbackA"), transport)
                        .getStatus());
        ClientSendSession session = hub.getSession(CLIENT_A);
        fake.emitJoin(new byte[]{0x31});
        drainToStreaming(hub, CLIENT_A, transport);
        assertEquals(2, transport.getEvents().size());

        fake.emitNonJoin(new byte[]{0x41});
        fake.emitNonJoin(new byte[]{0x42});
        transport.failNextAccessUnitSendUnchecked();
        assertThrows(IllegalStateException.class, () -> hub.drainOne(CLIENT_A));

        assertEquals(0, hub.getSessionCount());
        assertNull(hub.getSession(CLIENT_A));
        assertTrue(session.isDetached());
        assertEquals(ClientState.CLOSED, session.getState());
        assertEquals(1, transport.getCloseCount());
        assertEquals(2, transport.getEvents().size());
        assertFalse(hub.drainOne(CLIENT_A));
        assertEquals(2, transport.getEvents().size());

        JoinDecision fallback = hub.join(CANDIDATE_CLIENT,
                capabilities("fallbackA", "fallbackB"), new FakeTransport());
        assertEquals(JoinDecision.Status.REQUIRES_EXPLICIT_FALLBACK, fallback.getStatus());
        assertEquals("fallbackB", fallback.getFallbackCodecId());
    }

    @Test
    public void detach_isIdempotentAndAllowsRejoinWithNewTransport() throws Exception {
        MutableClock clock = new MutableClock();
        FakeCodec fake = new FakeCodec(ACTIVE_CODEC_ID, 0, false, false);
        fake.setAutoEmitJoinOnRequest(false);
        CodecRegistry registry = registry(fake);
        StreamRuntime runtime = startedRuntime(registry, STREAM_KEY, ACTIVE_CODEC_ID, clock);
        StreamHub hub = newHub(registry, clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        FakeTransport firstTransport = new FakeTransport();
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), firstTransport).getStatus());
        ClientSendSession firstSession = hub.getSession(CLIENT_A);

        hub.detach(CLIENT_A);
        hub.detach(CLIENT_A);

        assertEquals(0, hub.getSessionCount());
        assertNull(hub.getSession(CLIENT_A));
        assertTrue(firstSession.isDetached());
        assertEquals(1, firstTransport.getCloseCount());

        FakeTransport secondTransport = new FakeTransport();
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), secondTransport).getStatus());

        ClientSendSession secondSession = hub.getSession(CLIENT_A);
        assertNotNull(secondSession);
        assertNotSame(firstSession, secondSession);
        assertEquals(1, hub.getSessionCount());

        assertTrue(hub.drainOne(CLIENT_A));
        assertEquals(FakeTransport.Kind.DESCRIPTOR, secondTransport.getEvents().get(0).getKind());
        assertTrue(firstTransport.getEvents().isEmpty());
    }

    @Test
    public void concurrentAttachDetach_doesNotThrowOrLoseSessions() throws Exception {
        MutableClock clock = new MutableClock();
        FakeCodec fake = new FakeCodec(ACTIVE_CODEC_ID, 0, false, false);
        fake.setAutoEmitJoinOnRequest(false);
        CodecRegistry registry = registry(fake);
        StreamRuntime runtime = startedRuntime(registry, STREAM_KEY, ACTIVE_CODEC_ID, clock);
        final StreamHub hub = newHub(registry, clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        int threadCount = 4;
        int iterationsPerThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        final Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        final ClientCapabilities capabilities = capabilities(ACTIVE_CODEC_ID);
        try {
            for (int threadIndex = 0; threadIndex < threadCount; threadIndex++) {
                final int index = threadIndex;
                pool.execute(() -> {
                    try {
                        start.await();
                        ClientSessionKey key =
                                new ClientSessionKey(STREAM_KEY, "client-" + index);
                        for (int i = 0; i < iterationsPerThread; i++) {
                            JoinDecision decision = hub.join(key, capabilities,
                                    new FakeTransport());
                            if (decision.getStatus() != JoinDecision.Status.ACCEPTED) {
                                throw new AssertionError(
                                        "join rejected at iteration " + i + ": "
                                                + decision.getStatus());
                            }
                            if (hub.getSession(key) == null) {
                                throw new AssertionError(
                                        "session missing after join at iteration " + i);
                            }
                            hub.detach(key);
                            if (hub.getSession(key) != null) {
                                throw new AssertionError(
                                        "session still present after detach at iteration " + i);
                            }
                        }
                    } catch (Throwable t) {
                        failures.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue("concurrent attach/detach did not finish",
                    done.await(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertTrue("concurrent attach/detach failed: " + failures,
                failures.isEmpty());
        assertEquals(0, hub.getSessionCount());
    }

    @Test
    public void drainFailure_afterReconnect_keepsNewSessionCapabilities() throws Exception {
        MutableClock clock = new MutableClock();
        CodecRegistry registry = new CodecRegistry();
        FakeCodec fake = register(registry, ACTIVE_CODEC_ID, 0);
        register(registry, "fallbackA", 20);
        register(registry, "fallbackB", 10);
        fake.setAutoEmitJoinOnRequest(false);
        StreamRuntime runtime = startedRuntime(registry, STREAM_KEY, ACTIVE_CODEC_ID, clock);
        StreamHub hub = newHub(registry, clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        BlockingTransport oldTransport = new BlockingTransport();
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID, "fallbackA"), oldTransport)
                        .getStatus());
        ClientSendSession oldSession = hub.getSession(CLIENT_A);
        fake.emitJoin(new byte[]{0x31});
        assertTrue(hub.drainOne(CLIENT_A));

        oldTransport.failNextSend();
        oldTransport.blockNextAccessUnitSend();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> drain = pool.submit(() -> hub.drainOne(CLIENT_A));
            assertTrue("the old session send must be in flight",
                    oldTransport.awaitSendStarted(2L, TimeUnit.SECONDS));

            hub.detach(CLIENT_A);
            FakeTransport newTransport = new FakeTransport();
            assertEquals(JoinDecision.Status.ACCEPTED,
                    hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID, "fallbackA"),
                            newTransport).getStatus());

            oldTransport.releaseSend();
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> drain.get(2L, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IOException);
        } finally {
            oldTransport.releaseSend();
            pool.shutdownNow();
        }

        ClientSendSession newSession = hub.getSession(CLIENT_A);
        assertNotNull(newSession);
        assertNotSame(oldSession, newSession);
        assertEquals(1, hub.getSessionCount());

        JoinDecision fallback = hub.join(CANDIDATE_CLIENT,
                capabilities("fallbackA", "fallbackB"), new FakeTransport());
        assertEquals(JoinDecision.Status.REQUIRES_EXPLICIT_FALLBACK, fallback.getStatus());
        assertEquals("fallbackA", fallback.getFallbackCodecId());
        assertSame(newSession, hub.getSession(CLIENT_A));
    }

    @Test
    public void join_requestJoinPointFailure_rollsBackSessionAndCapabilities() {
        MutableClock clock = new MutableClock();
        CodecRegistry registry = new CodecRegistry();
        FakeCodec fake = register(registry, ACTIVE_CODEC_ID, 0);
        fake.setAutoEmitJoinOnRequest(false);
        StreamRuntime runtime = startedRuntime(registry, STREAM_KEY, ACTIVE_CODEC_ID, clock);
        StreamHub hub = newHub(registry, clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        FakeTransport failedTransport = new FakeTransport();
        fake.failNextRequestSyncPoint();
        assertThrows(RuntimeException.class,
                () -> hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), failedTransport));

        assertEquals(0, hub.getSessionCount());
        assertNull(hub.getSession(CLIENT_A));
        assertTrue(failedTransport.isClosed());
        assertEquals(1, failedTransport.getCloseCount());

        FakeTransport retryTransport = new FakeTransport();
        JoinDecision retry =
                hub.join(CLIENT_A, capabilities(ACTIVE_CODEC_ID), retryTransport);
        assertEquals(JoinDecision.Status.ACCEPTED, retry.getStatus());
        assertNotNull(hub.getSession(CLIENT_A));
        assertEquals(1, hub.getSessionCount());
    }

    @Test
    public void join_fallback_respectsExistingClientsAndReleasesAfterDetach() {
        MutableClock clock = new MutableClock();
        CodecRegistry registry = new CodecRegistry();
        FakeCodec fake = register(registry, ACTIVE_CODEC_ID, 0);
        register(registry, "fallbackA", 20);
        register(registry, "fallbackB", 10);
        fake.setAutoEmitJoinOnRequest(false);
        StreamRuntime runtime = startedRuntime(registry, STREAM_KEY, ACTIVE_CODEC_ID, clock);
        StreamHub hub = newHub(registry, clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(EXISTING_CLIENT,
                        capabilities(ACTIVE_CODEC_ID, "fallbackA"),
                        new FakeTransport()).getStatus());

        JoinDecision constrained = hub.join(CANDIDATE_CLIENT,
                capabilities("fallbackA", "fallbackB"), new FakeTransport());
        assertEquals(JoinDecision.Status.REQUIRES_EXPLICIT_FALLBACK, constrained.getStatus());
        assertEquals("fallbackA", constrained.getFallbackCodecId());
        assertEquals(1, hub.getSessionCount());

        hub.detach(EXISTING_CLIENT);
        assertEquals(0, hub.getSessionCount());

        JoinDecision released = hub.join(CANDIDATE_CLIENT,
                capabilities("fallbackA", "fallbackB"), new FakeTransport());
        assertEquals(JoinDecision.Status.REQUIRES_EXPLICIT_FALLBACK, released.getStatus());
        assertEquals("fallbackB", released.getFallbackCodecId());
    }

    private static CodecRegistry registry(FakeCodec codec) {
        CodecRegistry registry = new CodecRegistry();
        registry.register(codec.registration());
        return registry;
    }

    private static FakeCodec register(CodecRegistry registry, String codecId, int priority) {
        FakeCodec codec = new FakeCodec(codecId, priority, false, false);
        registry.register(codec.registration());
        return codec;
    }

    private static StreamHub newHub(CodecRegistry registry, MutableClock clock) {
        return new StreamHub(registry, new StreamQueueLimits(128L, 2, MAX_AGE_MS), clock);
    }

    private static StreamRuntime startedRuntime(CodecRegistry registry, StreamKey streamKey,
                                                String codecId, MutableClock clock) {
        StreamRuntime runtime = new StreamRuntime(registry, streamKey,
                descriptor(streamKey, 1, codecId, 64, 48, 64, 48),
                StreamParameters.DEFAULT, clock, JOIN_INTERVAL_MS);
        runtime.start();
        return runtime;
    }

    private static StreamDescriptor descriptor(StreamKey streamKey, int configEpoch,
                                               String codecId, int codedWidth, int codedHeight,
                                               int visibleWidth, int visibleHeight) {
        return StreamDescriptor.builder(streamKey, configEpoch, codecId,
                        FakeCodec.DEFAULT_MIME, BitstreamFormat.ANNEX_B)
                .codedSize(codedWidth, codedHeight)
                .visibleSize(visibleWidth, visibleHeight)
                .build();
    }

    private static ClientCapabilities capabilities(String... codecIds) {
        return new ClientCapabilities(new HashSet<>(Arrays.asList(codecIds)));
    }

    private static FallbackFixture fallbackFixture() {
        MutableClock clock = new MutableClock();
        CodecRegistry registry = new CodecRegistry();
        FakeCodec activeCodec = register(registry, ACTIVE_CODEC_ID, 0);
        register(registry, "fallbackA", 20);
        register(registry, "fallbackB", 10);
        StreamRuntime runtime = startedRuntime(registry, STREAM_KEY, ACTIVE_CODEC_ID, clock);
        StreamHub hub = newHub(registry, clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);
        assertEquals(JoinDecision.Status.ACCEPTED,
                hub.join(EXISTING_CLIENT,
                        capabilities(ACTIVE_CODEC_ID, "fallbackA", "fallbackB"),
                        new FakeTransport()).getStatus());
        return new FallbackFixture(activeCodec, runtime, hub);
    }

    private static void drainToStreaming(StreamHub hub, ClientSessionKey key,
                                         FakeTransport transport) throws IOException {
        assertEquals(ClientState.WAIT_RANDOM_ACCESS, hub.getSession(key).getState());
        assertTrue(hub.drainOne(key));
        assertTrue(hub.drainOne(key));
        assertEquals(ClientState.STREAMING, hub.getSession(key).getState());
        assertEquals(2, transport.getEvents().size());
        assertTrue(transport.getEvents().get(0).isDescriptor());
        assertTrue(transport.getEvents().get(1).isAccessUnit());
    }

    private static EncodedAccessUnit lastEmitted(FakeCodec fake) {
        List<EncodedAccessUnit> emitted = fake.getLastEncoder().getEmitted();
        assertFalse(emitted.isEmpty());
        return emitted.get(emitted.size() - 1);
    }

    private static FakeTransport.Event lastEvent(FakeTransport transport) {
        List<FakeTransport.Event> events = transport.getEvents();
        assertFalse(events.isEmpty());
        return events.get(events.size() - 1);
    }

    private static void assertJoinEvent(FakeTransport.Event event, EncodedAccessUnit expected) {
        assertTrue(event.isAccessUnit());
        assertEquals(expected.getConfigEpoch(), event.getAccessUnit().getConfigEpoch());
        assertEquals(expected.getSequence(), event.getAccessUnit().getSequence());
        assertTrue(event.getAccessUnit().getFlags().isJoinPoint());
    }

    private static final class FallbackFixture {

        final FakeCodec activeCodec;

        final StreamRuntime runtime;

        final StreamHub hub;

        FallbackFixture(FakeCodec activeCodec, StreamRuntime runtime, StreamHub hub) {
            this.activeCodec = activeCodec;
            this.runtime = runtime;
            this.hub = hub;
        }
    }
}
