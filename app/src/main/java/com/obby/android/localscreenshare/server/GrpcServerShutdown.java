package com.obby.android.localscreenshare.server;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Ordered teardown of the gRPC server and its callback executor.
 *
 * <p>gRPC's {@code Server.shutdownNow()} closes transports on the transport threads; each close
 * posts a context-closer task back to the application executor. Shutting that executor down before
 * the transport shutdown finishes makes a rejecting executor throw
 * {@code RejectedExecutionException} and crash the process when a share is stopped while a client
 * is streaming. This helper shuts the server down, waits for its termination so the asynchronous
 * transport shutdown finishes posting its close callbacks, and only then shuts the executor down.
 * It also provides the non-rejecting handler used to build the executor, as a safety net.</p>
 */
public final class GrpcServerShutdown {

    /**
     * Shutdown surface of the gRPC server, narrowed to what the ordered teardown needs.
     */
    public interface ServerHandle {
        /**
         * Initiates an abrupt shutdown that terminates every active call.
         */
        void shutdownNow();

        /**
         * Waits up to {@code timeoutMs} for the server to terminate.
         */
        boolean awaitTermination(long timeoutMs) throws InterruptedException;
    }

    private GrpcServerShutdown() {
    }

    /**
     * Returns the rejection handler for the server callback pool.
     *
     * <p>A late close callback posted after the pool has been shut down is discarded instead of
     * throwing, because the callback only cancels a per-call context for a stream that is already
     * closing.</p>
     */
    public static RejectedExecutionHandler rejectionHandler() {
        return new ThreadPoolExecutor.DiscardPolicy();
    }

    /**
     * Shuts the server down, waits for its termination, then shuts the executor down.
     *
     * <p>The wait ensures the asynchronous transport shutdown has finished posting its close
     * callbacks before the executor is terminated, so the executor never rejects a live callback.
     * An interrupt is recorded and the teardown still proceeds, so a stop is never abandoned.</p>
     */
    public static void shutdown(ServerHandle server, ExecutorService executor, long timeoutMs) {
        server.shutdownNow();
        try {
            server.awaitTermination(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor.shutdownNow();
    }
}
