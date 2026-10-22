package com.obby.android.localscreenshare.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link CaptureThrottlePolicy}.
 *
 * <p>The cases pin the frame-rate gate the capture thread applies: the first frame always passes,
 * a frame inside the interval is dropped, a frame at or beyond the interval passes, and a source
 * clock that moves backwards is not allowed to mute the capture. The guard cases cover a
 * non-positive configured rate and duplicate callbacks for the same image.</p>
 */
public class CaptureThrottlePolicyTest {

    private static final long MILLIS = 1_000_000L;

    @Test
    public void firstFrame_isAccepted() {
        assertTrue(CaptureThrottlePolicy.shouldAccept(1_000L * MILLIS, -1L, 30));
        assertTrue(CaptureThrottlePolicy.shouldAccept(0L, -1L, 60));
    }

    @Test
    public void frameInsideTheInterval_isDropped() {
        final long last = 10_000L * MILLIS;

        assertFalse(CaptureThrottlePolicy.shouldAccept(last + 10L * MILLIS, last, 30));
        assertFalse(CaptureThrottlePolicy.shouldAccept(last + 33L * MILLIS, last, 30));
    }

    @Test
    public void frameAtOrBeyondTheInterval_isAccepted() {
        final long last = 10_000L * MILLIS;
        final long interval = 1_000_000_000L / 30;

        assertTrue(CaptureThrottlePolicy.shouldAccept(last + interval, last, 30));
        assertTrue(CaptureThrottlePolicy.shouldAccept(last + 100L * MILLIS, last, 30));
    }

    @Test
    public void nonPositiveFrameRate_countsAsOneFramePerSecond() {
        final long last = 5_000L * MILLIS;

        assertFalse(CaptureThrottlePolicy.shouldAccept(last + 500L * MILLIS, last, 0));
        assertFalse(CaptureThrottlePolicy.shouldAccept(last + 500L * MILLIS, last, -30));
        assertTrue(CaptureThrottlePolicy.shouldAccept(last + 1_000L * MILLIS, last, 0));
    }

    @Test
    public void backwardsTimestamp_isAccepted() {
        final long last = 10_000L * MILLIS;

        // A source clock that restarted must not mute the capture until it catches up.
        assertTrue(CaptureThrottlePolicy.shouldAccept(1L * MILLIS, last, 30));
        assertTrue(CaptureThrottlePolicy.shouldAccept(0L, last, 60));
    }

    @Test
    public void equalTimestamp_isAcceptedOnlyOnce() {
        final long timestamp = 42_000L * MILLIS;

        assertTrue(CaptureThrottlePolicy.shouldAccept(timestamp, -1L, 60));
        assertFalse(CaptureThrottlePolicy.shouldAccept(timestamp, timestamp, 60));
        assertFalse(CaptureThrottlePolicy.shouldAccept(timestamp, timestamp, 1));
    }
}
