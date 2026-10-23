package com.obby.android.localscreenshare.transport;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Closes the socket of a media channel off the thread that asked for it.
 *
 * <p><b>Why a thread hop.</b> Closing a stream that is not plain is not a local operation: an
 * {@code SSLSocket.close()} records a {@code close_notify} and writes it to the peer. On the main
 * thread that write trips {@code StrictMode} and takes the process down with
 * {@code NetworkOnMainThreadException}. A device round caught exactly that on the viewer: the server
 * stopped sharing, the client's teardown closed the channel on the main thread, and the app died on
 * its most common disconnect path. Every close of a channel socket therefore only marks the channel
 * closed and hands the socket here, so no caller ever performs that write itself.</p>
 *
 * <p><b>Threads.</b> The shared instance closes on short-lived daemon threads, one per close: a
 * {@code close_notify} that blocks because the peer is already gone then neither delays the next
 * close nor keeps the process alive. A caller that has to observe the close installs its own
 * executor, which is also how the dispatch is asserted in a test.</p>
 */
public final class MediaChannelCloser {

    private static final MediaChannelCloser SHARED =
            new MediaChannelCloser(Executors.newCachedThreadPool(new DaemonThreadFactory()));

    private final Executor executor;

    /**
     * Creates a closer that runs every close on {@code executor}.
     *
     * @param executor executor that performs the closes; required
     * @throws IllegalArgumentException if {@code executor} is null
     */
    public MediaChannelCloser(final Executor executor) {
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        }
        this.executor = executor;
    }

    /**
     * Returns the closer shared by every media channel of the process.
     */
    public static MediaChannelCloser daemon() {
        return SHARED;
    }

    /**
     * Closes {@code target} on the executor; returns without waiting and never throws.
     *
     * <p>An executor that refuses the task still gets the socket closed on the calling thread,
     * because a refused close would leak the socket and the channel it belongs to.</p>
     *
     * @param target socket to close; null is a no-op
     */
    public void closeQuietly(final Closeable target) {
        if (target == null) {
            return;
        }
        try {
            executor.execute(() -> closeNow(target));
        } catch (RuntimeException e) {
            closeNow(target);
        }
    }

    private static void closeNow(final Closeable target) {
        try {
            target.close();
        } catch (IOException ignored) {
            // Closing an already broken socket is not an event.
        }
    }

    /**
     * Names one daemon thread per close, so a capture can attribute a slow close.
     */
    private static final class DaemonThreadFactory implements ThreadFactory {

        private final AtomicInteger mNext = new AtomicInteger();

        @Override
        public Thread newThread(final Runnable runnable) {
            final Thread thread = new Thread(runnable,
                    "lss-media-close-" + mNext.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
