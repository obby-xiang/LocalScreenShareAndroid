package com.obby.android.localscreenshare.core.stream;

import com.obby.android.localscreenshare.core.codec.CodecIds;
import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.codec.jpeg.JpegCodec;
import com.obby.android.localscreenshare.core.metrics.PipelineMetrics;
import com.obby.android.localscreenshare.core.model.AccessUnitFlags;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.model.ClientState;
import com.obby.android.localscreenshare.core.model.CodecConfigPart;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.JoinInfo;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.net.MediaTransportKind;
import com.obby.android.localscreenshare.core.support.FakeTransport;
import com.obby.android.localscreenshare.core.support.JpegFixtures;
import com.obby.android.localscreenshare.core.support.MutableClock;
import com.obby.android.localscreenshare.core.support.MutableNanoClock;

import org.junit.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the transport swap and the failure recovery hook of {@link ClientSendSession}.
 *
 * <p>Both mechanisms exist so that one failed media channel cannot end the control-plane call:
 * a swap has to re-send the current descriptor before the next access unit, and a recovered
 * failure has to keep the session attached while the replacement takes over.</p>
 */
public class ClientSendSessionTransportSwapTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session-1", "stream-1");

    private static final ClientSessionKey SESSION_KEY = new ClientSessionKey(STREAM_KEY, "client-A");

    private static final long MAX_AGE_MS = 100L;

    @Test
    public void swap_resendsDescriptorAndDropsThePreviousQueue() throws Exception {
        FakeTransport first = new FakeTransport();
        ClientSendSession session = session(first);
        assertTrue(session.onDescriptor(descriptor(1)));
        assertTrue(session.drainOne());
        streamTo(session, join(1, 0L));

        // Two units the old channel would still have carried; the swap must drop them.
        assertEquals(QueueOfferResult.ACCEPTED, session.offer(join(1, 1L)));
        assertEquals(QueueOfferResult.ACCEPTED, session.offer(join(1, 2L)));

        FakeTransport second = new FakeTransport();
        assertSame(first, session.swapTransport(second));
        assertEquals(MediaTransportKind.GRPC, session.getTransportKind());

        assertEquals(ClientState.WAIT_RANDOM_ACCESS, session.getState());
        assertTrue(session.drainOne());
        assertEquals(1, second.getEvents().size());
        assertTrue(second.getEvents().get(0).isDescriptor());
        assertTrue(first.getEvents().size() >= 2);
        assertEquals(0L, session.getStats().getQueuedUnits());

        assertEquals(QueueOfferResult.ACCEPTED, session.offer(join(1, 3L)));
        assertTrue(session.drainOne());
        assertEquals(ClientState.STREAMING, session.getState());
        assertEquals(2, second.getEvents().size());
        assertTrue(second.getEvents().get(1).isAccessUnit());
        assertEquals(2L, session.getStats().getDescriptorSends());
    }

    @Test
    public void swapWithAChannel_recordsTheNewChannel() throws Exception {
        ClientSendSession session = session(new FakeTransport());
        assertTrue(session.onDescriptor(descriptor(1)));
        assertEquals(MediaTransportKind.GRPC, session.getTransportKind());
        assertEquals("grpc", session.snapshot().getChannel());

        session.swapTransport(new FakeTransport(), MediaTransportKind.TCP);

        assertEquals(MediaTransportKind.TCP, session.getTransportKind());
        assertEquals("tcp", session.snapshot().getChannel());
    }

    @Test
    public void swapBeforeAnyDescriptor_queuesNothing() throws Exception {
        ClientSendSession session = session(new FakeTransport());
        FakeTransport second = new FakeTransport();

        session.swapTransport(second);

        assertEquals(ClientState.WAIT_CONFIG, session.getState());
        assertFalse(session.drainOne());
        assertTrue(second.getEvents().isEmpty());
    }

    @Test
    public void swapOnADetachedSession_returnsNullAndLeavesTheReplacementOpen() {
        ClientSendSession session = session(new FakeTransport());
        session.detach();

        FakeTransport second = new FakeTransport();

        assertNull(session.swapTransport(second));
        assertFalse(second.isClosed());
    }

    @Test
    public void swapWithoutATransport_isRejected() {
        ClientSendSession session = session(new FakeTransport());

        assertThrows(IllegalArgumentException.class, () -> session.swapTransport(null));
        assertThrows(IllegalArgumentException.class,
                () -> session.swapTransport(new FakeTransport(), null));
    }

    @Test
    public void recovery_installsTheReplacementWithoutDetaching() throws Exception {
        FakeTransport first = new FakeTransport();
        FakeTransport second = new FakeTransport();
        RecordingRecovery recovery = new RecordingRecovery(second);
        ClientSendSession session = measuredSession(first, recovery);
        assertTrue(session.onDescriptor(descriptor(1)));
        assertTrue(session.drainOne());
        streamTo(session, join(1, 0L));

        assertEquals(QueueOfferResult.ACCEPTED, session.offer(join(1, 1L)));
        first.failNextSend();

        assertFalse(session.drainOne());

        assertEquals(1, recovery.getCalls());
        assertSame(first, recovery.getFailed());
        assertTrue(recovery.getCause() instanceof IOException);
        assertFalse(session.isDetached());
        assertEquals(ClientState.WAIT_RANDOM_ACCESS, session.getState());
        assertTrue(first.isClosed());

        assertTrue(session.drainOne());
        assertEquals(1, second.getEvents().size());
        assertTrue(second.getEvents().get(0).isDescriptor());

        assertEquals(QueueOfferResult.ACCEPTED, session.offer(join(1, 2L)));
        assertTrue(session.drainOne());
        assertEquals(ClientState.STREAMING, session.getState());
        assertEquals(2L, session.getStats().getDescriptorSends());
        assertEquals(2L, session.getStats().getSentUnits());
    }

    @Test
    public void recoveryThatDeclines_detachesAndRethrows() {
        FakeTransport first = new FakeTransport();
        ClientSendSession session = measuredSession(first, new RecordingRecovery(null));
        assertTrue(session.onDescriptor(descriptor(1)));
        first.failNextSend();

        assertThrows(IOException.class, session::drainOne);

        assertTrue(session.isDetached());
        assertEquals(ClientState.CLOSED, session.getState());
        assertEquals(1, first.getCloseCount());
    }

    @Test
    public void recoveryThatThrows_isTreatedAsDeclining() {
        FakeTransport first = new FakeTransport();
        RecordingRecovery recovery = new RecordingRecovery(null);
        recovery.setFailure(new IllegalStateException("hook failed"));
        ClientSendSession session = measuredSession(first, recovery);
        assertTrue(session.onDescriptor(descriptor(1)));
        first.failNextSend();

        assertThrows(IOException.class, session::drainOne);

        assertEquals(1, recovery.getCalls());
        assertTrue(session.isDetached());
        assertEquals(1, first.getCloseCount());
    }

    @Test
    public void uncheckedFailure_isNotOfferedToRecovery() {
        FakeTransport first = new FakeTransport();
        RecordingRecovery recovery = new RecordingRecovery(new FakeTransport());
        ClientSendSession session = measuredSession(first, recovery);
        assertTrue(session.onDescriptor(descriptor(1)));
        first.failNextDescriptorSendUnchecked();

        assertThrows(IllegalStateException.class, session::drainOne);

        assertEquals(0, recovery.getCalls());
        assertTrue(session.isDetached());
    }

    @Test
    public void recoveryAfterAConcurrentDetach_closesTheReplacementAndStaysDetached() throws Exception {
        FakeTransport first = new FakeTransport();
        FakeTransport second = new FakeTransport();
        final ClientSendSession[] holder = new ClientSendSession[1];
        RecordingRecovery recovery = new RecordingRecovery(second);
        recovery.setBeforeReturn(() -> holder[0].detach());
        holder[0] = measuredSession(first, recovery);
        ClientSendSession session = holder[0];
        assertTrue(session.onDescriptor(descriptor(1)));
        first.failNextSend();

        assertThrows(IOException.class, session::drainOne);

        assertTrue(session.isDetached());
        assertTrue(second.isClosed());
        assertEquals(1, first.getCloseCount());
        assertTrue(second.getEvents().isEmpty());
    }

    private static ClientSendSession session(FakeTransport transport) {
        return new ClientSendSession(SESSION_KEY, CodecRegistry.withDefaults(),
                new StreamQueueLimits(64L, 4, MAX_AGE_MS), transport, new MutableClock());
    }

    private static ClientSendSession measuredSession(FakeTransport transport,
                                                     MediaTransportRecovery recovery) {
        return new ClientSendSession(SESSION_KEY, CodecRegistry.withDefaults(),
                new StreamQueueLimits(64L, 4, MAX_AGE_MS), transport, new MutableClock(),
                PipelineMetrics.create(new MutableNanoClock(), 8), recovery);
    }

    private static StreamDescriptor descriptor(int configEpoch) {
        return StreamDescriptor.builder(STREAM_KEY, configEpoch, CodecIds.JPEG, JpegCodec.MIME,
                        BitstreamFormat.JPEG_FRAME)
                .codedSize(64, 48)
                .visibleSize(64, 48)
                .build();
    }

    private static EncodedAccessUnit join(int configEpoch, long sequence) {
        AccessUnitFlags flags = AccessUnitFlags.of(true, true, false);
        JoinInfo joinInfo = new JoinInfo(configEpoch, Collections.singletonList(
                new CodecConfigPart("csd-0", new byte[]{0x01})));
        return new EncodedAccessUnit(STREAM_KEY, configEpoch, sequence, sequence * 1_000L,
                EncodedAccessUnit.DTS_UNSET, flags, joinInfo, null, JpegFixtures.VALID_FRAME);
    }

    private static void streamTo(ClientSendSession session, EncodedAccessUnit join)
            throws IOException {
        assertEquals(QueueOfferResult.ACCEPTED, session.offer(join));
        assertTrue(session.drainOne());
        assertEquals(ClientState.STREAMING, session.getState());
    }

    /**
     * Recovery hook that records how it was called and returns a fixed replacement.
     */
    private static final class RecordingRecovery implements MediaTransportRecovery {

        private final MediaTransport replacement;

        private final AtomicInteger calls = new AtomicInteger();

        private MediaTransport failed;

        private IOException cause;

        private RuntimeException failure;

        private Runnable beforeReturn;

        RecordingRecovery(MediaTransport replacement) {
            this.replacement = replacement;
        }

        void setFailure(RuntimeException failure) {
            this.failure = failure;
        }

        void setBeforeReturn(Runnable beforeReturn) {
            this.beforeReturn = beforeReturn;
        }

        int getCalls() {
            return calls.get();
        }

        MediaTransport getFailed() {
            return failed;
        }

        IOException getCause() {
            return cause;
        }

        @Override
        public MediaTransport onTransportFailed(MediaTransport failed, IOException cause) {
            calls.incrementAndGet();
            this.failed = failed;
            this.cause = cause;
            if (failure != null) {
                throw failure;
            }
            if (beforeReturn != null) {
                beforeReturn.run();
            }
            return replacement;
        }
    }
}
