package com.obby.android.localscreenshare.client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link KeyFrameRequestThrottle}.
 */
public class KeyFrameRequestThrottleTest {

    @Test
    public void firstRequestAlwaysPasses() {
        assertTrue(new KeyFrameRequestThrottle().tryAcquire(0L));
        assertTrue(new KeyFrameRequestThrottle().tryAcquire(Long.MAX_VALUE));
    }

    @Test
    public void requestInsideTheIntervalIsRejected() {
        KeyFrameRequestThrottle throttle = new KeyFrameRequestThrottle(1000L);

        assertTrue(throttle.tryAcquire(5_000L));
        assertFalse(throttle.tryAcquire(5_001L));
        assertFalse(throttle.tryAcquire(5_999L));
        assertTrue(throttle.tryAcquire(6_000L));
    }

    @Test
    public void rejectedRequestDoesNotExtendTheInterval() {
        KeyFrameRequestThrottle throttle = new KeyFrameRequestThrottle(1000L);

        assertTrue(throttle.tryAcquire(1_000L));
        assertFalse(throttle.tryAcquire(1_500L));
        assertTrue("the interval is measured from the granted request", throttle.tryAcquire(2_000L));
    }

    @Test
    public void clockMovedBackwards_grantsTheRequest() {
        KeyFrameRequestThrottle throttle = new KeyFrameRequestThrottle(1000L);

        assertTrue(throttle.tryAcquire(10_000L));
        assertTrue(throttle.tryAcquire(9_000L));
    }

    @Test
    public void nonPositiveIntervalIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new KeyFrameRequestThrottle(0L));
        assertThrows(IllegalArgumentException.class, () -> new KeyFrameRequestThrottle(-1L));
    }
}
