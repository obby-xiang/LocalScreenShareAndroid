package com.obby.android.localscreenshare.support;

import androidx.annotation.Nullable;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Bounded, idempotent teardown of an executor service.
 *
 * <p><b>Why bounded.</b> A viewer stop runs on the main thread, and the pools it releases are
 * exactly the ones that may still be busy with a decode, an audio write or a gRPC callback. Waiting
 * without a limit would let one stuck task hold the main thread for as long as it wants; waiting not
 * at all leaves a thread that keeps touching the session after the window is gone. The helper
 * therefore cancels the queued work with {@link ExecutorService#shutdownNow()} and then waits at
 * most the given budget.</p>
 *
 * <p><b>Self-calls.</b> {@link ExecutorService} offers no way to tell whether the calling thread
 * belongs to the pool. The wait is bounded, so a task that shuts down its own pool degrades to a
 * timeout instead of a deadlock; callers that cannot afford even that should pass a budget of
 * zero.</p>
 *
 * <p><b>Interrupt.</b> An interrupt during the wait is restored and reported as {@code false}: the
 * caller learns that the pool did not terminate in time without losing its own interrupt
 * status.</p>
 */
public final class ExecutorShutdown {

    private ExecutorShutdown() {
    }

    /**
     * Shuts the executor down and waits a bounded time for its tasks to finish.
     *
     * <p>Idempotent: a second call on an already terminated executor only re-issues the shutdown
     * and returns {@code true}. A {@code null} executor counts as already terminated.</p>
     *
     * @param executor  pool to terminate; null is accepted
     * @param timeoutMs bounded wait in milliseconds; zero or negative only initiates the shutdown
     * @return {@code true} when the pool is terminated or no pool was given, {@code false} when the
     *     budget elapsed first or the wait was interrupted
     */
    public static boolean shutdownAndAwait(@Nullable final ExecutorService executor,
                                           final long timeoutMs) {
        if (executor == null) {
            return true;
        }
        executor.shutdownNow();
        if (timeoutMs <= 0L) {
            return executor.isTerminated();
        }
        try {
            return executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
