package com.obby.android.localscreenshare.core.client;

/**
 * Single sampling entry point for the viewer's decode backlog and loss episodes.
 *
 * <p>Both receive paths of the viewer - the gRPC stream and the dedicated media channel - call
 * {@link #onDecoded(long, int, long, boolean)} for every unit they hand to the decoder, and the
 * datagram loss path calls {@link #onExternalCatchUp(long)}. Keeping one sampler per viewer is
 * what makes {@code rx.catchup} mean the same thing on every transport: before this class existed
 * only the gRPC path sampled, while the media channel recorded the queue wait and never decided
 * anything, and a lost datagram bypassed the decision entirely.</p>
 *
 * <p><b>Order is part of the contract.</b> A decoded unit ends the current episode first when it
 * is the join point, and only then is the backlog sampled. The same unit may therefore both end
 * one episode and start the next one; a single enum could express only one of the two. The
 * existing cooldown still keeps a just-ended join point from immediately starting another
 * episode.</p>
 *
 * <p><b>External episodes.</b> A datagram channel that lost a whole unit opens an episode without
 * a backlog sample. External episodes are merged into an open episode rather than counted twice,
 * never consume the consecutive budget, never set the cooldown and never start the convergence
 * timer: a loss is a network fault, not a decoder deficit, and the 30 s hold of {@code DV-027} is
 * not a settled trade-off. They are ignored while the policy holds off after a convergence
 * failure, so a loss cannot shorten that hold.</p>
 *
 * <p><b>Cost and threading.</b> Not thread-safe: the owning client samples it from its single
 * decode thread. The ordinary path allocates nothing, concatenates no strings, takes no lock and
 * reads no clock; the caller passes the timestamp it already took. A result object is only
 * allocated when an episode ends or starts, which is at most once per episode.</p>
 */
public final class CatchUpSampler {

    /**
     * What one decoded unit did to the catch-up state machine.
     *
     * <p>Instances are immutable. The ordinary path returns a shared instance whose fields are
     * both neutral, so it allocates nothing while the queue is healthy.</p>
     */
    public static final class Result {

        /**
         * Whether this unit was accepted as the join point that ended the current episode.
         */
        public final boolean finishedEpisode;

        /**
         * What the backlog sample of this unit asks the caller to do.
         */
        public final CatchUpPolicy.Decision decision;

        private static final Result NONE = new Result(false, CatchUpPolicy.Decision.NONE);

        private Result(boolean finishedEpisode, CatchUpPolicy.Decision decision) {
            this.finishedEpisode = finishedEpisode;
            this.decision = decision;
        }
    }

    private static final long UNSET = Long.MIN_VALUE;

    private final CatchUpPolicy mPolicy = new CatchUpPolicy();

    // Start of the open episode; meaningful only while mPolicy.isCatchingUp() is true, because a
    // timed-out episode leaves the moment behind without ending through a join point.
    private long mEpisodeStartedNs = UNSET;

    private long mSampleCount;

    public CatchUpSampler() {
    }

    /**
     * Samples one decoded unit.
     *
     * <p>While an episode is open and the session is streaming again, this unit is its join point:
     * the episode ends before the backlog is sampled, so the same unit can still start the next
     * one. While an episode is open and the session is not streaming, the sample is ignored, which
     * is the existing behaviour of the policy.</p>
     *
     * @param nowNs       monotonic time of the sample, in nanoseconds
     * @param queueDepth  decode-queue depth observed with this unit, in units
     * @param queueWaitNs time this unit waited before decoding started, in nanoseconds
     * @param streaming   whether the session is streaming again, so an open episode ends here
     * @return what this unit did and what the caller should do; never null
     */
    public Result onDecoded(long nowNs, int queueDepth, long queueWaitNs, boolean streaming) {
        mSampleCount++;

        final boolean finished;
        if (mPolicy.isCatchingUp() && streaming) {
            mPolicy.onJoinPoint(nowNs);
            finished = true;
        } else {
            finished = false;
        }

        final CatchUpPolicy.Decision decision = mPolicy.onSample(nowNs, queueDepth, queueWaitNs);
        if (decision == CatchUpPolicy.Decision.START_CATCH_UP) {
            mEpisodeStartedNs = nowNs;
        } else if (finished && !mPolicy.isCatchingUp()) {
            mEpisodeStartedNs = UNSET;
        }

        if (!finished && decision == CatchUpPolicy.Decision.NONE) {
            return Result.NONE;
        }
        return new Result(finished, decision);
    }

    /**
     * Opens an episode caused by something other than backlog, such as a lost whole access unit.
     *
     * <p>An open episode merges this event instead of counting a second episode, and the merge does
     * not refresh the episode start, so repeated loss cannot prolong one episode indefinitely. An
     * external episode never increments the consecutive count, never sets the cooldown and never
     * starts the convergence timer. While the policy holds off after a convergence failure the
     * event is ignored: the hold outranks a loss episode.</p>
     *
     * @param nowNs monotonic time of the loss, in nanoseconds
     * @return true when this call opened a new episode
     */
    public boolean onExternalCatchUp(long nowNs) {
        if (mPolicy.isCatchingUp()) {
            return false;
        }
        mPolicy.onExternalCatchUp(nowNs);
        if (!mPolicy.isCatchingUp()) {
            return false;
        }
        mEpisodeStartedNs = nowNs;
        return true;
    }

    /**
     * Returns whether an episode is open and waiting for its join point.
     */
    public boolean isCatchingUp() {
        return mPolicy.isCatchingUp();
    }

    /**
     * Returns the start of the open episode, or {@link Long#MIN_VALUE} when no episode is open.
     */
    public long getEpisodeStartedNs() {
        return mPolicy.isCatchingUp() ? mEpisodeStartedNs : UNSET;
    }

    /**
     * Returns which signal detected the latest episode, or {@link CatchUpPolicy.Reason#NONE}
     * before the first one.
     */
    public CatchUpPolicy.Reason getLastReason() {
        return mPolicy.getLastReason();
    }

    /**
     * Returns how many load catch-ups ran without a converged period in between.
     */
    public int getConsecutiveCatchUps() {
        return mPolicy.getConsecutiveCatchUps();
    }

    /**
     * Returns how many units were sampled; diagnostics only, never used for a decision.
     */
    public long getSampleCount() {
        return mSampleCount;
    }
}
