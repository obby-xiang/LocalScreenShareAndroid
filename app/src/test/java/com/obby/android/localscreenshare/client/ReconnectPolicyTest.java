package com.obby.android.localscreenshare.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for {@link ReconnectPolicy}.
 *
 * <p>The cases pin the schedule the viewer offers: the first retry is immediate, the following
 * delays double up to eight seconds, the sixth failure is not offered a retry, and a successful
 * connection restarts the schedule. The concurrency case checks that simultaneous callers stay
 * inside the schedule instead of running off its end.</p>
 */
public class ReconnectPolicyTest {

    private static final List<Long> SCHEDULE_MS = Arrays.asList(
        0L, 1_000L, 2_000L, 4_000L, 8_000L);

    @Test
    public void firstRetryIsImmediate() {
        final ReconnectPolicy policy = new ReconnectPolicy();

        assertTrue(policy.canRetry());
        assertEquals(0L, policy.nextDelayMs());
        assertEquals(1, policy.getAttempts());
    }

    @Test
    public void delaysIncreaseAndCapAtEightSeconds() {
        final ReconnectPolicy policy = new ReconnectPolicy();
        final List<Long> delays = new ArrayList<>();

        for (int attempt = 0; attempt < 6; attempt++) {
            delays.add(policy.nextDelayMs());
        }

        assertEquals(Arrays.asList(0L, 1_000L, 2_000L, 4_000L, 8_000L, 8_000L), delays);
    }

    @Test
    public void sixthFailureIsNotOfferedARetry() {
        final ReconnectPolicy policy = new ReconnectPolicy();

        for (int attempt = 0; attempt < ReconnectPolicy.MAX_ATTEMPTS; attempt++) {
            assertTrue("attempt " + attempt + " must be offered",
                policy.canRetry());
            policy.nextDelayMs();
        }

        assertFalse(policy.canRetry());
        assertEquals(ReconnectPolicy.MAX_ATTEMPTS, policy.getAttempts());
    }

    @Test
    public void resetReturnsToTheFirstDelay() {
        final ReconnectPolicy policy = new ReconnectPolicy();
        policy.nextDelayMs();
        policy.nextDelayMs();
        policy.nextDelayMs();

        policy.reset();

        assertEquals(0, policy.getAttempts());
        assertTrue(policy.canRetry());
        assertEquals(0L, policy.nextDelayMs());
    }

    @Test
    public void resetDoesNotChangeAnAlreadyReturnedDelay() {
        final ReconnectPolicy policy = new ReconnectPolicy();
        policy.nextDelayMs();
        policy.nextDelayMs();
        final long thirdDelay = policy.nextDelayMs();

        policy.reset();

        assertEquals(2_000L, thirdDelay);
        assertEquals(0L, policy.nextDelayMs());
    }

    @Test
    public void concurrentCallsStayWithinTheSchedule() throws InterruptedException {
        final int threadCount = 8;
        final int callsPerThread = 50;
        final ReconnectPolicy policy = new ReconnectPolicy();
        final List<Long> delays = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch ready = new CountDownLatch(threadCount);
        final CountDownLatch go = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final Thread worker = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                    for (int call = 0; call < callsPerThread; call++) {
                        delays.add(policy.nextDelayMs());
                        policy.canRetry();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "reconnect-policy-" + i);
            worker.setDaemon(true);
            worker.start();
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));

        assertEquals(threadCount * callsPerThread, delays.size());
        final Set<Long> allowed = new HashSet<>(SCHEDULE_MS);
        for (long delay : delays) {
            assertTrue("delay outside the schedule: " + delay, allowed.contains(delay));
        }
        assertEquals(ReconnectPolicy.MAX_ATTEMPTS, policy.getAttempts());
        assertFalse(policy.canRetry());
    }
}
