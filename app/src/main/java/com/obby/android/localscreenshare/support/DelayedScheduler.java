package com.obby.android.localscreenshare.support;

import androidx.annotation.NonNull;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Cancellable delayed execution used by the discovery timing state machines.
 *
 * <p>Announcement bursts, heartbeats, goodbyes and Register retries are all "run this once after
 * that delay" decisions. The interface keeps those decisions out of the clock: production code
 * schedules on a single daemon thread, and JVM tests advance a fake scheduler step by step, so the
 * exact sequence is asserted without sleeping.</p>
 *
 * <p>Implementations must run each task at most once and must tolerate cancelling an already
 * finished task. {@link #shutdown()} releases the underlying executor; it is only needed by
 * owners that create a scheduler for a bounded lifetime.</p>
 */
public interface DelayedScheduler {

    /** Returns a scheduler backed by one daemon thread named {@code threadName}. */
    @NonNull
    static DelayedScheduler newSingleThread(@NonNull final String threadName) {
        final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
        return new DelayedScheduler() {
            @Override
            @NonNull
            public Cancellable schedule(final long delayMs, @NonNull final Runnable task) {
                final ScheduledFuture<?> future =
                    executor.schedule(task, Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
                return () -> future.cancel(false);
            }

            @Override
            public void shutdown() {
                executor.shutdownNow();
            }
        };
    }

    /** Runs {@code task} once after {@code delayMs}; a negative delay runs it as soon as possible. */
    @NonNull
    Cancellable schedule(long delayMs, @NonNull Runnable task);

    /** Stops accepting tasks and releases the executor; pending tasks are discarded. */
    void shutdown();

    /** Handle of one scheduled task. */
    @FunctionalInterface
    interface Cancellable {
        /** Prevents the task from running; a no-op when it already ran. */
        void cancel();
    }
}
