package com.obby.android.localscreenshare.core.render;

/**
 * Pure decision policy for presenting one decoded frame on the client.
 *
 * <p>The decoder output loop runs faster than the display consumes frames, so "render every output
 * as soon as it is dequeued" makes the picture follow the network instead of the stream clock. This
 * policy decides for one output whether it should be shown immediately, handed to the platform with
 * its target timestamp, or released without rendering.</p>
 *
 * <p><b>Rules.</b> A frame older than the last presented frame is dropped: showing it would make the
 * picture jump backwards, and it can only appear after a reordering or a stream restart. With a
 * non-zero minimum interval, a frame that would be shown less than that interval after the previous
 * one is dropped, which caps the local display rate; a zero interval disables the cap, so every
 * frame that is not stale is presented. A target in the future is scheduled at its timestamp so the
 * platform holds it for the right moment; a target at or before the current time is presented now,
 * so a late frame never builds up latency.</p>
 *
 * <p><b>Time base.</b> All arguments are nanoseconds on the same monotonic clock. The caller maps
 * stream timestamps onto that clock before calling. {@link #UNSET} marks "no frame presented yet".
 * A baseline that lies in the future of the current time means the clock moved backwards, or that
 * the previous frame was scheduled slightly ahead; it carries no information about the new time
 * base, so both the stale check and the interval check are skipped instead of stalling the stream
 * until the clock catches up.</p>
 *
 * <p>The policy is stateless, so the same arguments always produce the same decision.</p>
 */
public final class PresentationPacer {

    /**
     * Sentinel for "nothing presented yet".
     */
    public static final long UNSET = Long.MIN_VALUE;

    /**
     * What to do with one decoded output.
     */
    public enum Decision {

        /**
         * Render the frame immediately; its target time is at or before now.
         */
        PRESENT_NOW,

        /**
         * Render the frame at its target time; the platform holds it until then.
         */
        SCHEDULE_AT,

        /**
         * Release the frame without rendering it.
         */
        DROP
    }

    /**
     * Decides what to do with one decoded output.
     *
     * @param nowNs           current monotonic time in nanoseconds
     * @param targetNs        presentation time of the frame on the same clock
     * @param lastPresentedNs presentation time of the last shown frame, or {@link #UNSET}
     * @param minIntervalNs   minimum spacing between two shown frames; zero disables the cap
     * @return the decision; never null
     */
    public Decision decide(long nowNs, long targetNs, long lastPresentedNs, long minIntervalNs) {
        // A baseline in the future means the clock moved backwards (or a frame was scheduled
        // slightly ahead): the old baseline says nothing about the new time base, so it is ignored
        // instead of stalling the stream until the clock catches up.
        final boolean hasLast = lastPresentedNs != UNSET && nowNs >= lastPresentedNs;

        if (hasLast && targetNs < lastPresentedNs) {
            // Stale: this frame belongs before the one already on screen.
            return Decision.DROP;
        }

        if (hasLast && minIntervalNs > 0) {
            final long presentationNs = Math.max(nowNs, targetNs);
            if (presentationNs - lastPresentedNs < minIntervalNs) {
                return Decision.DROP;
            }
        }

        return targetNs > nowNs ? Decision.SCHEDULE_AT : Decision.PRESENT_NOW;
    }
}
