package com.obby.android.localscreenshare.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.obby.android.localscreenshare.core.metrics.PipelineMetrics;
import com.obby.android.localscreenshare.core.model.StreamFeedback;
import com.obby.android.localscreenshare.core.support.MutableClock;
import com.obby.android.localscreenshare.core.support.MutableNanoClock;
import com.obby.android.localscreenshare.core.util.TimeSource;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Unit tests for {@link BackpressureCoordinator}.
 *
 * <p>Every case offers first and then drains a hand-cranked executor, so the queue size and the
 * exact pass that applies a report can be asserted. The coordinator is subclassed only to capture
 * its log calls; the production class never writes a log line from a test.</p>
 */
public class BackpressureCoordinatorTest {

    private static final int CEILING_BPS = 4_000_000;

    @Test
    public void offer_appliesOnlyTheNewestReport() {
        QueueExecutor executor = new QueueExecutor();
        FakeTarget target = new FakeTarget();
        MutableClock clock = new MutableClock(10_000L);
        RecordingCoordinator coordinator = coordinator(executor, clock, target);

        coordinator.offer(feedback(1), target);
        coordinator.offer(feedback(2), target);
        coordinator.offer(feedback(3), target);

        assertEquals(1, executor.size());
        executor.drain();
        assertEquals(1, target.applyCount);
        assertTrue(coordinator.lastActionLog.contains("depth=3"));
    }

    @Test
    public void offer_recordsApplyAndBitrateMetrics() {
        QueueExecutor executor = new QueueExecutor();
        FakeTarget target = new FakeTarget();
        PipelineMetrics metrics = PipelineMetrics.create(new MutableNanoClock(), 16);
        RecordingCoordinator coordinator = new RecordingCoordinator(metrics, new MutableClock(),
                () -> true, executor);

        coordinator.offer(feedback(8), target);
        executor.drain();

        assertEquals(1L, metrics.count(PipelineMetrics.Counter.BACKPRESSURE_APPLIED));
        assertEquals(3_000_000L, metrics.getGauge(PipelineMetrics.Gauge.BACKPRESSURE_TARGET_BITRATE));
    }

    @Test
    public void offer_containsEncoderFailure() {
        QueueExecutor executor = new QueueExecutor();
        FakeTarget target = new FakeTarget();
        target.failure = new IllegalStateException("encoder refused");
        PipelineMetrics metrics = PipelineMetrics.create(new MutableNanoClock(), 16);
        RecordingCoordinator coordinator = new RecordingCoordinator(metrics, new MutableClock(),
                () -> true, executor);

        coordinator.offer(feedback(8), target);
        executor.drain();

        assertEquals(0, target.applyCount);
        assertEquals(1L, metrics.count(PipelineMetrics.Counter.BACKPRESSURE_SKIPPED));
    }

    @Test
    public void offer_rateLimitsFailureLog() {
        QueueExecutor executor = new QueueExecutor();
        FakeTarget target = new FakeTarget();
        target.failure = new IllegalStateException("encoder refused");
        MutableClock clock = new MutableClock();
        RecordingCoordinator coordinator = coordinator(executor, clock, target);

        coordinator.offer(feedback(8), target);
        executor.drain();
        clock.advance(59_000L);
        coordinator.offer(feedback(8), target);
        executor.drain();

        assertEquals(1, coordinator.failureLogs);
    }

    @Test
    public void offer_skipsWhenDisabled() {
        QueueExecutor executor = new QueueExecutor();
        FakeTarget target = new FakeTarget();
        AtomicBoolean enabled = new AtomicBoolean(false);
        PipelineMetrics metrics = PipelineMetrics.create(new MutableNanoClock(), 16);
        RecordingCoordinator coordinator = new RecordingCoordinator(metrics, new MutableClock(),
                enabled::get, executor);

        coordinator.offer(feedback(8), target);
        executor.drain();
        assertEquals(0, target.applyCount);
        assertEquals(1L, metrics.count(PipelineMetrics.Counter.BACKPRESSURE_SKIPPED));

        enabled.set(true);
        coordinator.offer(feedback(8), target);
        executor.drain();
        assertEquals(1, target.applyCount);
    }

    @Test
    public void offer_afterCloseIsNoOp() {
        QueueExecutor executor = new QueueExecutor();
        FakeTarget target = new FakeTarget();
        RecordingCoordinator coordinator = coordinator(executor, new MutableClock(), target);

        coordinator.offer(feedback(8), target);
        coordinator.close();
        coordinator.close();
        coordinator.offer(feedback(8), target);
        executor.drain();

        assertEquals(0, target.applyCount);

        PipelineMetrics metrics = PipelineMetrics.create(new MutableNanoClock(), 16);
        BackpressureCoordinator.NONE.offer(feedback(8), target);
        BackpressureCoordinator.NONE.close();
        assertEquals(0, target.applyCount);
        assertEquals(0L, metrics.count(PipelineMetrics.Counter.BACKPRESSURE_APPLIED));
    }

    @Test
    public void offer_multiClientRestoresOnce() {
        QueueExecutor executor = new QueueExecutor();
        FakeTarget target = new FakeTarget();
        MutableClock clock = new MutableClock();
        RecordingCoordinator coordinator = coordinator(executor, clock, target);

        coordinator.offer(feedback(8), target);
        executor.drain();
        assertEquals(1, target.applyCount);
        assertEquals(3_000_000, target.bitrateBps);

        clock.advance(5_000L);
        target.sessionCount = 2;
        coordinator.offer(feedback(0), target);
        executor.drain();
        assertEquals(2, target.applyCount);
        assertEquals(CEILING_BPS, target.bitrateBps);

        coordinator.offer(feedback(0), target);
        executor.drain();
        assertEquals(2, target.applyCount);
    }

    @Test
    public void offer_externalWriteClearsOverride() {
        QueueExecutor executor = new QueueExecutor();
        FakeTarget target = new FakeTarget();
        MutableClock clock = new MutableClock();
        RecordingCoordinator coordinator = coordinator(executor, clock, target);

        coordinator.offer(feedback(8), target);
        executor.drain();
        assertEquals(1, target.applyCount);

        clock.advance(5_000L);
        target.bitrateBps = 2_500_000;
        coordinator.offer(feedback(0), target);
        executor.drain();
        assertEquals(1, target.applyCount);

        clock.advance(5_000L);
        coordinator.offer(feedback(0), target);
        executor.drain();
        assertEquals(1, target.applyCount);
    }

    private static RecordingCoordinator coordinator(QueueExecutor executor, MutableClock clock,
                                                    FakeTarget target) {
        return new RecordingCoordinator(PipelineMetrics.NONE, clock, () -> true, executor);
    }

    private static StreamFeedback feedback(int depth) {
        return new StreamFeedback(depth, 0, 0, 0L);
    }

    /**
     * Runs queued tasks only when the test drains it.
     */
    private static final class QueueExecutor implements Executor {

        private final Deque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        int size() {
            return tasks.size();
        }

        void drain() {
            while (!tasks.isEmpty()) {
                tasks.poll().run();
            }
        }
    }

    /**
     * Records the log calls of the coordinator instead of writing them.
     */
    private static class RecordingCoordinator extends BackpressureCoordinator {

        int failureLogs;

        String lastActionLog = "";

        RecordingCoordinator(PipelineMetrics metrics, TimeSource timeSource,
                             java.util.function.BooleanSupplier enabled, Executor executor) {
            super(metrics, timeSource, enabled, executor);
        }

        @Override
        protected void logFailure(String message) {
            failureLogs++;
        }

        @Override
        protected void logAction(String message) {
            lastActionLog = message;
        }
    }

    /**
     * Encoder seam with a read-back value the test can change to simulate an external write.
     */
    private static final class FakeTarget implements BitrateTarget {

        int sessionCount = 1;

        String codecId = "h264";

        int bitrateBps = CEILING_BPS;

        int applyCount;

        RuntimeException failure;

        @Override
        public int getSessionCount() {
            return sessionCount;
        }

        @Override
        public String getCodecId() {
            return codecId;
        }

        @Override
        public int getTargetBitrateBps() {
            return bitrateBps;
        }

        @Override
        public void applyTargetBitrate(int bitrateBps) {
            if (failure != null) {
                throw failure;
            }
            applyCount++;
            this.bitrateBps = bitrateBps;
        }
    }
}
