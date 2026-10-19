package com.obby.android.localscreenshare.utils;

import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Process;

import androidx.annotation.NonNull;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;

import org.apache.commons.lang3.concurrent.BasicThreadFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * NSD resolve helper that serializes requests and reports results on the main thread.
 *
 * <p>Resolve requests run on one shared single-thread executor at background priority, so only one
 * resolve is in flight at a time. Each task submits the request and waits for the framework
 * callback before the next task starts; that wait blocks the resolver thread, never the caller.
 * Both outcomes are posted to the main thread, so listeners may touch UI state.</p>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class NsdUtils {
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
     * Resolves {@code serviceInfo} in the background and reports the outcome to {@code listener}
     * on the main thread. Returns immediately. A resolve that never completes keeps the resolver
     * thread blocked, so later requests queue behind it; interrupting that thread ends the wait
     * without cancelling the framework request.
     */
    public static void resolveService(@NonNull final NsdManager nsdManager, @NonNull final NsdServiceInfo serviceInfo,
        @NonNull final NsdManager.ResolveListener listener) {
        RESOLVE_SERVICE_EXECUTOR_SUPPLIER.get().execute(() -> {
            final CountDownLatch latch = new CountDownLatch(1);
            final Executor executor = ThreadUtils.getMainThreadExecutor();

            nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    latch.countDown();
                    executor.execute(() -> listener.onResolveFailed(serviceInfo, errorCode));
                }

                @Override
                public void onServiceResolved(NsdServiceInfo serviceInfo) {
                    latch.countDown();
                    executor.execute(() -> listener.onServiceResolved(serviceInfo));
                }
            });

            try {
                latch.await();
            } catch (InterruptedException e) {
                // ignored
            }
        });
    }
}
