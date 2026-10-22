package com.obby.android.localscreenshare.core.render;

/**
 * Pure decision policy for presenting one decoded frame on the client.
 *
 * <p>The decoder output loop runs faster than the display consumes frames, so "render every output
 * as soon as it is dequeued" makes the picture follow the network instead of the stream clock. This
 * policy decides for one output whether it should be shown immediately, handed to the platform with
 * its target timestamp, or released without rendering.</p>
 *
 * <p><b>Rules.</b> The first frame of a decode run (nothing presented since the last configuration
 * or flush) is presented immediately when its mapped target lies more than
 * {@link #MAX_FIRST_FRAME_DEFERRAL_NS} ahead: such a lead is a clock artefact rather than an
 * alignment instruction, and waiting for it would leave the output surface showing whatever its
 * buffers held before (uninitialised content when no frame has ever been latched, which is the green
 * first frame a viewer sees; the abandoned picture of the previous run after a flush). A smaller
 * lead is ordinary audio/video alignment and is scheduled like any other frame, so the two tracks
 * stay together. A frame older than the last presented frame is dropped: showing it would make the
 * picture jump backwards, and it can only appear after a reordering or a stream restart. With a
 * non-zero minimum interval, a frame that would be shown less than that interval after the previous
 * one is dropped, which caps the local display rate; a zero interval disables the cap, so every
 * frame that is not stale is presented. Any later target in the future is scheduled at its timestamp
 * so the platform holds it for the right moment; a target at or before the current time is presented
 * now, so a late frame never builds up latency.</p>
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
     * Largest lead the first picture of a decode run may wait for, in nanoseconds (100 ms).
     *
     * <p>A first picture whose mapped target is a few frame periods ahead is ordinary audio/video
     * alignment: the shared session clock was anchored by the other track and this picture belongs
     * slightly later, so waiting keeps the two tracks together. A lead beyond this bound cannot come
     * from pacing - no producer leads the timeline it was anchored on by a tenth of a second without
     * a clock artefact - and waiting for it would leave the output surface on the content it held
     * before: uninitialised buffers when no picture was ever latched, which is the green first frame
     * a viewer sees, and the abandoned picture of the previous run after a flush.</p>
     */
    public static final long MAX_FIRST_FRAME_DEFERRAL_NS = 100_000_000L;

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
        if (lastPresentedNs == UNSET && targetNs - nowNs > MAX_FIRST_FRAME_DEFERRAL_NS) {
            // First picture of the decode run, mapped beyond the deferral bound: the lead is a clock
            // artefact, not an alignment instruction, so show the picture now instead of waiting on
            // a surface that still holds uninitialised content.
            return Decision.PRESENT_NOW;
        }

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
