package com.obby.android.localscreenshare.transport;

import org.junit.Test;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link MediaChannelCloser}.
 *
 * <p>The closer exists because a TLS socket close is a network write: on the main thread it trips
 * {@code StrictMode} and kills the process, which is what a device round caught while the viewer
 * was tearing down a channel. The tests assert the policy that prevents it - the caller never
 * performs the close itself, the close is still performed exactly once, and a close that never
 * returns cannot keep the process alive.</p>
 */
public class MediaChannelCloserTest {

    @Test
    public void close_isDispatchedInsteadOfRunOnTheCallingThread() {
        RecordingExecutor executor = new RecordingExecutor();
        RecordingCloseable target = new RecordingCloseable();
        MediaChannelCloser closer = new MediaChannelCloser(executor);

        closer.closeQuietly(target);

        assertFalse("the caller must not perform the close itself", target.isClosed());
        assertEquals(1, executor.pending());
        executor.runAll();
        assertTrue(target.isClosed());
        assertEquals(1, target.closeCount());
    }

    @Test
    public void aRefusedTask_stillClosesTheSocket() {
        MediaChannelCloser closer = new MediaChannelCloser(task -> {
            throw new RejectedExecutionException("pool is shutting down");
        });
        RecordingCloseable target = new RecordingCloseable();

        closer.closeQuietly(target);

        assertTrue("a refused close would leak the socket", target.isClosed());
    }

    @Test
    public void aBrokenClose_isSwallowed() {
        MediaChannelCloser closer = new MediaChannelCloser(Runnable::run);

        closer.closeQuietly(() -> {
            throw new IOException("peer already gone");
        });
    }

    @Test
    public void aNullTarget_isANoOp() {
        new MediaChannelCloser(Runnable::run).closeQuietly(null);
    }

    @Test
    public void theSharedCloser_closesOnADaemonThread() throws Exception {
        AtomicReference<Thread> thread = new AtomicReference<>();
        CountDownLatch closed = new CountDownLatch(1);

        MediaChannelCloser.daemon().closeQuietly(() -> {
            thread.set(Thread.currentThread());
            closed.countDown();
        });

        assertTrue("the deferred close never ran", closed.await(2L, TimeUnit.SECONDS));
        assertNotNull(thread.get());
        assertNotSame(Thread.currentThread(), thread.get());
        assertTrue("a close that blocks must not keep the process alive",
                thread.get().isDaemon());
    }

    /**
     * Executor that queues tasks until the test runs them, so the dispatch itself is observable.
     */
    private static final class RecordingExecutor implements Executor {

        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(final Runnable command) {
            tasks.add(command);
        }

        int pending() {
            return tasks.size();
        }

        void runAll() {
            for (Runnable task : new ArrayList<>(tasks)) {
                task.run();
            }
        }
    }

    /**
     * Closeable that records whether and how often it was closed.
     */
    private static final class RecordingCloseable implements Closeable {

        private volatile boolean closed;

        private int closeCount;

        @Override
        public void close() {
            closed = true;
            closeCount++;
        }

        boolean isClosed() {
            return closed;
        }

        int closeCount() {
            return closeCount;
        }
    }
}
