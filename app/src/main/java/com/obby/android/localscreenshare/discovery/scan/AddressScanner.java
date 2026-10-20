package com.obby.android.localscreenshare.discovery.scan;

import androidx.annotation.NonNull;

import com.obby.android.localscreenshare.core.util.TimeSource;
import com.obby.android.localscreenshare.grpc.screenstream.DeviceSummary;

import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded, cancellable probe of one interface's candidates.
 *
 * <p>One scan runs on a fixed pool of {@value #DEFAULT_MAX_CONCURRENCY} workers with a bounded
 * queue, so the number of outstanding probes never exceeds the pool plus the queue no matter how
 * many candidates a prefix produced. Each probe has a {@link #PROBE_TIMEOUT_MS 500 ms} deadline and
 * the whole scan has a budget; once the budget is spent no new probe is dispatched and the scan
 * returns what it has. {@link #cancel()} is idempotent and stops dispatch immediately; probes that
 * are already in flight finish and their late answers are ignored.</p>
 *
 * <p>A scan never confirms anything by itself: it returns the peers that answered so the caller
 * can feed the normal observation and confirmation path of the store. Probes are issued with
 * {@code announces=false}, so scanning a subnet cannot amplify into reverse handshakes.</p>
 *
 * <p>{@link #scan(List, int, boolean)} blocks until the scan ends and is meant to run on a worker
 * thread. State is per-scan except {@link #cancel()}, which targets the scan in progress.</p>
 */
public final class AddressScanner {

    /**
     * Default number of probes in flight.
     */
    public static final int DEFAULT_MAX_CONCURRENCY = 50;

    /**
     * Deadline of one probe, in milliseconds.
     */
    public static final long PROBE_TIMEOUT_MS = 500L;

    /**
     * Budget of one interface scan, in milliseconds.
     */
    public static final long DEFAULT_BUDGET_MS = 8_000L;

    /**
     * Candidates that may wait for a worker before dispatch blocks.
     */
    public static final int QUEUE_CAPACITY = 128;

    private static final AtomicInteger THREAD_IDS = new AtomicInteger();

    @NonNull
    private final AddressProbe mProbe;

    private final int mMaxConcurrency;

    private final long mBudgetMs;

    @NonNull
    private final TimeSource mTimeSource;

    private final Object mCancelLock = new Object();

    // Token of the scan in progress; null while idle. A new scan gets a new token, so cancelling
    // one scan never cancels the next one.
    private AtomicBoolean mActiveScan;

    /**
     * Creates a scanner.
     *
     * @param probe one-address probe
     * @param maxConcurrency probes in flight; must be positive
     * @param budgetMs budget of one scan; must be positive
     * @param timeSource clock of the budget
     */
    public AddressScanner(@NonNull final AddressProbe probe, final int maxConcurrency,
        final long budgetMs, @NonNull final TimeSource timeSource) {
        if (maxConcurrency <= 0) {
            throw new IllegalArgumentException("maxConcurrency must be positive: " + maxConcurrency);
        }
        if (budgetMs <= 0) {
            throw new IllegalArgumentException("budgetMs must be positive: " + budgetMs);
        }
        mProbe = Objects.requireNonNull(probe, "probe");
        mMaxConcurrency = maxConcurrency;
        mBudgetMs = budgetMs;
        mTimeSource = Objects.requireNonNull(timeSource, "timeSource");
    }

    /**
     * Creates a scanner with the default concurrency, probe deadline and budget.
     */
    @NonNull
    public static AddressScanner create(@NonNull final AddressProbe probe,
        @NonNull final TimeSource timeSource) {
        return new AddressScanner(probe, DEFAULT_MAX_CONCURRENCY, DEFAULT_BUDGET_MS, timeSource);
    }

    /**
     * Probes every candidate of one interface.
     *
     * @param candidates hosts to probe, in ascending order
     * @param port gRPC port every host is assumed to use
     * @param partial whether the candidate list was truncated by the prefix bound
     */
    @NonNull
    public ScanResult scan(@NonNull final List<Inet4Address> candidates, final int port,
        final boolean partial) {
        final AtomicBoolean cancelled = new AtomicBoolean();
        synchronized (mCancelLock) {
            mActiveScan = cancelled;
        }
        if (candidates.isEmpty()) {
            clearActiveScan(cancelled);
            return new ScanResult(0, Collections.emptyList(), false, partial);
        }

        final long deadlineMs = mTimeSource.nowMs() + mBudgetMs;
        final AtomicInteger scanned = new AtomicInteger();
        final List<ScanHit> hits = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch finished = new CountDownLatch(candidates.size());
        final Semaphore slots = new Semaphore(mMaxConcurrency + QUEUE_CAPACITY);
        final int threadId = THREAD_IDS.incrementAndGet();
        final ThreadPoolExecutor executor = new ThreadPoolExecutor(mMaxConcurrency, mMaxConcurrency,
            0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
                final Thread thread = new Thread(runnable, "lss-scan-" + threadId);
                thread.setDaemon(true);
                return thread;
            });

        try {
            for (final Inet4Address address : candidates) {
                if (cancelled.get() || mTimeSource.nowMs() >= deadlineMs) {
                    finished.countDown();
                    continue;
                }
                try {
                    slots.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    finished.countDown();
                    continue;
                }
                if (cancelled.get()) {
                    slots.release();
                    finished.countDown();
                    continue;
                }
                final String host = address.getHostAddress();
                final Runnable task = () -> {
                    try {
                        final long remaining = deadlineMs - mTimeSource.nowMs();
                        if (cancelled.get() || remaining <= 0L) {
                            return;
                        }
                        final ProbeResult result = mProbe.probe(host, port,
                            Math.min(PROBE_TIMEOUT_MS, remaining));
                        scanned.incrementAndGet();
                        if (result.isConfirmed() && !cancelled.get() && result.getSummary() != null) {
                            hits.add(new ScanHit(host, port, result.getSummary()));
                        }
                    } catch (RuntimeException e) {
                        // A probe that throws is a failed probe, never a reason to abort the scan.
                    } finally {
                        slots.release();
                        finished.countDown();
                    }
                };
                submit(executor, task, slots, finished, cancelled, deadlineMs);
            }

            awaitCompletion(finished, deadlineMs, cancelled);
        } finally {
            executor.shutdownNow();
            clearActiveScan(cancelled);
        }

        return new ScanResult(scanned.get(), new ArrayList<>(hits), cancelled.get(), partial);
    }

    /**
     * Cancels the scan in progress and suppresses further dispatch.
     *
     * <p>Idempotent; the next {@link #scan(List, int, boolean)} starts with a fresh token, so a
     * late cancel never stops a later scan.</p>
     */
    public void cancel() {
        synchronized (mCancelLock) {
            if (mActiveScan != null) {
                mActiveScan.set(true);
            }
        }
    }

    /**
     * Submits one probe task.
     *
     * <p>A task can be rejected although a slot permit was free: the permit is released inside the
     * task's {@code finally}, while the pool still counts the task as occupying a worker for a few
     * instructions. That is a transient capacity race, not a failure of the candidate, so the
     * dispatcher waits briefly and retries instead of dropping the address.</p>
     */
    private void submit(@NonNull final ThreadPoolExecutor executor, @NonNull final Runnable task,
        @NonNull final Semaphore slots, @NonNull final CountDownLatch finished,
        @NonNull final AtomicBoolean cancelled, final long deadlineMs) {
        while (true) {
            if (cancelled.get() || mTimeSource.nowMs() >= deadlineMs) {
                slots.release();
                finished.countDown();
                return;
            }
            try {
                executor.execute(task);
                return;
            } catch (RejectedExecutionException e) {
                try {
                    Thread.sleep(1L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    slots.release();
                    finished.countDown();
                    return;
                }
            } catch (RuntimeException e) {
                slots.release();
                finished.countDown();
                return;
            }
        }
    }

    private void clearActiveScan(@NonNull final AtomicBoolean token) {
        synchronized (mCancelLock) {
            if (mActiveScan == token) {
                mActiveScan = null;
            }
        }
    }

    private void awaitCompletion(@NonNull final CountDownLatch finished, final long deadlineMs,
        @NonNull final AtomicBoolean cancelled) {
        while (true) {
            try {
                if (finished.await(50L, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (cancelled.get() || mTimeSource.nowMs() >= deadlineMs + PROBE_TIMEOUT_MS) {
                return;
            }
        }
    }

    /**
     * Result of one interface scan.
     */
    public static final class ScanResult {

        private final int mScanned;

        @NonNull
        private final List<ScanHit> mConfirmed;

        private final boolean mCancelled;

        private final boolean mPartial;

        ScanResult(final int scanned, @NonNull final List<ScanHit> confirmed,
            final boolean cancelled, final boolean partial) {
            mScanned = scanned;
            mConfirmed = Collections.unmodifiableList(confirmed);
            mCancelled = cancelled;
            mPartial = partial;
        }

        /**
         * Returns how many probes were actually issued.
         */
        public int getScanned() {
            return mScanned;
        }

        /**
         * Returns the peers that answered, in completion order.
         */
        @NonNull
        public List<ScanHit> getConfirmed() {
            return mConfirmed;
        }

        /**
         * Returns how many peers answered.
         */
        public int getConfirmedCount() {
            return mConfirmed.size();
        }

        /**
         * True when {@link #cancel()} stopped this scan early.
         */
        public boolean isCancelled() {
            return mCancelled;
        }

        /**
         * True when the candidate list was truncated to the local {@code /24}.
         */
        public boolean isPartial() {
            return mPartial;
        }
    }

    /**
     * One peer that answered during a scan.
     */
    public static final class ScanHit {

        @NonNull
        private final String mHost;

        private final int mPort;

        @NonNull
        private final DeviceSummary mSummary;

        private ScanHit(@NonNull final String host, final int port,
            @NonNull final DeviceSummary summary) {
            mHost = host;
            mPort = port;
            mSummary = summary;
        }

        @NonNull
        public String getHost() {
            return mHost;
        }

        public int getPort() {
            return mPort;
        }

        @NonNull
        public DeviceSummary getSummary() {
            return mSummary;
        }
    }
}
