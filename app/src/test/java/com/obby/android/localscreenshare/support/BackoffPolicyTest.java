package com.obby.android.localscreenshare.support;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class BackoffPolicyTest {

    @Test
    public void defaultsWithoutJitterFollowsDocumentedScheduleAndCaps() {
        BackoffPolicy policy = BackoffPolicy.defaultsWithoutJitter();

        assertEquals(1_000L, policy.nextDelayMs());
        assertEquals(2_000L, policy.nextDelayMs());
        assertEquals(4_000L, policy.nextDelayMs());
        assertEquals(8_000L, policy.nextDelayMs());
        assertEquals(16_000L, policy.nextDelayMs());
        assertEquals(30_000L, policy.nextDelayMs());
        assertEquals(30_000L, policy.nextDelayMs());
        assertEquals(7, policy.attempts());
    }

    @Test
    public void resetReturnsToFirstStep() {
        BackoffPolicy policy = BackoffPolicy.withoutJitter(100L, 200L);
        policy.nextDelayMs();
        policy.nextDelayMs();
        assertEquals(2, policy.attempts());

        policy.reset();

        assertEquals(0, policy.attempts());
        assertEquals(100L, policy.nextDelayMs());
    }

    @Test
    public void customScheduleIsUsedWithoutJitter() {
        BackoffPolicy policy = BackoffPolicy.withoutJitter(5L, 10L);

        assertEquals(5L, policy.nextDelayMs());
        assertEquals(10L, policy.nextDelayMs());
        assertEquals(10L, policy.nextDelayMs());
    }

    @Test
    public void zeroDelayIsRaisedToOneMillisecond() {
        BackoffPolicy policy = BackoffPolicy.withoutJitter(0L);

        assertEquals(1L, policy.nextDelayMs());
    }

    @Test
    public void jitterStaysWithinConfiguredRatio() {
        BackoffPolicy policy = BackoffPolicy.withDelays(new long[] {1_000L},
            BackoffPolicy.randomJitter(0.1d, new Random(42L)));

        for (int i = 0; i < 50; i++) {
            long delayMs = policy.nextDelayMs();
            assertTrue("delay must not go below -10%: " + delayMs, delayMs >= 900L);
            assertTrue("delay must not exceed +10%: " + delayMs, delayMs <= 1_100L);
        }
    }

    @Test
    public void defaultsUsesJitterButStaysInRange() {
        BackoffPolicy policy = BackoffPolicy.defaults();

        long delayMs = policy.nextDelayMs();

        assertTrue(delayMs >= 900L && delayMs <= 1_100L);
    }

    @Test
    public void emptyScheduleIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> BackoffPolicy.withDelays(new long[0], BackoffPolicy.NO_JITTER));
    }

    @Test
    public void negativeDelayIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> BackoffPolicy.withDelays(new long[] {1L, -1L}, BackoffPolicy.NO_JITTER));
    }

    @Test
    public void invalidJitterRatioIsRejected() {
        Random random = new Random(1L);

        assertThrows(IllegalArgumentException.class, () -> BackoffPolicy.randomJitter(-0.1d, random));
        assertThrows(IllegalArgumentException.class, () -> BackoffPolicy.randomJitter(1d, random));
    }
}
