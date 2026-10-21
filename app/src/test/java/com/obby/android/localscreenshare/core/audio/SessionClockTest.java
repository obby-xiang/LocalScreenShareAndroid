package com.obby.android.localscreenshare.core.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for {@link SessionClock}.
 */
public class SessionClockTest {

    @Test
    public void unanchoredClockMapsNothing() {
        SessionClock clock = new SessionClock();

        assertFalse(clock.isAnchored());
        assertEquals(SessionClock.UNSET, clock.map(1_000L));
        assertEquals(SessionClock.UNSET, clock.unmap(1_000L));
        assertEquals(SessionClock.UNSET, clock.getAnchorPtsUs());
    }

    @Test
    public void firstAnchorWins() {
        SessionClock clock = new SessionClock();

        assertTrue(clock.anchorIfUnset(1_000L, 5_000L));
        assertFalse(clock.anchorIfUnset(2_000L, 9_000L));

        assertEquals(1_000L, clock.getAnchorPtsUs());
        assertEquals(5_000L, clock.getAnchorNs());
        assertEquals(5_000L, clock.map(1_000L));
    }

    @Test
    public void mappingIsLinearInMicroseconds() {
        SessionClock clock = new SessionClock();
        clock.anchorIfUnset(1_000_000L, 5_000_000_000L);

        assertEquals(5_000_000_000L, clock.map(1_000_000L));
        assertEquals(5_500_000_000L, clock.map(1_500_000L));
        assertEquals(4_500_000_000L, clock.map(500_000L));
    }

    @Test
    public void unmapInvertsTheMapping() {
        SessionClock clock = new SessionClock();
        clock.anchorIfUnset(1_000_000L, 5_000_000_000L);

        assertEquals(1_500_000L, clock.unmap(5_500_000_000L));
        assertEquals(500_000L, clock.unmap(4_500_000_000L));
    }

    @Test
    public void resetAllowsANewAnchor() {
        SessionClock clock = new SessionClock();
        clock.anchorIfUnset(1_000L, 5_000L);

        clock.reset();

        assertFalse(clock.isAnchored());
        assertTrue(clock.anchorIfUnset(7_000L, 9_000L));
        assertEquals(9_000L, clock.map(7_000L));
    }

    @Test
    public void concurrentAnchoringSucceedsExactlyOnce() throws Exception {
        SessionClock clock = new SessionClock();
        AtomicInteger winners = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        int threads = 8;
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            final long pts = i;
            Thread thread = new Thread(() -> {
                try {
                    start.await(1_000L, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (clock.anchorIfUnset(pts, 1_000L + pts)) {
                    winners.incrementAndGet();
                }
                done.countDown();
            });
            thread.start();
        }
        start.countDown();
        assertTrue(done.await(2_000L, TimeUnit.MILLISECONDS));

        assertEquals(1, winners.get());
        assertTrue(clock.isAnchored());
        assertEquals(clock.getAnchorNs(), clock.map(clock.getAnchorPtsUs()));
    }
}
