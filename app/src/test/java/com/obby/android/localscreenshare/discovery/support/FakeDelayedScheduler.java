package com.obby.android.localscreenshare.discovery.support;

import androidx.annotation.NonNull;

import com.obby.android.localscreenshare.support.DelayedScheduler;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic {@link DelayedScheduler} that runs only when the test advances virtual time.
 *
 * <p>Tasks scheduled during a run are picked up by the same advance loop, so a state machine can
 * chain its next step and the test still controls every transition. Cancelling a task marks it
 * and it is skipped; the queue is pruned when it runs empty.</p>
 */
public final class FakeDelayedScheduler implements DelayedScheduler {

    @NonNull
    private final List<Task> mTasks = new ArrayList<>();

    private long mNowMs;

    private boolean mShutdown;

    @Override
    @NonNull
    public synchronized Cancellable schedule(final long delayMs, @NonNull final Runnable task) {
        final Task scheduled = new Task(mNowMs + Math.max(0L, delayMs), task);
        if (!mShutdown) {
            mTasks.add(scheduled);
        }
        return () -> scheduled.cancelled = true;
    }

    @Override
    public synchronized void shutdown() {
        mShutdown = true;
        mTasks.clear();
    }

    /** Advances virtual time and runs every task that becomes due, in chronological order. */
    public synchronized void advance(final long deltaMs) {
        final long targetMs = mNowMs + deltaMs;
        while (true) {
            Task next = null;
            for (final Task task : mTasks) {
                if (task.cancelled || task.executed || task.atMs > targetMs) {
                    continue;
                }
                if (next == null || task.atMs < next.atMs) {
                    next = task;
                }
            }
            if (next == null) {
                break;
            }
            mNowMs = Math.max(mNowMs, next.atMs);
            next.executed = true;
            next.runnable.run();
            mTasks.removeIf(task -> task.cancelled || task.executed);
        }
        mNowMs = targetMs;
    }

    /** Returns the current virtual time. */
    public synchronized long nowMs() {
        return mNowMs;
    }

    /** Returns the number of scheduled and not yet run tasks. */
    public synchronized int pendingCount() {
        return mTasks.size();
    }

    /** Returns whether {@link #shutdown()} was called. */
    public synchronized boolean isShutdown() {
        return mShutdown;
    }

    /** One scheduled callback. */
    private static final class Task {
        final long atMs;

        @NonNull
        final Runnable runnable;

        boolean cancelled;

        boolean executed;

        private Task(final long atMs, @NonNull final Runnable runnable) {
            this.atMs = atMs;
            this.runnable = runnable;
        }
    }
}
