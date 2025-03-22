package com.obby.android.localscreenshare.utils;

import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Process;

import androidx.annotation.NonNull;

import org.apache.commons.lang3.concurrent.BasicThreadFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class NsdUtils {
    private static final Object RESOLVE_SERVICE_EXECUTOR_LOCK = new Object();

    private static volatile Executor sResolveServiceExecutor;

    public static void resolveService(@NonNull final NsdManager nsdManager, @NonNull final NsdServiceInfo serviceInfo,
        @NonNull final NsdManager.ResolveListener listener) {
        getResolveServiceExecutor().execute(() -> {
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

    @NonNull
    private static Executor getResolveServiceExecutor() {
        if (sResolveServiceExecutor == null) {
            synchronized (RESOLVE_SERVICE_EXECUTOR_LOCK) {
                if (sResolveServiceExecutor == null) {
                    sResolveServiceExecutor = Executors.newSingleThreadExecutor(new BasicThreadFactory.Builder()
                        .namingPattern("lss-nsd-service-resolver-%d")
                        .wrappedFactory(runnable -> new Thread(runnable) {
                            @Override
                            public void run() {
                                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                                super.run();
                            }
                        })
                        .build());
                }
            }
        }
        return sResolveServiceExecutor;
    }
}
