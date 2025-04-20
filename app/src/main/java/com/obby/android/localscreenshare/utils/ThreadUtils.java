package com.obby.android.localscreenshare.utils;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ThreadUtils {
    private static final Supplier<Executor> MAIN_THREAD_EXECUTOR_SUPPLIER =
        Suppliers.memoize(() -> new Handler(Looper.getMainLooper())::post);

    private static final Supplier<Executor> WORKER_THREAD_EXECUTOR_SUPPLIER =
        Suppliers.memoize(() -> CompletableFuture::runAsync);

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
        return MAIN_THREAD_EXECUTOR_SUPPLIER.get();
    }

    @NonNull
    public static Executor getWorkerThreadExecutor() {
        return WORKER_THREAD_EXECUTOR_SUPPLIER.get();
    }

    public static boolean isMainThread() {
        final Looper looper = Looper.myLooper();
        return looper != null && looper == Looper.getMainLooper();
    }
}
