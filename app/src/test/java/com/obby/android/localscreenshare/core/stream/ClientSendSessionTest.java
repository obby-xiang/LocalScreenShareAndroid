package com.obby.android.localscreenshare.core.stream;

import com.obby.android.localscreenshare.core.codec.CodecDescriptor;
import com.obby.android.localscreenshare.core.codec.CodecIds;
import com.obby.android.localscreenshare.core.codec.CodecPolicy;
import com.obby.android.localscreenshare.core.codec.CodecRegistration;
import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.codec.OverflowAction;
import com.obby.android.localscreenshare.core.codec.jpeg.JpegCodec;
import com.obby.android.localscreenshare.core.model.AccessUnitFlags;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.model.ClientState;
import com.obby.android.localscreenshare.core.model.CodecConfigPart;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.JoinInfo;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.support.BlockingTransport;
import com.obby.android.localscreenshare.core.support.FakeTransport;
import com.obby.android.localscreenshare.core.support.JpegFixtures;
import com.obby.android.localscreenshare.core.support.MutableClock;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ClientSendSessionTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session-1", "stream-1");

    private static final String CLIENT_ID = "client-A";

    private static final ClientSessionKey SESSION_KEY = new ClientSessionKey(STREAM_KEY, CLIENT_ID);

    private static final String REFERENCE_CODEC_ID = "reference";

    private static final String REFERENCE_MIME = "video/reference";

    private static final long MAX_AGE_MS = 100L;

    private static final CodecPolicy REFERENCE_POLICY = new CodecPolicy() {

        @Override
        public OverflowAction getOverflowAction() {
            return OverflowAction.DROP_UNTIL_JOIN_POINT;
        }

        @Override
        public boolean isJoinPoint(EncodedAccessUnit accessUnit) {
            return accessUnit != null && accessUnit.getFlags().isJoinPoint();
        }

        @Override
        public boolean requiresReconfigure(StreamDescriptor previous, StreamDescriptor next) {
            return previous == null || next == null || !previous.sameDecodeConfig(next);
        }
    };

    @Test
    public void join_descriptorSentBeforeFirstAccessUnit() throws Exception {
        FakeTransport transport = new FakeTransport();
        ClientSendSession session = jpegSession(transport, new MutableClock(), 64L, 4);
        StreamDescriptor descriptor = jpegDescriptor(1);

        assertTrue(session.onDescriptor(descriptor));
        assertEquals(ClientState.WAIT_RANDOM_ACCESS, session.getState());
        assertEquals(descriptor.getConfigHash(), session.getCurrentDescriptor().getConfigHash());

        assertTrue(session.drainOne());
        assertEventKinds(transport, FakeTransport.Kind.DESCRIPTOR);
        assertEquals(descriptor.getConfigHash(),
                transport.getEvents().get(0).getDescriptor().getConfigHash());

        EncodedAccessUnit join = jpegJoin(1, 0L);
        assertEquals(QueueOfferResult.ACCEPTED, session.offer(join));
        assertTrue(session.drainOne());

        assertEventKinds(transport, FakeTransport.Kind.DESCRIPTOR,
                FakeTransport.Kind.ACCESS_UNIT);
        assertSame(join, transport.getEvents().get(1).getAccessUnit());
        assertEquals(ClientState.STREAMING, session.getState());
    }

    @Test
    public void offerBeforeDescriptor_returnsNotReady() throws Exception {
        FakeTransport transport = new FakeTransport();
        ClientSendSession session = jpegSession(transport, new MutableClock(), 64L, 4);

        assertEquals(SESSION_KEY, session.getKey());
        assertEquals(CLIENT_ID, session.getClientId());
        assertEquals(ClientState.WAIT_CONFIG, session.getState());
        assertEquals(QueueOfferResult.REJECTED_NOT_READY, session.offer(jpegJoin(1, 0L)));
        assertFalse(session.drainOne());
        assertTrue(transport.getEvents().isEmpty());
        assertEquals(ClientState.WAIT_CONFIG, session.getState());
    }

    @Test
    public void lateJoin_waitsForJoinPoint() throws Exception {
        FakeTransport transport = new FakeTransport();
        ClientSendSession session = jpegSession(transport, new MutableClock(), 64L, 4);
        assertTrue(session.onDescriptor(jpegDescriptor(1)));
        assertTrue(session.drainOne());

        assertEquals(QueueOfferResult.REJECTED_NOT_JOIN_POINT, session.offer(jpegPlain(1, 1L)));
        assertEquals(ClientState.WAIT_RANDOM_ACCESS, session.getState());
        assertEventKinds(transport, FakeTransport.Kind.DESCRIPTOR);

        EncodedAccessUnit join = jpegJoin(1, 2L);
        assertEquals(QueueOfferResult.ACCEPTED, session.offer(join));
        assertEquals(ClientState.WAIT_RANDOM_ACCESS, session.getState());
        assertTrue(session.drainOne());

        assertEventKinds(transport, FakeTransport.Kind.DESCRIPTOR,
                FakeTransport.Kind.ACCESS_UNIT);
        assertSame(join, transport.getEvents().get(1).getAccessUnit());
        assertEquals(ClientState.STREAMING, session.getState());
    }

    @Test
    public void ordinaryOldEpochAu_rejected() throws Exception {
        FakeTransport transport = new FakeTransport();
        ClientSendSession session = jpegSession(transport, new MutableClock(), 64L, 4);
        assertTrue(session.onDescriptor(jpegDescriptor(2)));
        assertTrue(session.drainOne());
        streamTo(session, jpegJoin(2, 0L));

        assertEquals(QueueOfferResult.REJECTED_EPOCH, session.offer(jpegPlain(1, 1L)));

        StreamKey otherStream = new StreamKey("session-1", "stream-2");
        EncodedAccessUnit otherStreamAu = accessUnit(otherStream, 2, 2L, false,
                new byte[]{0x02});
        assertEquals(QueueOfferResult.REJECTED_EPOCH, session.offer(otherStreamAu));

        assertEquals(ClientState.STREAMING, session.getState());
        assertEventKinds(transport, FakeTransport.Kind.DESCRIPTOR,
                FakeTransport.Kind.ACCESS_UNIT);
    }

    @Test
    public void epochChange_clearsQueueAndDescriptorPrecedesNewAu() throws Exception {
        FakeTransport transport = new FakeTransport();
        ClientSendSession session = jpegSession(transport, new MutableClock(), 64L, 4);
        StreamDescriptor first = jpegDescriptor(1);
        assertTrue(session.onDescriptor(first));
        assertTrue(session.drainOne());

        assertEquals(QueueOfferResult.ACCEPTED, session.offer(jpegJoin(1, 0L)));
        assertEquals(1L, session.getStats().getQueuedUnits());

        StreamDescriptor second = jpegDescriptor(2, 48, 36);
        assertTrue(session.onDescriptor(second));
        assertEquals(ClientState.WAIT_RANDOM_ACCESS, session.getState());
        assertEquals(second.getConfigHash(), session.getCurrentDescriptor().getConfigHash());
        assertEquals("the previous epoch queue must be discarded",
                0L, session.getStats().getQueuedUnits());

        assertTrue(session.drainOne());
        assertEventKinds(transport, FakeTransport.Kind.DESCRIPTOR,
                FakeTransport.Kind.DESCRIPTOR);
        assertEquals(second.getConfigHash(),
                transport.getEvents().get(1).getDescriptor().getConfigHash());
        assertFalse("an old-epoch access unit must not survive the epoch change",
                session.drainOne());

        EncodedAccessUnit newJoin = jpegJoin(2, 1L);
        assertEquals(QueueOfferResult.ACCEPTED, session.offer(newJoin));
        assertTrue(session.drainOne());

        assertEventKinds(transport, FakeTransport.Kind.DESCRIPTOR,
                FakeTransport.Kind.DESCRIPTOR, FakeTransport.Kind.ACCESS_UNIT);
        assertSame(newJoin, transport.getEvents().get(2).getAccessUnit());
        assertEquals(ClientState.STREAMING, session.getState());
    }

    @Test
    public void duplicateAndStaleDescriptorIgnored() throws Exception {
        FakeTransport transport = new FakeTransport();
        ClientSendSession session = jpegSession(transport, new MutableClock(), 64L, 4);
        StreamDescriptor first = jpegDescriptor(1);

        assertTrue(session.onDescriptor(first));
        assertFalse("a repeated descriptor is not a state change",
                session.onDescriptor(first));
        assertFalse("the same epoch and config hash is a duplicate",
                session.onDescriptor(jpegDescriptor(1)));

        StreamDescriptor second = jpegDescriptor(2);
        assertTrue(session.onDescriptor(second));
        assertFalse("an older epoch must be ignored", session.onDescriptor(first));
        assertFalse("a repeated current descriptor is a duplicate",
                session.onDescriptor(second));

        assertEquals(2, session.getCurrentDescriptor().getConfigEpoch());
    }

    @Test
    public void sameEpochDifferentConfigThrows() throws Exception {
        FakeTransport transport = new FakeTransport();
        ClientSendSession session = jpegSession(transport, new MutableClock(), 64L, 4);
        StreamDescriptor first = jpegDescriptor(1, 64, 48);
        assertTrue(session.onDescriptor(first));

        try {
            session.onDescriptor(jpegDescriptor(1, 48, 36));
            fail("a changed decode config in the same epoch must be rejected");
        } catch (IllegalArgumentException expected) {
            // The session keeps its original descriptor and pending state.
        }

        assertEquals(first.getConfigHash(), session.getCurrentDescriptor().getConfigHash());
        assertEquals(64, session.getCurrentDescriptor().getVisibleWidth());
        assertEquals(ClientState.WAIT_RANDOM_ACCESS, session.getState());
    }

    @Test
    public void jpegOverflow_keepsLatestAndDoesNotResync() throws Exception {
        FakeTransport transport = new FakeTransport();
        ClientSendSession session = jpegSession(transport, new MutableClock(), 64L, 1);
        assertTrue(session.onDescriptor(jpegDescriptor(1)));
        assertTrue(session.drainOne());
        streamTo(session, jpegJoin(1, 0L));

        assertEquals(QueueOfferResult.ACCEPTED, session.offer(jpegPlain(1, 1L)));
        assertEquals(QueueOfferResult.ACCEPTED_KEPT_LATEST,
                session.offer(jpegPlain(1, 2L)));

        assertEquals(ClientState.STREAMING, session.getState());
        assertEquals(0L, session.getStats().getResyncCount());

        assertEquals(QueueOfferResult.REJECTED_TOO_LARGE,
                session.offer(jpegPlain(1, 3L, new byte[65])));
        assertEquals("a JPEG oversized frame must not force a resync",
                ClientState.STREAMING, session.getState());
        assertEquals(0L, session.getStats().getResyncCount());
        assertEquals(0L, session.getStats().getQueuedUnits());
    }

    @Test
    public void referenceOverflow_entersResyncingUntilJoin() throws Exception {
        FakeTransport transport = new FakeTransport();
        ClientSendSession session = referenceSession(transport, new MutableClock(), 64L, 2);
        assertTrue(session.onDescriptor(referenceDescriptor(1)));
        assertTrue(session.drainOne());
        streamTo(session, referenceJoin(1, 0L));

        assertEquals(QueueOfferResult.ACCEPTED, session.offer(referencePlain(1, 1L)));
        assertEquals(QueueOfferResult.ACCEPTED, session.offer(referencePlain(1, 2L)));
        assertEquals(QueueOfferResult.REJECTED_NOT_JOIN_POINT,
                session.offer(referencePlain(1, 3L)));

        assertEquals(ClientState.RESYNCING, session.getState());
        assertEquals(1L, session.getStats().getResyncCount());
        assertEquals(0L, session.getStats().getQueuedUnits());

        EncodedAccessUnit recovery = referenceJoin(1, 4L);
        assertEquals(QueueOfferResult.ACCEPTED_AFTER_RESYNC, session.offer(recovery));
        assertEquals("only a successful join send leaves RESYNCING",
                ClientState.RESYNCING, session.getState());

        assertTrue(session.drainOne());
        assertSame(recovery, lastEvent(transport).getAccessUnit());
        assertEquals(ClientState.STREAMING, session.getState());
        assertEquals(1L, session.getStats().getResyncCount());
    }

    @Test
    public void referenceTooLargeAu_entersResyncing() throws Exception {
        FakeTransport transport = new FakeTransport();
        ClientSendSession session = referenceSession(transport, new MutableClock(), 16L, 4);
        assertTrue(session.onDescriptor(referenceDescriptor(1)));
        assertTrue(session.drainOne());
        streamTo(session, referenceJoin(1, 0L));

        EncodedAccessUnit tooLarge = referencePlain(1, 1L, new byte[17]);
        assertEquals(QueueOfferResult.REJECTED_TOO_LARGE, session.offer(tooLarge));

        assertEquals(ClientState.RESYNCING, session.getState());
        assertEquals(1L, session.getStats().getResyncCount());
        assertEquals(0L, session.getStats().getQueuedUnits());
    }

    @Test
    public void referenceExpiry_entersResyncing() throws Exception {
        FakeTransport transport = new FakeTransport();
        MutableClock clock = new MutableClock();
        ClientSendSession session = referenceSession(transport, clock, 64L, 4);
        assertTrue(session.onDescriptor(referenceDescriptor(1)));
        assertTrue(session.drainOne());
        streamTo(session, referenceJoin(1, 0L));

        assertEquals(QueueOfferResult.ACCEPTED, session.offer(referencePlain(1, 1L)));
        clock.advance(MAX_AGE_MS + 1L);
        drainUntilIdle(session);

        assertEquals(ClientState.RESYNCING, session.getState());
        assertEquals(1L, session.getStats().getResyncCount());
        assertEquals(0L, session.getStats().getQueuedUnits());

        EncodedAccessUnit recovery = referenceJoin(1, 2L);
        assertEquals(QueueOfferResult.ACCEPTED_AFTER_RESYNC, session.offer(recovery));
        assertTrue(session.drainOne());
        assertEquals(ClientState.STREAMING, session.getState());
    }

    @Test
    public void slowTransport_doesNotBlockOffer() throws Exception {
        BlockingTransport transport = new BlockingTransport();
        ClientSendSession session = jpegSession(transport, new MutableClock(), 64L, 4);
        assertTrue(session.onDescriptor(jpegDescriptor(1)));
        assertTrue(session.drainOne());

        transport.blockNextAccessUnitSend();
        assertEquals(QueueOfferResult.ACCEPTED, session.offer(jpegJoin(1, 0L)));

        ExecutorService drainExecutor = Executors.newSingleThreadExecutor();
        ExecutorService offerExecutor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> drain = drainExecutor.submit(
                    (Callable<Boolean>) () -> session.drainOne());
            assertTrue("send must start before the offer probe",
                    transport.awaitSendStarted(2L, TimeUnit.SECONDS));

            Future<QueueOfferResult> offered = offerExecutor.submit(
                    (Callable<QueueOfferResult>) () -> session.offer(jpegJoin(1, 1L)));
            assertEquals("offer must not wait for the in-flight send",
                    QueueOfferResult.ACCEPTED, offered.get(2L, TimeUnit.SECONDS));

            transport.releaseSend();
            assertTrue(drain.get(2L, TimeUnit.SECONDS));
        } finally {
            transport.releaseSend();
            drainExecutor.shutdownNow();
            offerExecutor.shutdownNow();
        }

        assertEquals(ClientState.STREAMING, session.getState());
    }

    @Test
    public void epochChangeDuringInFlightOldJoin_keepsNewEpochWaiting() throws Exception {
        BlockingTransport transport = new BlockingTransport();
        ClientSendSession session = jpegSession(transport, new MutableClock(), 64L, 4);
        assertTrue(session.onDescriptor(jpegDescriptor(1)));
        assertTrue(session.drainOne());

        transport.blockNextAccessUnitSend();
        EncodedAccessUnit oldJoin = jpegJoin(1, 0L);
        assertEquals(QueueOfferResult.ACCEPTED, session.offer(oldJoin));

        ExecutorService drainExecutor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> drain = drainExecutor.submit(
                    (Callable<Boolean>) () -> session.drainOne());
            assertTrue(transport.awaitSendStarted(2L, TimeUnit.SECONDS));

            StreamDescriptor newDescriptor = jpegDescriptor(2, 48, 36);
            assertTrue(session.onDescriptor(newDescriptor));
            assertEquals(ClientState.WAIT_RANDOM_ACCESS, session.getState());

            transport.releaseSend();
            assertTrue(drain.get(2L, TimeUnit.SECONDS));
        } finally {
            transport.releaseSend();
            drainExecutor.shutdownNow();
        }

        ClientState stateAfterOldSend = session.getState();
        QueueOfferResult newEpochPlain = session.offer(jpegPlain(2, 1L));
        assertTrue("an old in-flight join must not promote the new epoch: state="
                        + stateAfterOldSend + ", nonJoinOffer=" + newEpochPlain,
                stateAfterOldSend == ClientState.WAIT_RANDOM_ACCESS
                        && newEpochPlain == QueueOfferResult.REJECTED_NOT_JOIN_POINT);

        assertTrue(session.drainOne());
        List<FakeTransport.Event> events = transport.getEvents();
        assertEquals(3, events.size());
        assertEquals(FakeTransport.Kind.DESCRIPTOR, events.get(2).getKind());
        assertEquals(2, events.get(2).getDescriptor().getConfigEpoch());

        EncodedAccessUnit newJoin = jpegJoin(2, 2L);
        assertEquals(QueueOfferResult.ACCEPTED, session.offer(newJoin));
        assertTrue(session.drainOne());
        assertSame(newJoin, lastEvent(transport).getAccessUnit());
        assertEquals(ClientState.STREAMING, session.getState());
    }

    @Test
    public void transportFailure_detachesAndClosesOnce() throws Exception {
        FakeTransport transport = new FakeTransport();
        ClientSendSession session = jpegSession(transport, new MutableClock(), 64L, 4);
        assertTrue(session.onDescriptor(jpegDescriptor(1)));
        assertTrue(session.drainOne());
        streamTo(session, jpegJoin(1, 0L));

        assertEquals(QueueOfferResult.ACCEPTED, session.offer(jpegPlain(1, 1L)));
        transport.failNextSend();

        try {
            session.drainOne();
            fail("a failed send must surface as IOException");
        } catch (IOException expected) {
            // The session detaches before rethrowing.
        }

        assertTrue(session.isDetached());
        assertEquals(ClientState.CLOSED, session.getState());
        assertTrue(transport.isClosed());
        assertEquals(1, transport.getCloseCount());

        session.detach();
        session.detach();
        assertEquals("transport.close() must run only once", 1, transport.getCloseCount());
        assertEquals(QueueOfferResult.REJECTED_CLOSED, session.offer(jpegJoin(1, 2L)));
        assertFalse(session.drainOne());
    }

    @Test
    public void descriptorSendUncheckedFailure_detachesClosesOnceAndDoesNotLeakPending()
            throws Exception {
        FakeTransport transport = new FakeTransport();
        ClientSendSession session = jpegSession(transport, new MutableClock(), 64L, 4);
        assertTrue(session.onDescriptor(jpegDescriptor(1)));
        transport.failNextDescriptorSendUnchecked();

        assertThrows(IllegalStateException.class, () -> session.drainOne());

        assertTrue(session.isDetached());
        assertEquals(ClientState.CLOSED, session.getState());
        assertTrue(transport.isClosed());
        assertEquals(1, transport.getCloseCount());
        assertTrue(transport.getEvents().isEmpty());
        assertFalse("a failed descriptor must not be retried or followed by an AU",
                session.drainOne());
        assertEquals(0, transport.getEvents().size());
        assertEquals(QueueOfferResult.REJECTED_CLOSED, session.offer(jpegJoin(1, 0L)));

        session.detach();
        assertEquals(1, transport.getCloseCount());
    }

    @Test
    public void accessUnitSendUncheckedFailure_detachesStopsSendingAndClosesOnce()
            throws Exception {
        FakeTransport transport = new FakeTransport();
        ClientSendSession session = referenceSession(transport, new MutableClock(), 64L, 4);
        assertTrue(session.onDescriptor(referenceDescriptor(1)));
        assertTrue(session.drainOne());
        streamTo(session, referenceJoin(1, 0L));

        assertEquals(QueueOfferResult.ACCEPTED, session.offer(referencePlain(1, 1L)));
        assertEquals(QueueOfferResult.ACCEPTED, session.offer(referencePlain(1, 2L)));
        int eventsBefore = transport.getEvents().size();
        assertEquals(2, eventsBefore);
        transport.failNextAccessUnitSendUnchecked();

        assertThrows(IllegalStateException.class, () -> session.drainOne());

        assertTrue(session.isDetached());
        assertEquals(ClientState.CLOSED, session.getState());
        assertTrue(transport.isClosed());
        assertEquals(1, transport.getCloseCount());
        assertEquals(eventsBefore, transport.getEvents().size());
        assertFalse("a failed AU must not be followed by another queued AU",
                session.drainOne());
        assertEquals(eventsBefore, transport.getEvents().size());
        assertEquals(QueueOfferResult.REJECTED_CLOSED, session.offer(referenceJoin(1, 3L)));
    }

    @Test
    public void detach_isIdempotentAndRejectsNewUnits() throws Exception {
        FakeTransport transport = new FakeTransport();
        ClientSendSession session = jpegSession(transport, new MutableClock(), 64L, 4);

        session.detach();
        session.detach();

        assertTrue(session.isDetached());
        assertEquals(ClientState.CLOSED, session.getState());
        assertEquals(1, transport.getCloseCount());
        assertEquals(QueueOfferResult.REJECTED_CLOSED, session.offer(jpegJoin(1, 0L)));
        assertFalse(session.drainOne());
        assertTrue(transport.getEvents().isEmpty());
    }

    @Test
    public void stats_reportQueuedDroppedResyncSentAndDescriptorCounts() throws Exception {
        FakeTransport transport = new FakeTransport();
        ClientSendSession session = referenceSession(transport, new MutableClock(), 64L, 1);

        assertStats(session.getStats(), 0L, 0L, 0L, 0L, 0L, 0L);

        assertTrue(session.onDescriptor(referenceDescriptor(1)));
        assertStats(session.getStats(), 0L, 0L, 0L, 0L, 0L, 0L);
        assertTrue(session.drainOne());
        assertStats(session.getStats(), 0L, 0L, 0L, 0L, 0L, 1L);

        assertEquals(QueueOfferResult.ACCEPTED, session.offer(referenceJoin(1, 0L)));
        assertStats(session.getStats(), 1L, 4L, 0L, 0L, 0L, 1L);
        assertTrue(session.drainOne());
        assertStats(session.getStats(), 0L, 0L, 0L, 0L, 1L, 1L);
        assertEquals(ClientState.STREAMING, session.getState());

        assertEquals(QueueOfferResult.ACCEPTED, session.offer(referencePlain(1, 1L)));
        assertStats(session.getStats(), 1L, 5L, 0L, 0L, 1L, 1L);
        assertEquals(QueueOfferResult.REJECTED_NOT_JOIN_POINT,
                session.offer(referencePlain(1, 2L, new byte[6])));
        assertStats(session.getStats(), 0L, 0L, 1L, 1L, 1L, 1L);
        assertEquals(ClientState.RESYNCING, session.getState());

        assertEquals(QueueOfferResult.ACCEPTED_AFTER_RESYNC,
                session.offer(referenceJoin(1, 3L, new byte[7])));
        assertStats(session.getStats(), 1L, 7L, 1L, 1L, 1L, 1L);
        assertTrue(session.drainOne());
        assertStats(session.getStats(), 0L, 0L, 1L, 1L, 2L, 1L);
        assertEquals(ClientState.STREAMING, session.getState());

        session.detach();
        assertStats(session.getStats(), 0L, 0L, 1L, 1L, 2L, 1L);
    }

    private static ClientSendSession jpegSession(FakeTransport transport, MutableClock clock,
                                                 long maxBytes, int maxUnits) {
        return new ClientSendSession(SESSION_KEY, CodecRegistry.withDefaults(),
                new StreamQueueLimits(maxBytes, maxUnits, MAX_AGE_MS), transport, clock);
    }

    private static ClientSendSession referenceSession(FakeTransport transport, MutableClock clock,
                                                      long maxBytes, int maxUnits) {
        CodecRegistry registry = new CodecRegistry();
        CodecDescriptor codec = new CodecDescriptor(REFERENCE_CODEC_ID, REFERENCE_MIME,
                BitstreamFormat.ANNEX_B, false, false, false);
        registry.register(new CodecRegistration(codec,
                (streamKey, descriptor, parameters, output) -> null,
                (streamKey, descriptor, renderTarget) -> null,
                REFERENCE_POLICY, 10));
        return new ClientSendSession(SESSION_KEY, registry,
                new StreamQueueLimits(maxBytes, maxUnits, MAX_AGE_MS), transport, clock);
    }

    private static StreamDescriptor jpegDescriptor(int configEpoch) {
        return jpegDescriptor(configEpoch, 64, 48);
    }

    private static StreamDescriptor jpegDescriptor(int configEpoch, int visibleWidth,
                                                   int visibleHeight) {
        return StreamDescriptor.builder(STREAM_KEY, configEpoch, CodecIds.JPEG, JpegCodec.MIME,
                        BitstreamFormat.JPEG_FRAME)
                .codedSize(64, 48)
                .visibleSize(visibleWidth, visibleHeight)
                .build();
    }

    private static StreamDescriptor referenceDescriptor(int configEpoch) {
        return StreamDescriptor.builder(STREAM_KEY, configEpoch, REFERENCE_CODEC_ID,
                        REFERENCE_MIME, BitstreamFormat.ANNEX_B)
                .codedSize(64, 48)
                .visibleSize(64, 48)
                .build();
    }

    private static EncodedAccessUnit jpegJoin(int configEpoch, long sequence) {
        return accessUnit(STREAM_KEY, configEpoch, sequence, true, JpegFixtures.VALID_FRAME);
    }

    private static EncodedAccessUnit jpegPlain(int configEpoch, long sequence) {
        return accessUnit(STREAM_KEY, configEpoch, sequence, false, new byte[]{0x11});
    }

    private static EncodedAccessUnit jpegPlain(int configEpoch, long sequence, byte[] payload) {
        return accessUnit(STREAM_KEY, configEpoch, sequence, false, payload);
    }

    private static EncodedAccessUnit referenceJoin(int configEpoch, long sequence) {
        return referenceJoin(configEpoch, sequence, new byte[4]);
    }

    private static EncodedAccessUnit referenceJoin(int configEpoch, long sequence,
                                                   byte[] payload) {
        Arrays.fill(payload, (byte) 0x22);
        return accessUnit(STREAM_KEY, configEpoch, sequence, true, payload);
    }

    private static EncodedAccessUnit referencePlain(int configEpoch, long sequence) {
        return referencePlain(configEpoch, sequence, new byte[5]);
    }

    private static EncodedAccessUnit referencePlain(int configEpoch, long sequence,
                                                    byte[] payload) {
        return accessUnit(STREAM_KEY, configEpoch, sequence, false, payload);
    }

    private static EncodedAccessUnit accessUnit(StreamKey streamKey, int configEpoch,
                                                long sequence, boolean joinPoint,
                                                byte[] payload) {
        AccessUnitFlags flags = joinPoint
                ? AccessUnitFlags.of(true, true, false)
                : AccessUnitFlags.none();
        JoinInfo joinInfo = joinPoint
                ? new JoinInfo(configEpoch, Collections.singletonList(
                        new CodecConfigPart("csd-0", new byte[]{0x01})))
                : null;
        return new EncodedAccessUnit(streamKey, configEpoch, sequence, sequence * 1_000L,
                EncodedAccessUnit.DTS_UNSET, flags, joinInfo, null, payload);
    }

    private static void assertEventKinds(FakeTransport transport, FakeTransport.Kind... kinds) {
        List<FakeTransport.Event> events = transport.getEvents();
        assertEquals(kinds.length, events.size());
        for (int i = 0; i < kinds.length; i++) {
            assertEquals("event " + i, kinds[i], events.get(i).getKind());
        }
    }

    private static FakeTransport.Event lastEvent(FakeTransport transport) {
        List<FakeTransport.Event> events = transport.getEvents();
        assertFalse(events.isEmpty());
        return events.get(events.size() - 1);
    }

    private static void streamTo(ClientSendSession session, EncodedAccessUnit join)
            throws IOException {
        assertEquals(QueueOfferResult.ACCEPTED, session.offer(join));
        assertTrue(session.drainOne());
        assertEquals(ClientState.STREAMING, session.getState());
    }

    private static void drainUntilIdle(ClientSendSession session) throws IOException {
        for (int i = 0; i < 16; i++) {
            if (!session.drainOne()) {
                return;
            }
        }
        fail("session did not become idle");
    }

    private static void assertStats(ClientSendStats stats, long queuedUnits, long queuedBytes,
                                    long droppedUnits, long resyncCount, long sentUnits,
                                    long descriptorSends) {
        assertEquals("queuedUnits", queuedUnits, stats.getQueuedUnits());
        assertEquals("queuedBytes", queuedBytes, stats.getQueuedBytes());
        assertEquals("droppedUnits", droppedUnits, stats.getDroppedUnits());
        assertEquals("resyncCount", resyncCount, stats.getResyncCount());
        assertEquals("sentUnits", sentUnits, stats.getSentUnits());
        assertEquals("descriptorSends", descriptorSends, stats.getDescriptorSends());
    }
}
