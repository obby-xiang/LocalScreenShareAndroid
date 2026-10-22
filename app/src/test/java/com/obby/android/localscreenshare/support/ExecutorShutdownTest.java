package com.obby.android.localscreenshare.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for {@link ExecutorShutdown}.
 *
 * <p>The cases pin the contract the viewer stop relies on: a cooperative task ends within the
 * budget, a task that ignores the interrupt cannot hold the caller past the budget, an interrupt of
 * the caller is preserved and reported as a failure to terminate, and a null or finished executor
 * is accepted without blocking. Repeated calls are covered because a service can reach its stop path
 * more than once.</p>
 */
public class ExecutorShutdownTest {

    @Test
    public void cooperativeTask_terminatesWithinBudget() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final CountDownLatch started = new CountDownLatch(1);
        executor.execute(() -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));

        assertTrue(ExecutorShutdown.shutdownAndAwait(executor, 2_000L));
        assertTrue(executor.isTerminated());
    }

    @Test
    public void taskIgnoringInterrupt_reportsFalseAfterBudget() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> awaitRelease(started, release));
        assertTrue(started.await(2, TimeUnit.SECONDS));

        try {
            assertFalse(ExecutorShutdown.shutdownAndAwait(executor, 50L));
        } finally {
            release.countDown();
        }

        // The second call is the same idempotent operation on a pool that can now terminate.
        assertTrue(ExecutorShutdown.shutdownAndAwait(executor, 2_000L));
    }

    @Test
    public void interruptedCaller_keepsTheFlagAndReportsFalse() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> awaitRelease(started, release));
        assertTrue(started.await(2, TimeUnit.SECONDS));

        Thread.currentThread().interrupt();
        try {
            assertFalse(ExecutorShutdown.shutdownAndAwait(executor, 2_000L));
            assertTrue("the caller's interrupt flag must survive the wait",
                Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
            release.countDown();
            ExecutorShutdown.shutdownAndAwait(executor, 2_000L);
        }
    }

    @Test
    public void nullOrTerminatedExecutor_reportsTrue() {
        assertTrue(ExecutorShutdown.shutdownAndAwait(null, 100L));

        final ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.shutdownNow();

        assertTrue(ExecutorShutdown.shutdownAndAwait(executor, 2_000L));
        assertTrue(ExecutorShutdown.shutdownAndAwait(executor, 2_000L));
    }

    @Test
    public void zeroBudgetAndRepeatedCalls_neverThrow() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> awaitRelease(started, release));
        assertTrue(started.await(2, TimeUnit.SECONDS));

        try {
            // A zero budget only issues the shutdown; the running task decides when it ends.
            assertFalse(ExecutorShutdown.shutdownAndAwait(executor, 0L));
            assertFalse(ExecutorShutdown.shutdownAndAwait(executor, -1L));
        } finally {
            release.countDown();
        }

        assertTrue(ExecutorShutdown.shutdownAndAwait(executor, 2_000L));
        assertTrue(ExecutorShutdown.shutdownAndAwait(executor, 2_000L));
    }

    private static void awaitRelease(final CountDownLatch started, final CountDownLatch release) {
        started.countDown();
        while (release.getCount() > 0L) {
            try {
                release.await();
            } catch (InterruptedException ignored) {
                // Deliberately ignores the interrupt: this models a task that keeps working.
            }
        }
    }
}
