package com.obby.android.localscreenshare.discovery.nsd;

import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Process;

import androidx.annotation.NonNull;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.obby.android.localscreenshare.support.ThreadUtils;

import org.apache.commons.lang3.concurrent.BasicThreadFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * NSD resolve helper that serializes requests and reports results on the main thread.
 *
 * <p>Resolve requests run on one shared single-thread executor at background priority, so only one
 * resolve is in flight at a time. Each task submits the request and waits for the framework
 * callback before the next task starts; that wait blocks the resolver thread, never the caller.
 * The wait is bounded by a timeout, so a service that never answers cannot occupy the resolver
 * forever: after the timeout the task reports a failure and the thread moves on. The first outcome
 * wins; a late framework callback is ignored. Both outcomes are posted to the main thread, so
 * listeners may touch UI state.</p>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class NsdResolver {
    /** Default resolve timeout; matches the discovery research budget. */
    public static final long DEFAULT_RESOLVE_TIMEOUT_MS = 2_000L;

    /** Error code reported through {@link NsdManager.ResolveListener#onResolveFailed} on timeout. */
    public static final int ERROR_CODE_TIMEOUT = -1;

    /** Error code reported when the framework rejected the resolve request synchronously. */
    public static final int ERROR_CODE_REQUEST_REJECTED = -2;

    private static final Supplier<Executor> RESOLVE_SERVICE_EXECUTOR_SUPPLIER =
        Suppliers.memoize(() -> Executors.newSingleThreadExecutor(new BasicThreadFactory.Builder()
            .namingPattern("lss-nsd-service-resolver-%d")
            .wrappedFactory(runnable -> new Thread(runnable) {
                @Override
                public void run() {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    super.run();
                }
            })
            .build()));

    /**
     * Resolves {@code serviceInfo} with the default timeout and reports the outcome to
     * {@code listener} on the main thread.
     */
    public static void resolveService(@NonNull final NsdManager nsdManager, @NonNull final NsdServiceInfo serviceInfo,
        @NonNull final NsdManager.ResolveListener listener) {
        resolveService(nsdManager, serviceInfo, DEFAULT_RESOLVE_TIMEOUT_MS, listener);
    }

    /**
     * Resolves {@code serviceInfo} in the background and reports the outcome to {@code listener}
     * on the main thread. Returns immediately.
     *
     * <p>Exactly one outcome is delivered. When the framework does not answer within
     * {@code timeoutMs}, {@link NsdManager.ResolveListener#onResolveFailed} is called with
     * {@link #ERROR_CODE_TIMEOUT} and the resolver thread proceeds to the next request; a callback
     * arriving later is dropped. A synchronously rejected request reports
     * {@link #ERROR_CODE_REQUEST_REJECTED}.</p>
     *
     * @param timeoutMs positive wait budget in milliseconds
     */
    public static void resolveService(@NonNull final NsdManager nsdManager, @NonNull final NsdServiceInfo serviceInfo,
        final long timeoutMs, @NonNull final NsdManager.ResolveListener listener) {
        if (timeoutMs <= 0L) {
            throw new IllegalArgumentException("timeoutMs must be positive: " + timeoutMs);
        }

        RESOLVE_SERVICE_EXECUTOR_SUPPLIER.get().execute(() -> {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicBoolean delivered = new AtomicBoolean(false);
            final Executor executor = ThreadUtils.getMainThreadExecutor();

            try {
                nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                        if (delivered.compareAndSet(false, true)) {
                            latch.countDown();
                            executor.execute(() -> listener.onResolveFailed(info, errorCode));
                        }
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo info) {
                        if (delivered.compareAndSet(false, true)) {
                            latch.countDown();
                            executor.execute(() -> listener.onServiceResolved(info));
                        }
                    }
                });
            } catch (RuntimeException e) {
                if (delivered.compareAndSet(false, true)) {
                    executor.execute(() -> listener.onResolveFailed(serviceInfo, ERROR_CODE_REQUEST_REJECTED));
                }
                return;
            }

            boolean completed;
            try {
                completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                // The caller's thread is being torn down; fall through and report the timeout.
                Thread.currentThread().interrupt();
                completed = false;
            }

            if (!completed && delivered.compareAndSet(false, true)) {
                executor.execute(() -> listener.onResolveFailed(serviceInfo, ERROR_CODE_TIMEOUT));
            }
        });
    }
}
