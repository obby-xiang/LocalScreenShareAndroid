package com.obby.android.localscreenshare.client;

import com.obby.android.localscreenshare.core.client.CatchUpSampler;
import com.obby.android.localscreenshare.core.client.ClientMediaSession;
import com.obby.android.localscreenshare.core.metrics.PipelineMetrics;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Accounts one externally detected loss against the viewer's shared catch-up state.
 *
 * <p>A dedicated media channel that lost a whole access unit reports the loss from its own
 * thread, while the accounting belongs on the decode thread next to the session it flushes. This
 * collaborator owns the three steps so the client endpoint only wires them up: it captures the
 * loss moment when the task is submitted, asks the session to resynchronize, and updates the
 * catch-up generation, the sampler episode and the {@code rx.catchup} metrics.</p>
 *
 * <p>The session gate comes first. Before the first descriptor configured a decoder, or after the
 * session was closed, there is no backlog to discard and no join point will ever arrive; opening
 * an episode there would leave the sampler in {@code CATCHING_UP} until its timeout, suppressing
 * every backlog sample in between, and would report a catch-up that never happened.</p>
 */
final class ExternalCatchUpRecorder {

    private final CatchUpSampler sampler;

    private final PipelineMetrics metrics;

    // Bumped once a loss episode really discarded the queued units; the transport and decode
    // threads use it to drop tasks submitted before the episode.
    private final AtomicInteger generation;

    ExternalCatchUpRecorder(CatchUpSampler sampler, PipelineMetrics metrics,
                            AtomicInteger generation) {
        this.sampler = sampler;
        this.metrics = metrics;
        this.generation = generation;
    }

    /**
     * Captures the detection time and submits the accounting to the decode thread.
     *
     * <p>The timestamp is taken before the task is queued, so a task that waits behind a slow
     * frame still reports the moment the loss was detected instead of the moment the accounting
     * happened to run.</p>
     *
     * @param decodeExecutor executor that owns the single decode thread
     * @param session        session the loss belongs to
     * @param isStopped      lifecycle flag checked at the start of the task
     */
    void submit(Executor decodeExecutor, ClientMediaSession session, BooleanSupplier isStopped) {
        final long lossNs = metrics.nowNs();
        decodeExecutor.execute(() -> {
            if (isStopped.getAsBoolean()) {
                return;
            }
            onLossDetected(session, lossNs);
        });
    }

    /**
     * Applies one loss on the decode thread.
     *
     * <p>The session gate runs first: a session without a decoder (before its first descriptor)
     * or a closed one cannot resynchronize, so it must not open an episode either. Only a session
     * that really flushed its decoder takes the generation bump, which discards the units already
     * queued behind the loss.</p>
     *
     * @return true when the loss was applied to the session
     */
    boolean onLossDetected(ClientMediaSession session, long lossNs) {
        if (!session.onCatchUp()) {
            return false;
        }
        generation.incrementAndGet();
        if (sampler.onExternalCatchUp(lossNs)) {
            metrics.increment(PipelineMetrics.Counter.CATCH_UP_EPISODES);
            metrics.setGauge(PipelineMetrics.Gauge.CATCH_UP_REASON,
                PipelineMetrics.CATCH_UP_REASON_EXTERNAL);
        }
        return true;
    }
}
