package com.obby.android.localscreenshare.client;

import com.obby.android.localscreenshare.core.model.StreamFeedback;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Accumulates the viewer's queue backlog between two reports.
 *
 * <p>The hot path never allocates: {@link #onQueueDepth(int)}, {@link #onQueueWait(long)} and
 * {@link #onCatchUp()} only write a volatile maximum or increment one counter, so the decode thread
 * can call them once per dequeued task. Sampling happens at dequeue time and not on the enqueue
 * path, so the reading describes what the decoder actually had to wait for; the same point is used
 * for the catch-up sampler, which removes a one-frame ambiguity between depth and wait.</p>
 *
 * <p>{@link #sampleAndReset()} is the only allocating call and runs at most once per reporting
 * window. It returns and clears the peaks atomically enough for one producer: the decode path is a
 * single thread, and the report reader runs on the main thread. {@link #hasActivity()} means "the
 * window saw any queueing or catch-up", not "the viewer is congested": an occasional report on a
 * healthy stream is allowed, and the server's thresholds decide whether anything happens.</p>
 */
public final class BacklogFeedback {

    // Peaks of the current window. Written only by the single decode thread; the report reader
    // resets them after taking a snapshot.
    private volatile int mPeakDepth;

    private volatile long mPeakWaitNs;

    private final AtomicInteger mCatchUps = new AtomicInteger();

    /**
     * Records one dequeue-time queue depth.
     *
     * @param depth tasks still queued behind the one that just left; negative values are ignored
     */
    public void onQueueDepth(int depth) {
        if (depth > mPeakDepth) {
            mPeakDepth = depth;
        }
    }

    /**
     * Records one arrival-to-decode-start wait.
     *
     * @param waitNs wait in nanoseconds; negative values are ignored
     */
    public void onQueueWait(long waitNs) {
        if (waitNs > mPeakWaitNs) {
            mPeakWaitNs = waitNs;
        }
    }

    /**
     * Counts one catch-up episode that started in the current window.
     */
    public void onCatchUp() {
        mCatchUps.incrementAndGet();
    }

    /**
     * Returns whether the current window saw any queueing or catch-up.
     */
    public boolean hasActivity() {
        return mPeakDepth > 0 || mCatchUps.get() > 0;
    }

    /**
     * Takes the peaks of the current window, clears them and returns one report.
     *
     * <p>The queue wait is converted to whole milliseconds with {@link Math#round(double)}; the
     * client uptime is the monotonic process clock, used for diagnostics only.</p>
     *
     * @return a report; all-zero when nothing was recorded in the window
     */
    public StreamFeedback sampleAndReset() {
        final int depthPeak = mPeakDepth;
        mPeakDepth = 0;
        final long waitPeakNs = mPeakWaitNs;
        mPeakWaitNs = 0L;
        final int catchUps = mCatchUps.getAndSet(0);
        final long waitPeakMs = Math.round(waitPeakNs / 1_000_000d);
        final long uptimeMs = System.nanoTime() / 1_000_000L;
        return new StreamFeedback(depthPeak, catchUps,
                (int) Math.min(Integer.MAX_VALUE, Math.max(0L, waitPeakMs)), uptimeMs);
    }
}
