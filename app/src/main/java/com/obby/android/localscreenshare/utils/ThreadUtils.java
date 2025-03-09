package com.obby.android.localscreenshare.utils;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.core.os.HandlerCompat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class ThreadUtils {
    private static final Object EXECUTOR_LOCK = new Object();

    private static volatile Executor sMainThreadExecutor;

    private static volatile Executor sWorkerThreadExecutor;

    public static void runOnMainThread(@NonNull final Runnable runnable) {
        if (isMainThread()) {
            runnable.run();
        } else {
            executeOnMainThread(runnable);
        }
    }

    public static void runOnWorkerThread(@NonNull final Runnable runnable) {
        if (isMainThread()) {
            executeOnWorkerThread(runnable);
        } else {
            runnable.run();
        }
    }

    public static void executeOnMainThread(@NonNull final Runnable runnable) {
        getMainThreadExecutor().execute(runnable);
    }

    public static void executeOnWorkerThread(@NonNull final Runnable runnable) {
        getWorkerThreadExecutor().execute(runnable);
    }

    @NonNull
    public static Executor getMainThreadExecutor() {
        if (sMainThreadExecutor == null) {
            synchronized (EXECUTOR_LOCK) {
                if (sMainThreadExecutor == null) {
                    final Handler handler = HandlerCompat.createAsync(Looper.getMainLooper());
                    sMainThreadExecutor = handler::post;
                }
            }
        }
        return sMainThreadExecutor;
    }

    @NonNull
    public static Executor getWorkerThreadExecutor() {
        if (sWorkerThreadExecutor == null) {
            synchronized (EXECUTOR_LOCK) {
                if (sWorkerThreadExecutor == null) {
                    sWorkerThreadExecutor = CompletableFuture::runAsync;
                }
            }
        }
        return sWorkerThreadExecutor;
    }

    public static boolean isMainThread() {
        final Looper looper = Looper.myLooper();
        return looper != null && looper == Looper.getMainLooper();
    }
}
