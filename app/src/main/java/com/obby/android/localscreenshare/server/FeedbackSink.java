package com.obby.android.localscreenshare.server;

import com.obby.android.localscreenshare.core.model.StreamFeedback;

/**
 * The only backpressure view a stream call owns.
 *
 * <p>The handler depends on this interface instead of the coordinator so a test can record offers
 * without starting the coordinator's executor, and so a call can be wired to
 * {@link BackpressureCoordinator#NONE} without a null check at every report.</p>
 */
public interface FeedbackSink {

    /**
     * Records the newest report and schedules one apply pass.
     *
     * <p>Never blocks, never calls an encoder and never throws. When a pass is already scheduled the
     * report replaces the pending one, so only the newest report is applied.</p>
     *
     * @param feedback report to apply; null is ignored
     * @param target   encoder seam the report applies to; null is ignored
     */
    void offer(StreamFeedback feedback, BitrateTarget target);

    /**
     * Stops the executor within a bounded wait; idempotent.
     *
     * <p>Offers after this call are no-ops: they schedule nothing, create no thread, write no metric
     * and call no encoder.</p>
     */
    void close();
}
