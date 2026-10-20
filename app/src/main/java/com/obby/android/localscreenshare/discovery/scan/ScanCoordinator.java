package com.obby.android.localscreenshare.discovery.scan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.support.DelayedScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

/**
 * Decides when a LAN scan is worth its cost.
 *
 * <p>Discovery starts three sources at once: announcements, the favorite and last-success probes,
 * and a one-second grace window. If the grace window ends with at least one confirmed device the
 * scan is skipped; a scan is the fallback for networks that filter multicast, not a second
 * discovery channel. When it does run, it probes the candidates of every eligible interface with
 * {@link AddressScanner} and reports the peers that answered through the normal store path.</p>
 *
 * <p>Triggers are deliberately coarse: discovery start, an explicit refresh, an empty list and a
 * network change. Repeated triggers while a grace window or a scan is running are coalesced; only
 * a network change restarts the window, because the interfaces the running scan enumerated may no
 * longer exist. There is no periodic scan: a stale list is a job for the announcement channel.</p>
 *
 * <p>State is guarded by this object; the grace timer runs on the injected scheduler, the scan on
 * the injected background executor, and listener callbacks are marshaled to the injected callback
 * executor. Every scan carries the generation it was started in, so a result that arrives after a
 * cancel or a restart is dropped.</p>
 */
public final class ScanCoordinator {

    /**
     * Length of the window in which announcements and favorite probes can confirm a device.
     */
    public static final long GRACE_MS = 1_000L;

    /**
     * State of the coordinator.
     */
    public enum State {
        /**
         * Nothing scheduled.
         */
        IDLE,
        /**
         * Waiting for announcements and probes to confirm a device.
         */
        GRACE,
        /**
         * Probing the interface candidates.
         */
        SCANNING,
        /**
         * The last run ended; a new trigger may start another one.
         */
        DONE
    }

    /**
     * What asked for the scan.
     */
    public enum Trigger {
        /**
         * Discovery was just started.
         */
        DISCOVERY_START,
        /**
         * The user asked to search again.
         */
        MANUAL_REFRESH,
        /**
         * The device list stayed empty after the initial window.
         */
        EMPTY_LIST,
        /**
         * Connectivity changed; interfaces may differ from the previous run.
         */
        NETWORK_CHANGED
    }

    /**
     * Receives state changes and the aggregate result of one run on the callback executor.
     */
    public interface Listener {
        /**
         * Called after a state transition.
         */
        void onStateChanged(@NonNull State state);

        /**
         * Called once per run, including runs that were cancelled or skipped.
         */
        void onScanCompleted(@NonNull AddressScanner.ScanResult result);
    }

    @NonNull
    private final DelayedScheduler mScheduler;

    @NonNull
    private final InterfaceEnumerator mEnumerator;

    @NonNull
    private final AddressScanner mScanner;

    @NonNull
    private final Executor mBackgroundExecutor;

    @NonNull
    private final Executor mCallbackExecutor;

    @NonNull
    private final IntSupplier mConfirmedCount;

    @NonNull
    private final IntSupplier mPortSupplier;

    @NonNull
    private final AtomicInteger mGeneration = new AtomicInteger();

    @Nullable
    private DelayedScheduler.Cancellable mGraceTask;

    @Nullable
    private Listener mListener;

    @NonNull
    private State mState = State.IDLE;

    private int mRuns;

    /**
     * Creates the coordinator.
     *
     * @param scheduler timer of the grace window and of the empty-list trigger
     * @param enumerator eligible interfaces
     * @param scanner probe executor
     * @param backgroundExecutor executor that runs the blocking scan
     * @param callbackExecutor executor that receives listener callbacks
     * @param confirmedCount returns how many devices are confirmed in the store right now
     * @param portSupplier returns the gRPC port peers are assumed to listen on
     */
    public ScanCoordinator(@NonNull final DelayedScheduler scheduler,
        @NonNull final InterfaceEnumerator enumerator, @NonNull final AddressScanner scanner,
        @NonNull final Executor backgroundExecutor, @NonNull final Executor callbackExecutor,
        @NonNull final IntSupplier confirmedCount, @NonNull final IntSupplier portSupplier) {
        mScheduler = Objects.requireNonNull(scheduler, "scheduler");
        mEnumerator = Objects.requireNonNull(enumerator, "enumerator");
        mScanner = Objects.requireNonNull(scanner, "scanner");
        mBackgroundExecutor = Objects.requireNonNull(backgroundExecutor, "backgroundExecutor");
        mCallbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        mConfirmedCount = Objects.requireNonNull(confirmedCount, "confirmedCount");
        mPortSupplier = Objects.requireNonNull(portSupplier, "portSupplier");
    }

    /**
     * Subscribes the listener that receives state changes and results.
     */
    public void setListener(@Nullable final Listener listener) {
        synchronized (this) {
            mListener = listener;
        }
    }

    /**
     * Requests a run.
     *
     * <p>A trigger that arrives while the coordinator is in {@link State#GRACE} or
     * {@link State#SCANNING} is coalesced into the run in progress, except for
     * {@link Trigger#NETWORK_CHANGED}, which cancels and restarts because the interface set can
     * have changed.</p>
     */
    public void start(@NonNull final Trigger trigger) {
        final int generation;
        final boolean restart;
        synchronized (this) {
            restart = mState == State.GRACE || mState == State.SCANNING;
            if (restart && trigger != Trigger.NETWORK_CHANGED) {
                return;
            }
            if (restart) {
                cancelTimers();
                mScanner.cancel();
            }
            generation = mGeneration.incrementAndGet();
            mState = State.GRACE;
            mGraceTask = mScheduler.schedule(GRACE_MS, () -> onGraceElapsed(generation));
        }
        notifyState(State.GRACE);
    }

    /**
     * Cancels the grace window and the scan in progress.
     *
     * <p>Idempotent. A result that was already being computed is dropped, and no listener callback
     * is delivered for it.</p>
     */
    public void cancel() {
        synchronized (this) {
            mGeneration.incrementAndGet();
            cancelTimers();
            mState = State.IDLE;
        }
        mScanner.cancel();
        notifyState(State.IDLE);
    }

    /**
     * Returns the current state.
     */
    @NonNull
    public State state() {
        synchronized (this) {
            return mState;
        }
    }

    /**
     * Returns how many scans were started; used by tests and diagnostics.
     */
    public int runs() {
        synchronized (this) {
            return mRuns;
        }
    }

    private void onGraceElapsed(final int generation) {
        synchronized (this) {
            if (generation != mGeneration.get() || mState != State.GRACE) {
                return;
            }
            mGraceTask = null;
            if (mConfirmedCount.getAsInt() > 0) {
                mState = State.DONE;
            } else {
                mState = State.SCANNING;
                mRuns++;
            }
        }

        if (state() == State.DONE) {
            notifyState(State.DONE);
            dispatchResult(new AddressScanner.ScanResult(0, new ArrayList<>(), false, false));
            return;
        }

        notifyState(State.SCANNING);
        mBackgroundExecutor.execute(() -> runScan(generation));
    }

    private void runScan(final int generation) {
        int scanned = 0;
        boolean cancelled = false;
        boolean partial = false;
        final List<AddressScanner.ScanHit> hits = new ArrayList<>();

        for (final LocalInterface localInterface : mEnumerator.enumerate()) {
            if (isStale(generation)) {
                cancelled = true;
                break;
            }
            final NetworkPrefix.Result candidates = NetworkPrefix.candidates(
                localInterface.getAddress(), localInterface.getPrefixLength());
            final AddressScanner.ScanResult result = mScanner.scan(candidates.getAddresses(),
                mPortSupplier.getAsInt(), candidates.isPartial());
            scanned += result.getScanned();
            hits.addAll(result.getConfirmed());
            partial |= result.isPartial();
            cancelled |= result.isCancelled();
            if (cancelled) {
                break;
            }
        }

        synchronized (this) {
            if (generation != mGeneration.get()) {
                return;
            }
            mState = State.DONE;
        }

        notifyState(State.DONE);
        dispatchResult(new AddressScanner.ScanResult(scanned, hits, cancelled, partial));
    }

    private boolean isStale(final int generation) {
        synchronized (this) {
            return generation != mGeneration.get();
        }
    }

    private void cancelTimers() {
        if (mGraceTask != null) {
            mGraceTask.cancel();
            mGraceTask = null;
        }
    }

    private void notifyState(@NonNull final State state) {
        final Listener listener;
        synchronized (this) {
            listener = mListener;
        }
        if (listener == null) {
            return;
        }
        mCallbackExecutor.execute(() -> listener.onStateChanged(state));
    }

    private void dispatchResult(@NonNull final AddressScanner.ScanResult result) {
        final Listener listener;
        synchronized (this) {
            listener = mListener;
        }
        if (listener == null) {
            return;
        }
        mCallbackExecutor.execute(() -> listener.onScanCompleted(result));
    }
}
