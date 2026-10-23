package com.obby.android.localscreenshare.core.model;

/**
 * One viewer-side backlog report carried on the control plane.
 *
 * <p>The values are peaks of the reporting window, not instantaneous readings, because a single
 * low sample in a burst would otherwise hide the queue. The model is immutable and rejects negative
 * values: a report is a measurement, and a negative one can only come from a malformed peer or a
 * broken accumulator. {@link #EMPTY} is the all-zero report used when nothing was sampled.</p>
 *
 * <p>The field carries no viewer identity: the stream already identifies the client. The client
 * uptime is diagnostics only and never influences a server decision.</p>
 */
public final class StreamFeedback {

    /**
     * All-zero report: no queue depth, no catch-up and no wait was observed.
     */
    public static final StreamFeedback EMPTY = new StreamFeedback(0, 0, 0, 0L);

    private final int queueDepthPeak;

    private final int catchUpEvents;

    private final int queueWaitPeakMs;

    private final long clientUptimeMs;

    /**
     * Creates a report.
     *
     * @param queueDepthPeak  highest decode-queue depth sampled at dequeue time
     * @param catchUpEvents   catch-up episodes started in the window
     * @param queueWaitPeakMs longest arrival-to-decode-start wait in the window, in milliseconds
     * @param clientUptimeMs  viewer uptime when the report was sampled, for diagnostics
     * @throws IllegalArgumentException if any argument is negative
     */
    public StreamFeedback(int queueDepthPeak, int catchUpEvents, int queueWaitPeakMs,
                          long clientUptimeMs) {
        if (queueDepthPeak < 0) {
            throw new IllegalArgumentException("queueDepthPeak must be >= 0");
        }
        if (catchUpEvents < 0) {
            throw new IllegalArgumentException("catchUpEvents must be >= 0");
        }
        if (queueWaitPeakMs < 0) {
            throw new IllegalArgumentException("queueWaitPeakMs must be >= 0");
        }
        if (clientUptimeMs < 0) {
            throw new IllegalArgumentException("clientUptimeMs must be >= 0");
        }
        this.queueDepthPeak = queueDepthPeak;
        this.catchUpEvents = catchUpEvents;
        this.queueWaitPeakMs = queueWaitPeakMs;
        this.clientUptimeMs = clientUptimeMs;
    }

    /**
     * Returns the highest decode-queue depth sampled at dequeue time.
     */
    public int getQueueDepthPeak() {
        return queueDepthPeak;
    }

    /**
     * Returns the catch-up episodes started in the reporting window.
     */
    public int getCatchUpEvents() {
        return catchUpEvents;
    }

    /**
     * Returns the longest arrival-to-decode-start wait in the reporting window, in milliseconds.
     */
    public int getQueueWaitPeakMs() {
        return queueWaitPeakMs;
    }

    /**
     * Returns the viewer uptime when the report was sampled, in milliseconds.
     */
    public long getClientUptimeMs() {
        return clientUptimeMs;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof StreamFeedback)) {
            return false;
        }
        StreamFeedback that = (StreamFeedback) other;
        return queueDepthPeak == that.queueDepthPeak
                && catchUpEvents == that.catchUpEvents
                && queueWaitPeakMs == that.queueWaitPeakMs
                && clientUptimeMs == that.clientUptimeMs;
    }

    @Override
    public int hashCode() {
        int result = queueDepthPeak;
        result = 31 * result + catchUpEvents;
        result = 31 * result + queueWaitPeakMs;
        result = 31 * result + (int) (clientUptimeMs ^ (clientUptimeMs >>> 32));
        return result;
    }

    @Override
    public String toString() {
        return "StreamFeedback{queueDepthPeak=" + queueDepthPeak
                + ", catchUpEvents=" + catchUpEvents
                + ", queueWaitPeakMs=" + queueWaitPeakMs
                + ", clientUptimeMs=" + clientUptimeMs + '}';
    }
}
