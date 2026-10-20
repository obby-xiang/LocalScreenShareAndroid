package com.obby.android.localscreenshare.server;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for the ordered gRPC server teardown.
 *
 * <p>The crash this guards against: {@code ScreenShareServer.stop()} shut the callback executor
 * down immediately after {@code Server.shutdownNow()}. The transport shutdown runs asynchronously
 * on the transport threads, and each closed stream posts a context-closer task back to the
 * callback executor; with {@link ThreadPoolExecutor.AbortPolicy} that late post threw
 * {@code RejectedExecutionException} and killed the process. The fix orders the teardown and uses
 * a non-rejecting handler.</p>
 */
public class GrpcServerShutdownTest {

    @Test
    public void shutdown_ordersServerBeforeExecutor() {
        List<String> events = new ArrayList<>();
        GrpcServerShutdown.ServerHandle server = new GrpcServerShutdown.ServerHandle() {
            @Override
            public void shutdownNow() {
                events.add("server.shutdownNow");
            }

            @Override
            public boolean awaitTermination(long timeoutMs) {
                events.add("server.awaitTermination");
                return true;
            }
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>()) {
            @Override
            public List<Runnable> shutdownNow() {
                events.add("executor.shutdownNow");
                return super.shutdownNow();
            }
        };

        GrpcServerShutdown.shutdown(server, executor, 2_000L);

        assertEquals(Arrays.asList("server.shutdownNow", "server.awaitTermination",
                "executor.shutdownNow"), events);
        assertTrue(executor.isShutdown());
    }

    @Test
    public void rejectionHandler_discardsLateTasksInsteadOfThrowing() {
        RejectedExecutionHandler handler = GrpcServerShutdown.rejectionHandler();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(1), handler);
        executor.shutdownNow();

        // A late close callback must not throw RejectedExecutionException.
        executor.execute(() -> { });

        assertTrue(executor.isShutdown());
    }
}
