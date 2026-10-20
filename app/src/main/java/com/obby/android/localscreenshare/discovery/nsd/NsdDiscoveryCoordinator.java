package com.obby.android.localscreenshare.discovery.nsd;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.support.BackoffPolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Runs NSD discovery for several service types and keeps them alive across failures.
 *
 * <p>Each service type has its own running state and its own {@link BackoffPolicy}: a failed type
 * is retried on its own schedule and never unregisters the type that is running. A network change
 * restarts every type after a short debounce, because several callbacks usually arrive for one
 * switch, and resets the backoff so the first retry after connectivity returns is immediate.</p>
 *
 * <p>The coordinator owns no Android types. It drives a {@link Discovery} and a
 * {@link Scheduler}, both injected, which keeps the retry and restart scheduling covered by JVM
 * tests. All methods must be called on the main thread.</p>
 */
public final class NsdDiscoveryCoordinator {

    /**
     * Debounce applied to connectivity callbacks before discovery is restarted.
     */
    public static final long NETWORK_CHANGE_DEBOUNCE_MS = 500L;

    /**
     * The start/stop surface of {@code NsdManager} used by this coordinator.
     */
    public interface Discovery {
        /**
         * Starts discovery for {@code serviceType}.
         */
        void startDiscovery(@NonNull String serviceType);

        /**
         * Stops discovery for {@code serviceType}.
         */
        void stopDiscovery(@NonNull String serviceType);
    }

    /**
     * Delayed execution surface; production uses the main-thread handler.
     */
    public interface Scheduler {
        /**
         * Runs {@code task} after {@code delayMs}; the returned handle cancels it.
         */
        @NonNull
        Cancellable schedule(long delayMs, @NonNull Runnable task);
    }

    /**
     * Handle for a scheduled task.
     */
    public interface Cancellable {
        /**
         * Cancels the task; idempotent.
         */
        void cancel();
    }

    @NonNull
    private final Discovery mDiscovery;

    @NonNull
    private final Scheduler mScheduler;

    @NonNull
    private final Supplier<BackoffPolicy> mBackoffFactory;

    @NonNull
    private final Map<String, Session> mSessions = new LinkedHashMap<>();

    @Nullable
    private Cancellable mNetworkChangeTask;

    private boolean mActive;

    /**
     * Creates a coordinator.
     *
     * @param discovery start/stop surface
     * @param scheduler delayed execution surface
     * @param serviceTypes service types to run; each gets an independent session
     * @param backoffFactory creates one fresh policy per service type
     */
    public NsdDiscoveryCoordinator(@NonNull final Discovery discovery, @NonNull final Scheduler scheduler,
        @NonNull final List<String> serviceTypes, @NonNull final Supplier<BackoffPolicy> backoffFactory) {
        mDiscovery = Objects.requireNonNull(discovery, "discovery");
        mScheduler = Objects.requireNonNull(scheduler, "scheduler");
        mBackoffFactory = Objects.requireNonNull(backoffFactory, "backoffFactory");
        for (final String serviceType : serviceTypes) {
            mSessions.put(serviceType, new Session(serviceType, backoffFactory.get()));
        }
    }

    /**
     * Starts discovery for every service type; calling it twice restarts nothing extra.
     */
    public void startAll() {
        if (mActive) {
            return;
        }
        mActive = true;
        for (final Session session : mSessions.values()) {
            start(session);
        }
    }

    /**
     * Stops every type, cancels retries and the pending network restart.
     *
     * <p>Every type is stopped even when it is not believed to be running: framework stop
     * callbacks are asynchronous, so the local flag can lag behind the real state, and a stop for
     * a type that is already idle is harmless.</p>
     */
    public void stopAll() {
        mActive = false;
        cancelNetworkChangeTask();
        for (final Session session : mSessions.values()) {
            cancelRetry(session);
            session.running = false;
            mDiscovery.stopDiscovery(session.serviceType);
        }
    }

    /**
     * Reports that discovery for {@code serviceType} started; clears its backoff.
     */
    public void onDiscoveryStarted(@NonNull final String serviceType) {
        final Session session = mSessions.get(serviceType);
        if (session == null) {
            return;
        }
        cancelRetry(session);
        session.running = true;
        session.backoff.reset();
    }

    /**
     * Reports a stop that was requested rather than a restart.
     */
    public void onDiscoveryStopped(@NonNull final String serviceType) {
        final Session session = mSessions.get(serviceType);
        if (session != null) {
            session.running = false;
        }
    }

    /**
     * Reports a failed start and schedules a backoff retry for that type only.
     *
     * @return the scheduled delay in milliseconds, or -1 when the coordinator is inactive
     */
    public long onStartFailed(@NonNull final String serviceType) {
        final Session session = mSessions.get(serviceType);
        if (session == null || !mActive) {
            return -1L;
        }
        session.running = false;
        cancelRetry(session);

        final long delayMs = session.backoff.nextDelayMs();
        session.retry = mScheduler.schedule(delayMs, () -> {
            session.retry = null;
            if (mActive) {
                start(session);
            }
        });
        return delayMs;
    }

    /**
     * Reports a connectivity change. Pending retries are cancelled immediately, because a retry
     * racing with the restart could start the same type twice, and every backoff is reset so the
     * first attempt after connectivity returns is immediate. Restarts are debounced so the burst
     * of callbacks for one switch results in one restart.
     */
    public void onNetworkChanged() {
        if (!mActive) {
            return;
        }
        cancelNetworkChangeTask();
        for (final Session session : mSessions.values()) {
            cancelRetry(session);
            session.backoff.reset();
        }
        mNetworkChangeTask = mScheduler.schedule(NETWORK_CHANGE_DEBOUNCE_MS, this::restartAll);
    }

    /**
     * True while discovery is wanted.
     */
    public boolean isActive() {
        return mActive;
    }

    /**
     * True when the given type is believed to be discovering.
     */
    public boolean isRunning(@NonNull final String serviceType) {
        final Session session = mSessions.get(serviceType);
        return session != null && session.running;
    }

    /**
     * Number of service types with a pending retry.
     */
    public int pendingRetryCount() {
        int count = 0;
        for (final Session session : mSessions.values()) {
            if (session.retry != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * True when a network-change restart is scheduled but not yet executed.
     */
    public boolean isNetworkRestartPending() {
        return mNetworkChangeTask != null;
    }

    /**
     * Returns the service types this coordinator owns.
     */
    @NonNull
    public List<String> serviceTypes() {
        return new ArrayList<>(mSessions.keySet());
    }

    // Restarts every type. The stop is issued unconditionally for the same reason as in stopAll:
    // the framework may still be discovering a type whose local running flag was cleared by a
    // late stop callback, and starting such a type without stopping it first is rejected.
    private void restartAll() {
        mNetworkChangeTask = null;
        if (!mActive) {
            return;
        }
        for (final Session session : mSessions.values()) {
            cancelRetry(session);
            session.backoff.reset();
            session.running = false;
            mDiscovery.stopDiscovery(session.serviceType);
            start(session);
        }
    }

    private void start(@NonNull final Session session) {
        if (!mActive) {
            return;
        }
        session.running = true;
        mDiscovery.startDiscovery(session.serviceType);
    }

    private void cancelRetry(@NonNull final Session session) {
        if (session.retry != null) {
            session.retry.cancel();
            session.retry = null;
        }
    }

    private void cancelNetworkChangeTask() {
        if (mNetworkChangeTask != null) {
            mNetworkChangeTask.cancel();
            mNetworkChangeTask = null;
        }
    }

    /**
     * Per-type running flag, retry handle and backoff.
     */
    private static final class Session {
        @NonNull
        final String serviceType;

        @NonNull
        final BackoffPolicy backoff;

        boolean running;

        @Nullable
        Cancellable retry;

        Session(@NonNull final String serviceType, @NonNull final BackoffPolicy backoff) {
            this.serviceType = serviceType;
            this.backoff = backoff;
        }
    }
}
