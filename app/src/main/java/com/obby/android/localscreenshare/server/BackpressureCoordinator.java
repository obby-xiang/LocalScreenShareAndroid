package com.obby.android.localscreenshare.server;

import android.util.Log;

import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.core.codec.CodecIds;
import com.obby.android.localscreenshare.core.metrics.PipelineMetrics;
import com.obby.android.localscreenshare.core.model.StreamFeedback;
import com.obby.android.localscreenshare.core.stream.BackpressurePolicy;
import com.obby.android.localscreenshare.core.util.TimeSource;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Applies viewer backlog reports to the encoder on a dedicated thread.
 *
 * <p>The coordinator is the only place where a report can change a bitrate. {@link #offer} records
 * the newest report and schedules one pass; it never blocks and never calls a codec, because it runs
 * on the gRPC callback thread. The pass itself runs on one lazily created thread named
 * {@code lss-backpressure}, so an encoder that takes a while cannot stall control messages or a
 * drain.</p>
 *
 * <p><b>Newest value wins.</b> A report overwrites the pending one and a second pass is only
 * scheduled when none is in flight. Three reports in a row therefore cause one encoder call, with
 * the newest report, not three calls with stale ones.</p>
 *
 * <p><b>Single client only.</b> The coordinator acts only while exactly one client is attached and
 * the published codec is H.264. Any other situation is observed and skipped; if an override is
 * already in force it is restored to the ceiling, so a lowered stream never keeps serving a client
 * set that no longer qualifies. Rows in {@link BackpressurePolicy} explain the thresholds.</p>
 *
 * <p><b>External writes.</b> The policy writes only what a lower or a restore computed. Reading a
 * different bitrate back means somebody else wrote it - the user setting, a thermal tier, a
 * rotation or a size change - so the override is dropped without restoring and without retrying.
 * That is deliberate: the external value is the user's or another policy's decision, and fighting it
 * would make both oscillate.</p>
 *
 * <p><b>Failure and close.</b> An encoder exception is contained: state is unchanged, the skip
 * counter grows, and at most one warning line per {@value #WARN_INTERVAL_MS} ms is written. {@link
 * #close()} is idempotent and waits at most {@value #CLOSE_WAIT_MS} ms for the owned executor; it
 * never creates that executor just to shut it down. Offers after a close are no-ops.</p>
 *
 * <p>The class is not final so a test can observe the two log methods; production code always uses
 * the factory with the metrics scope of the session.</p>
 */
public class BackpressureCoordinator implements FeedbackSink {

    /**
     * No-op sink: never creates a thread, never writes a metric, never calls a codec.
     */
    public static final FeedbackSink NONE = new FeedbackSink() {
        @Override
        public void offer(StreamFeedback feedback, BitrateTarget target) {
        }

        @Override
        public void close() {
        }
    };

    private static final String TAG = "Backpressure";

    private static final String EXECUTOR_THREAD_NAME = "lss-backpressure";

    private static final long WARN_INTERVAL_MS = 60_000L;

    private static final long CLOSE_WAIT_MS = 1_000L;

    private final PipelineMetrics mMetrics;

    private final TimeSource mTimeSource;

    private final BooleanSupplier mEnabled;

    // Test seam; null means this instance owns the lazily created production executor.
    @Nullable
    private final Executor mExecutor;

    private final Object mExecutorLock = new Object();

    @Nullable
    private ExecutorService mOwnedExecutor;

    private final AtomicReference<Report> mLatest = new AtomicReference<>();

    private final AtomicBoolean mScheduled = new AtomicBoolean();

    private final AtomicBoolean mClosed = new AtomicBoolean();

    // State below is touched only by the executor thread.
    private boolean mOverridden;

    private int mCeilingBps;

    private long mLastAppliedBps;

    private long mLastActionMs;

    private int mClearStreak;

    private boolean mHasFailureLogged;

    private long mLastFailureLogMs;

    /**
     * Production factory.
     *
     * <p>The executor is created on the first offer, so a server that never receives a report has
     * no backpressure thread at all.</p>
     *
     * @param metrics    metric scope of the session; required
     * @param timeSource monotonic clock used for the action interval and log throttle; required
     * @param enabled    master switch, read before every pass; required
     * @return a coordinator whose executor belongs to it
     * @throws IllegalArgumentException if an argument is null
     */
    public static BackpressureCoordinator create(PipelineMetrics metrics, TimeSource timeSource,
                                                 BooleanSupplier enabled) {
        return new BackpressureCoordinator(metrics, timeSource, enabled, null);
    }

    /**
     * Creates a coordinator with an explicit executor.
     *
     * <p>The caller owns the executor: it may queue tasks and drain them by hand, which is what
     * makes "newest report wins" observable before and after a pass. {@link #close()} does not shut
     * an injected executor down.</p>
     *
     * @param metrics    metric scope of the session; required
     * @param timeSource monotonic clock used for the action interval and log throttle; required
     * @param enabled    master switch, read before every pass; required
     * @param executor   executor that runs the apply passes; null to let this instance own one
     * @throws IllegalArgumentException if a required argument is null
     */
    public BackpressureCoordinator(PipelineMetrics metrics, TimeSource timeSource,
                                   BooleanSupplier enabled, @Nullable Executor executor) {
        if (metrics == null) {
            throw new IllegalArgumentException("metrics must not be null");
        }
        if (timeSource == null) {
            throw new IllegalArgumentException("timeSource must not be null");
        }
        if (enabled == null) {
            throw new IllegalArgumentException("enabled must not be null");
        }
        mMetrics = metrics;
        mTimeSource = timeSource;
        mEnabled = enabled;
        mExecutor = executor;
        // The first report may act immediately; the interval only separates two actions.
        mLastActionMs = timeSource.nowMs() - BackpressurePolicy.MIN_ACTION_INTERVAL_MS;
    }

    @Override
    public void offer(StreamFeedback feedback, BitrateTarget target) {
        if (mClosed.get() || feedback == null || target == null) {
            return;
        }
        mLatest.set(new Report(feedback, target));
        if (mScheduled.compareAndSet(false, true)) {
            schedulePass();
        }
    }

    @Override
    public void close() {
        if (!mClosed.compareAndSet(false, true)) {
            return;
        }
        mLatest.set(null);
        final ExecutorService owned;
        synchronized (mExecutorLock) {
            owned = mOwnedExecutor;
            mOwnedExecutor = null;
        }
        if (owned == null) {
            return;
        }
        // Interrupt a pass that is inside an encoder call and drop anything still queued; then wait
        // for the thread to leave. The wait is bounded because a codec may ignore an interrupt.
        owned.shutdownNow();
        try {
            owned.awaitTermination(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void schedulePass() {
        try {
            executor().execute(this::runPass);
        } catch (RejectedExecutionException e) {
            // The owned executor was shut down by a concurrent close; nothing is left to run.
            mScheduled.set(false);
        }
    }

    private Executor executor() {
        if (mExecutor != null) {
            return mExecutor;
        }
        synchronized (mExecutorLock) {
            if (mOwnedExecutor == null) {
                mOwnedExecutor = Executors.newSingleThreadExecutor(runnable -> {
                    final Thread thread = new Thread(runnable, EXECUTOR_THREAD_NAME);
                    thread.setDaemon(true);
                    return thread;
                });
            }
            return mOwnedExecutor;
        }
    }

    private void runPass() {
        try {
            if (mClosed.get()) {
                return;
            }
            final Report report = mLatest.getAndSet(null);
            if (report != null && !mClosed.get()) {
                apply(report);
            }
        } finally {
            mScheduled.set(false);
            // A report that arrived while this pass ran must not be lost.
            if (!mClosed.get() && mLatest.get() != null && mScheduled.compareAndSet(false, true)) {
                schedulePass();
            }
        }
    }

    private void apply(Report report) {
        final BitrateTarget target = report.target;
        final long nowMs = mTimeSource.nowMs();
        final boolean isEnabled = mEnabled.getAsBoolean();
        final String codecId = target.getCodecId();
        final int sessionCount = target.getSessionCount();

        if (!isEnabled || codecId == null || !CodecIds.H264.equals(codecId) || sessionCount != 1) {
            final String reason;
            if (!isEnabled) {
                reason = "disabled";
            } else if (codecId == null) {
                reason = "codec-unknown";
            } else if (!CodecIds.H264.equals(codecId)) {
                reason = "codec";
            } else {
                reason = "multi-client";
            }
            if (mOverridden
                    && nowMs - mLastActionMs >= BackpressurePolicy.MIN_ACTION_INTERVAL_MS) {
                applyAction(BackpressurePolicy.Action.RESTORE, target.getTargetBitrateBps(),
                        target, report.feedback);
            } else {
                skip(reason);
            }
            return;
        }

        final int currentBps = target.getTargetBitrateBps();
        if (mOverridden && currentBps != mLastAppliedBps) {
            // Somebody else wrote the bitrate; that decision wins over the override. Do not restore
            // and do not retry, or the two writers would keep undoing each other.
            mOverridden = false;
            mCeilingBps = 0;
            mClearStreak = 0;
            logAction("apply: override cleared by an external write, read = " + currentBps
                    + ", applied = " + mLastAppliedBps);
            return;
        }
        if (currentBps <= 0) {
            skip("no-bitrate");
            return;
        }

        final BackpressurePolicy.Level level = BackpressurePolicy.classify(report.feedback);
        mClearStreak = level == BackpressurePolicy.Level.CLEAR ? mClearStreak + 1 : 0;
        final BackpressurePolicy.Action action = BackpressurePolicy.decide(
                mOverridden ? BackpressurePolicy.Action.LOWER : BackpressurePolicy.Action.NONE,
                currentBps, mOverridden ? mCeilingBps : 0, sessionCount, true, mClearStreak,
                nowMs - mLastActionMs);
        if (action == BackpressurePolicy.Action.NONE) {
            // A measured report with no action (interval, or one clear report) is not a skip.
            return;
        }
        applyAction(action, currentBps, target, report.feedback);
    }

    private void applyAction(BackpressurePolicy.Action action, int currentBps,
                             BitrateTarget target, StreamFeedback feedback) {
        final int ceilingBps = mOverridden ? mCeilingBps : currentBps;
        final int nextBps = BackpressurePolicy.targetBitrate(action, currentBps, ceilingBps);
        if (nextBps <= 0) {
            skip("invalid-target");
            return;
        }
        try {
            target.applyTargetBitrate(nextBps);
        } catch (RuntimeException e) {
            // The encoder refused; leave every piece of state as it was and retry on a later report.
            skip("encoder");
            logFailureLimited("apply: encoder refused " + action + ", from = " + currentBps
                    + ", to = " + nextBps + ", error = " + e);
            return;
        }

        if (action == BackpressurePolicy.Action.LOWER) {
            if (!mOverridden) {
                mCeilingBps = currentBps;
            }
            mOverridden = true;
        } else {
            mOverridden = false;
            mCeilingBps = 0;
        }
        mLastAppliedBps = nextBps;
        mLastActionMs = mTimeSource.nowMs();
        mClearStreak = 0;
        mMetrics.increment(PipelineMetrics.Counter.BACKPRESSURE_APPLIED);
        mMetrics.setGauge(PipelineMetrics.Gauge.BACKPRESSURE_TARGET_BITRATE, nextBps);
        logAction("apply: action = " + action + ", from = " + currentBps + ", to = " + nextBps
                + ", depth=" + feedback.getQueueDepthPeak()
                + ", wait=" + feedback.getQueueWaitPeakMs()
                + ", catchups=" + feedback.getCatchUpEvents()
                + ", clients=" + target.getSessionCount());
    }

    private void skip(String reason) {
        mMetrics.increment(PipelineMetrics.Counter.BACKPRESSURE_SKIPPED);
        // Skip reasons are deliberately not logged per report: a multi-client stream would write a
        // line every three seconds for a decision that never changes.
    }

    private void logFailureLimited(String message) {
        final long nowMs = mTimeSource.nowMs();
        if (mHasFailureLogged && nowMs - mLastFailureLogMs < WARN_INTERVAL_MS) {
            return;
        }
        mHasFailureLogged = true;
        mLastFailureLogMs = nowMs;
        logFailure(message);
    }

    /**
     * Writes one applied-action line; overridden by a test to observe actions.
     *
     * @param message line to write
     */
    protected void logAction(String message) {
        Log.i(TAG, message);
    }

    /**
     * Writes one encoder-failure line, already throttled; overridden by a test to count failures.
     *
     * @param message line to write
     */
    protected void logFailure(String message) {
        Log.w(TAG, message);
    }

    /**
     * One report and the encoder seam it applies to.
     */
    private static final class Report {

        private final StreamFeedback feedback;

        private final BitrateTarget target;

        private Report(StreamFeedback feedback, BitrateTarget target) {
            this.feedback = feedback;
            this.target = target;
        }
    }
}
