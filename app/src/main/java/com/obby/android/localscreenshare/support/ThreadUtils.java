package com.obby.android.localscreenshare.support;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Thread dispatch helpers for the main looper and the shared worker pool.
 *
 * <p>Two pairs of methods exist. {@code runOn*} runs inline when the caller is already on the
 * target thread and dispatches otherwise; {@code executeOn*} always dispatches. The main-thread
 * executor posts to the main looper, so a runnable that throws is handled by the looper's
 * uncaught-exception path. The worker executor delegates to
 * {@link java.util.concurrent.CompletableFuture#runAsync(Runnable)}, which uses the common
 * ForkJoinPool; the returned future is discarded, so worker exceptions do not reach the caller.
 * Both executors are created once and reused. No method blocks.</p>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ThreadUtils {
    private static final Supplier<Executor> MAIN_THREAD_EXECUTOR_SUPPLIER =
        Suppliers.memoize(() -> new Handler(Looper.getMainLooper())::post);

    private static final Supplier<Executor> WORKER_THREAD_EXECUTOR_SUPPLIER =
        Suppliers.memoize(() -> CompletableFuture::runAsync);

    /**
     * Runs {@code runnable} now when called from the main thread, otherwise posts it to the main
     * looper.
     */
    public static void runOnMainThread(@NonNull final Runnable runnable) {
        if (isMainThread()) {
            runnable.run();
        } else {
            executeOnMainThread(runnable);
        }
    }

    /**
     * Runs {@code runnable} now when called from any thread other than the main thread, otherwise
     * hands it to the worker executor. Running inline still means the caller's thread, not a pool
     * thread.
     */
    public static void runOnWorkerThread(@NonNull final Runnable runnable) {
        if (isMainThread()) {
            executeOnWorkerThread(runnable);
        } else {
            runnable.run();
        }
    }

    /** Always posts to the main looper, including when called from the main thread. */
    public static void executeOnMainThread(@NonNull final Runnable runnable) {
        getMainThreadExecutor().execute(runnable);
    }

    /** Always hands the runnable to the worker executor, even from a background thread. */
    public static void executeOnWorkerThread(@NonNull final Runnable runnable) {
        getWorkerThreadExecutor().execute(runnable);
    }

    /** Returns the shared executor that posts to the main looper. */
    @NonNull
    public static Executor getMainThreadExecutor() {
        return MAIN_THREAD_EXECUTOR_SUPPLIER.get();
    }

    /** Returns the shared worker executor backed by the common ForkJoinPool. */
    @NonNull
    public static Executor getWorkerThreadExecutor() {
        return WORKER_THREAD_EXECUTOR_SUPPLIER.get();
    }

    /** True when the current thread has a looper and it is the main looper. */
    public static boolean isMainThread() {
        final Looper looper = Looper.myLooper();
        return looper != null && looper == Looper.getMainLooper();
    }
}
