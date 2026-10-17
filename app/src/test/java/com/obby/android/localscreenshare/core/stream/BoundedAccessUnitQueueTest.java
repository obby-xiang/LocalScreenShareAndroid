package com.obby.android.localscreenshare.core.stream;

import com.obby.android.localscreenshare.core.codec.OverflowAction;
import com.obby.android.localscreenshare.core.model.AccessUnitFlags;
import com.obby.android.localscreenshare.core.model.CodecConfigPart;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.JoinInfo;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.support.MutableClock;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BoundedAccessUnitQueueTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session-1", "stream-1");

    private static final int CONFIG_EPOCH = 1;

    private static final long MAX_AGE_MS = 100L;

    @Test
    public void offer_rejectsNull() {
        BoundedAccessUnitQueue queue = queue(64L, 4, OverflowAction.KEEP_LATEST_FRAME);
        MutableClock clock = new MutableClock();

        try {
            queue.offer(null, clock.nowMs());
            fail("offer(null) must not enter the queue");
        } catch (IllegalArgumentException | NullPointerException expected) {
            // A null access unit is rejected before any queue state is touched.
        }

        assertEquals(0, queue.size());
        assertEquals(0L, queue.bytes());
        assertFalse(queue.isResyncRequired());
    }

    @Test
    public void tooLargeAu_isRejected() {
        BoundedAccessUnitQueue queue = queue(8L, 4, OverflowAction.KEEP_LATEST_FRAME);
        MutableClock clock = new MutableClock();
        EncodedAccessUnit resident = joinAu(1, 4);
        queue.offer(resident, clock.nowMs());

        QueueOfferResult result = queue.offer(joinAu(2, 9), clock.nowMs());

        assertEquals(QueueOfferResult.REJECTED_TOO_LARGE, result);
        assertEquals(0, queue.size());
        assertEquals(0L, queue.bytes());
        assertFalse(queue.isResyncRequired());
        assertEquals(1, queue.droppedUnits());
        assertEquals(4L, queue.droppedBytes());
    }

    @Test
    public void referenceTooLargeAu_entersResync() {
        BoundedAccessUnitQueue queue = queue(8L, 4, OverflowAction.DROP_UNTIL_JOIN_POINT);
        MutableClock clock = new MutableClock();
        queue.offer(joinAu(1, 4), clock.nowMs());
        queue.offer(plainAu(2, 4), clock.nowMs());

        QueueOfferResult result = queue.offer(joinAu(3, 9), clock.nowMs());

        assertEquals(QueueOfferResult.REJECTED_TOO_LARGE, result);
        assertEquals(0, queue.size());
        assertEquals(0L, queue.bytes());
        assertTrue(queue.isResyncRequired());
        assertEquals(2, queue.droppedUnits());
        assertEquals(8L, queue.droppedBytes());
    }

    @Test
    public void keepLatestOverflow_keepsNewestWithoutResync() {
        BoundedAccessUnitQueue queue = queue(64L, 1, OverflowAction.KEEP_LATEST_FRAME);
        MutableClock clock = new MutableClock();
        EncodedAccessUnit older = joinAu(1, 4);
        EncodedAccessUnit newest = joinAu(2, 6);
        assertEquals(QueueOfferResult.ACCEPTED, queue.offer(older, clock.nowMs()));

        QueueOfferResult result = queue.offer(newest, clock.nowMs());

        assertEquals(QueueOfferResult.ACCEPTED_KEPT_LATEST, result);
        assertEquals(1, queue.size());
        assertEquals(6L, queue.bytes());
        assertFalse(queue.isResyncRequired());
        assertEquals(1, queue.droppedUnits());
        assertEquals(4L, queue.droppedBytes());

        QueuePollResult polled = queue.poll(clock.nowMs());
        assertSame(newest, polled.getAccessUnit());
        assertFalse(polled.isResyncRequired());
        assertEquals(0, queue.size());
        assertEquals(0L, queue.bytes());
    }

    @Test
    public void keepLatestExpiry_dropsOldWithoutResync() {
        BoundedAccessUnitQueue queue = queue(64L, 4, OverflowAction.KEEP_LATEST_FRAME);
        MutableClock clock = new MutableClock();
        EncodedAccessUnit older = joinAu(1, 4);
        queue.offer(older, clock.nowMs());
        clock.advance(80L);
        EncodedAccessUnit fresh = joinAu(2, 5);
        queue.offer(fresh, clock.nowMs());

        clock.advance(70L);
        QueuePollResult polled = queue.poll(clock.nowMs());

        assertSame(fresh, polled.getAccessUnit());
        assertFalse(polled.isResyncRequired());
        assertFalse(queue.isResyncRequired());
        assertEquals(0, queue.size());
        assertEquals(0L, queue.bytes());
        assertEquals(1, queue.droppedUnits());
        assertEquals(4L, queue.droppedBytes());
    }

    @Test
    public void referenceOverflow_nonJoinClearsAndRequiresResync() {
        BoundedAccessUnitQueue queue = queue(64L, 1, OverflowAction.DROP_UNTIL_JOIN_POINT);
        MutableClock clock = new MutableClock();
        EncodedAccessUnit resident = joinAu(1, 4);
        assertEquals(QueueOfferResult.ACCEPTED, queue.offer(resident, clock.nowMs()));

        QueueOfferResult result = queue.offer(plainAu(2, 5), clock.nowMs());

        assertEquals(QueueOfferResult.REJECTED_NOT_JOIN_POINT, result);
        assertEquals(0, queue.size());
        assertEquals(0L, queue.bytes());
        assertTrue(queue.isResyncRequired());
        assertEquals(1, queue.droppedUnits());
        assertEquals(4L, queue.droppedBytes());
    }

    @Test
    public void referenceOverflow_joinAcceptedAfterResync() {
        BoundedAccessUnitQueue queue = queue(64L, 1, OverflowAction.DROP_UNTIL_JOIN_POINT);
        MutableClock clock = new MutableClock();
        queue.offer(joinAu(1, 4), clock.nowMs());
        assertEquals(QueueOfferResult.REJECTED_NOT_JOIN_POINT,
                queue.offer(plainAu(2, 5), clock.nowMs()));

        EncodedAccessUnit joinPoint = joinAu(3, 6);
        QueueOfferResult result = queue.offer(joinPoint, clock.nowMs());

        assertEquals(QueueOfferResult.ACCEPTED_AFTER_RESYNC, result);
        assertEquals(1, queue.size());
        assertEquals(6L, queue.bytes());

        QueuePollResult polled = queue.poll(clock.nowMs());
        assertSame(joinPoint, polled.getAccessUnit());
        assertFalse(polled.isResyncRequired());
        assertFalse(queue.isResyncRequired());
        assertEquals(0, queue.size());
    }

    @Test
    public void referenceExpiry_clearsAndRequiresResync() {
        BoundedAccessUnitQueue queue = queue(64L, 4, OverflowAction.DROP_UNTIL_JOIN_POINT);
        MutableClock clock = new MutableClock();
        queue.offer(joinAu(1, 4), clock.nowMs());
        clock.advance(80L);
        queue.offer(plainAu(2, 5), clock.nowMs());

        clock.advance(70L);
        QueuePollResult polled = queue.poll(clock.nowMs());

        assertNull(polled.getAccessUnit());
        assertTrue(polled.isResyncRequired());
        assertTrue(queue.isResyncRequired());
        assertEquals(0, queue.size());
        assertEquals(0L, queue.bytes());
        assertEquals(2, queue.droppedUnits());
        assertEquals(9L, queue.droppedBytes());
    }

    @Test
    public void poll_returnsFifoAndDecrementsBytes() {
        BoundedAccessUnitQueue queue = queue(64L, 4, OverflowAction.KEEP_LATEST_FRAME);
        MutableClock clock = new MutableClock();
        EncodedAccessUnit first = joinAu(1, 3);
        EncodedAccessUnit second = plainAu(2, 5);
        queue.offer(first, clock.nowMs());
        queue.offer(second, clock.nowMs());
        assertEquals(8L, queue.bytes());

        clock.advance(10L);
        QueuePollResult firstPoll = queue.poll(clock.nowMs());
        assertSame(first, firstPoll.getAccessUnit());
        assertFalse(firstPoll.isResyncRequired());
        assertEquals(5L, queue.bytes());

        QueuePollResult secondPoll = queue.poll(clock.nowMs());
        assertSame(second, secondPoll.getAccessUnit());
        assertFalse(secondPoll.isResyncRequired());
        assertEquals(0L, queue.bytes());

        QueuePollResult emptyPoll = queue.poll(clock.nowMs());
        assertNull(emptyPoll.getAccessUnit());
        assertFalse(emptyPoll.isResyncRequired());
        assertEquals(0, queue.size());
    }

    @Test
    public void clear_resetsSizeAndResyncFlagButKeepsDroppedCounters() {
        BoundedAccessUnitQueue queue = queue(64L, 1, OverflowAction.DROP_UNTIL_JOIN_POINT);
        MutableClock clock = new MutableClock();
        queue.offer(joinAu(1, 4), clock.nowMs());
        assertEquals(QueueOfferResult.REJECTED_NOT_JOIN_POINT,
                queue.offer(plainAu(2, 5), clock.nowMs()));
        assertTrue(queue.isResyncRequired());
        assertEquals(1, queue.droppedUnits());
        assertEquals(4L, queue.droppedBytes());

        queue.clear();

        assertEquals(0, queue.size());
        assertEquals(0L, queue.bytes());
        assertFalse(queue.isResyncRequired());
        assertEquals(1, queue.droppedUnits());
        assertEquals(4L, queue.droppedBytes());
    }

    private static BoundedAccessUnitQueue queue(long maxBytes, int maxUnits,
                                                OverflowAction action) {
        return new BoundedAccessUnitQueue(
                new StreamQueueLimits(maxBytes, maxUnits, MAX_AGE_MS), action);
    }

    private static EncodedAccessUnit joinAu(long sequence, int payloadLength) {
        return accessUnit(sequence, true, payloadLength);
    }

    private static EncodedAccessUnit plainAu(long sequence, int payloadLength) {
        return accessUnit(sequence, false, payloadLength);
    }

    private static EncodedAccessUnit accessUnit(long sequence, boolean joinPoint,
                                                int payloadLength) {
        AccessUnitFlags flags = joinPoint
                ? AccessUnitFlags.of(true, true, false)
                : AccessUnitFlags.none();
        JoinInfo joinInfo = joinPoint
                ? new JoinInfo(CONFIG_EPOCH, Collections.singletonList(
                        new CodecConfigPart("csd-0", new byte[]{0x01})))
                : null;
        return new EncodedAccessUnit(STREAM_KEY, CONFIG_EPOCH, sequence, sequence * 1_000L,
                EncodedAccessUnit.DTS_UNSET, flags, joinInfo, null,
                payload(payloadLength, (byte) sequence));
    }

    private static byte[] payload(int length, byte fill) {
        byte[] payload = new byte[length];
        Arrays.fill(payload, fill);
        return payload;
    }
}
