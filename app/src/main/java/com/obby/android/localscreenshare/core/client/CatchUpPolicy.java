package com.obby.android.localscreenshare.core.client;

/**
 * Decides when a viewer gives up its decode backlog and resynchronizes on a fresh join point.
 *
 * <p><b>Why a policy is needed.</b> A decoder that falls behind real time cannot catch up by
 * itself: the decode queue stays full, every queued access unit becomes staler, and the picture
 * lags the producer by the whole residency of the queue. Trimming the queue to its newest frame is
 * not an option for H.264 either, because a P frame whose references were dropped is undecodable.
 * The only bounded recovery is a <i>catch-up</i>: discard every queued access unit, ask the
 * encoder for an IDR and resume decoding on it. This class decides when that is worth doing, how
 * often it may be attempted, and when the attempts must stop.</p>
 *
 * <p><b>Signals.</b> One sample is one access unit that reached the decode thread: the time it
 * waited between submission and decode start (the value reported as {@code rx.qWait}) plus the
 * decode-queue depth at that moment. A backlog is one of two conditions, and the reason is kept so
 * a report can tell which one fired:</p>
 *
 * <ul>
 *   <li>{@link #BACKLOG_WAIT_NANOS}: the unit already waited longer than this before decoding
 *       started, which is a direct measurement of the lag the user sees;</li>
 *   <li>{@link #BACKLOG_QUEUE_DEPTH}: the queue holds at least this many units, which catches a
 *       backlog whose wait samples are still small because the queue filled up just now.</li>
 * </ul>
 *
 * <p><b>Hysteresis.</b> A single slow frame is ordinary jitter on a Wi-Fi link and must not
 * discard a GOP, so a backlog has to persist for {@link #SUSTAIN_NANOS} before it triggers. Two
 * catch-ups closer together than {@link #COOLDOWN_NANOS} cannot both run, because each one costs a
 * key frame and a visible reset. A stream that keeps falling back into backlog after
 * {@link #MAX_CONSECUTIVE_CATCH_UPS} attempts is not recoverable by dropping frames at all - the
 * decoder is simply slower than the stream - so the next attempt is aborted and reported as a
 * convergence failure, and further attempts are held off for {@link #HOLD_NANOS} instead of
 * resetting the picture every few seconds. A stream that stays clear for
 * {@link #CONVERGED_CLEAR_NANOS} after a catch-up counts as converged, which resets the consecutive
 * count. An episode that never sees a join point expires after
 * {@link #CATCH_UP_TIMEOUT_NANOS}, so a lost key-frame request cannot disable the mechanism for the
 * rest of the session.</p>
 *
 * <p><b>External episodes.</b> {@link #onExternalCatchUp(long)} opens the same kind of episode for
 * a fault that is not backlog, such as a lost whole access unit. It merges into an open episode
 * without refreshing its start and never touches the consecutive count, the cooldown or the
 * convergence timer, so a network fault cannot spend the hold budget that belongs to decoder
 * deficits. It is ignored while the policy holds off after a convergence failure.</p>
 *
 * <p>All thresholds are starting values <b>pending device calibration</b>: they are public so a
 * verification report can quote exactly what it measured against, and they are deliberately
 * conservative - a missed catch-up costs one more lagging second, a needless one costs a visible
 * reset and a larger key frame.</p>
 *
 * <p><b>Threading and cost.</b> Not thread-safe: the owning client samples from its single decode
 * thread. A sample costs a handful of {@code long} comparisons, allocates nothing and reads no
 * clock, because the caller passes the timestamp it already took. {@link #isSuperseded(int, int,
 * boolean)} is a pure predicate the transport thread may call without coordination.</p>
 */
public final class CatchUpPolicy {

    /**
     * Queue wait above which a unit counts as backlogged, in nanoseconds; pending device
     * calibration.
     */
    public static final long BACKLOG_WAIT_NANOS = 1_000_000_000L;

    /**
     * Decode-queue depth at or above which the queue counts as backlogged, in units; pending
     * device calibration. Sixty-four units are roughly two seconds at thirty frames per second,
     * which is long enough to be a backlog and short enough to stay far from the queue capacity.
     */
    public static final int BACKLOG_QUEUE_DEPTH = 64;

    /**
     * How long a backlog must persist before a catch-up is allowed, in nanoseconds; pending device
     * calibration.
     */
    public static final long SUSTAIN_NANOS = 2_000_000_000L;

    /**
     * Minimum spacing between the starts of two catch-ups, in nanoseconds; pending device
     * calibration.
     */
    public static final long COOLDOWN_NANOS = 5_000_000_000L;

    /**
     * Number of catch-ups that may run back to back before the stream is declared unconverged;
     * pending device calibration.
     */
    public static final int MAX_CONSECUTIVE_CATCH_UPS = 3;

    /**
     * How long the backlog must stay clear after a catch-up for it to count as converged, in
     * nanoseconds; pending device calibration.
     */
    public static final long CONVERGED_CLEAR_NANOS = 5_000_000_000L;

    /**
     * How long catch-up attempts are held off after a convergence failure, in nanoseconds; pending
     * device calibration. Smoothness wins here: the picture keeps playing late instead of being
     * reset every few seconds.
     */
    public static final long HOLD_NANOS = 30_000_000_000L;

    /**
     * How long a catch-up may wait for its join point before the episode is abandoned, in
     * nanoseconds; pending device calibration.
     */
    public static final long CATCH_UP_TIMEOUT_NANOS = 3_000_000_000L;

    private static final long UNSET = Long.MIN_VALUE;

    /**
     * What a sample asks the caller to do.
     */
    public enum Decision {

        /**
         * Keep decoding; no state change.
         */
        NONE,

        /**
         * Discard the backlog, request a join point and resume on it.
         */
        START_CATCH_UP,

        /**
         * The stream did not converge after the allowed attempts; report the event and stop
         * attempting for {@link #HOLD_NANOS}.
         */
        CONVERGENCE_FAILED
    }

    /**
     * Which signal detected the backlog of the latest episode.
     */
    public enum Reason {

        /**
         * No episode has started yet.
         */
        NONE,

        /**
         * The queue wait of an access unit exceeded {@link #BACKLOG_WAIT_NANOS}.
         */
        QUEUE_WAIT,

        /**
         * The queue depth reached {@link #BACKLOG_QUEUE_DEPTH}.
         */
        QUEUE_DEPTH,

        /**
         * The episode was opened by something other than backlog, such as a lost whole access
         * unit. It never consumes the consecutive budget.
         */
        EXTERNAL
    }

    /**
     * Where the episode state machine currently is.
     */
    private enum State {

        /**
         * Watching the queue; a catch-up may start.
         */
        IDLE,

        /**
         * Waiting for the join point of the current episode.
         */
        CATCHING_UP,

        /**
         * Holding off after a convergence failure.
         */
        HOLD
    }

    private State state = State.IDLE;

    private Reason lastReason = Reason.NONE;

    // Moment the current backlog was first observed; UNSET while the queue is clear.
    private long sustainedSinceNs = UNSET;

    // First clear sample after a catch-up, used to detect convergence; UNSET while backlogged.
    private long clearSinceNs = UNSET;

    // Start of the latest catch-up, or the latest hold; both gate the next attempt.
    private long lastCatchUpAtNs = UNSET;

    private long catchUpSinceNs = UNSET;

    private long holdSinceNs = UNSET;

    private int consecutiveCatchUps;

    /**
     * Feeds one decoded access unit and returns what the caller should do about it.
     *
     * <p>The sample is ignored while a catch-up waits for its join point and while attempts are
     * held off, so the caller can sample unconditionally after every decoded unit. While a
     * catch-up waits, an expired episode is abandoned first, so a stream that never produced a
     * join point is detected again instead of being written off.</p>
     *
     * @param nowNs       monotonic time of the sample, in nanoseconds
     * @param queueDepth  decode-queue depth observed with this unit, in units
     * @param queueWaitNs time this unit waited before decoding started, in nanoseconds
     * @return what the caller should do; {@link Decision#NONE} on the ordinary path
     */
    public Decision onSample(long nowNs, int queueDepth, long queueWaitNs) {
        if (state == State.HOLD) {
            if (nowNs - holdSinceNs < HOLD_NANOS) {
                return Decision.NONE;
            }
            // The hold expired: start over with a clean consecutive count.
            state = State.IDLE;
            holdSinceNs = UNSET;
            consecutiveCatchUps = 0;
        }
        if (state == State.CATCHING_UP) {
            if (nowNs - catchUpSinceNs < CATCH_UP_TIMEOUT_NANOS) {
                return Decision.NONE;
            }
            state = State.IDLE;
            catchUpSinceNs = UNSET;
        }

        final Reason reason = backlogReason(queueDepth, queueWaitNs);
        if (reason == Reason.NONE) {
            sustainedSinceNs = UNSET;
            noteClearSample(nowNs);
            return Decision.NONE;
        }

        clearSinceNs = UNSET;
        if (sustainedSinceNs == UNSET) {
            sustainedSinceNs = nowNs;
            return Decision.NONE;
        }
        if (nowNs - sustainedSinceNs < SUSTAIN_NANOS) {
            return Decision.NONE;
        }
        if (lastCatchUpAtNs != UNSET && nowNs - lastCatchUpAtNs < COOLDOWN_NANOS) {
            return Decision.NONE;
        }

        if (consecutiveCatchUps >= MAX_CONSECUTIVE_CATCH_UPS) {
            state = State.HOLD;
            holdSinceNs = nowNs;
            sustainedSinceNs = UNSET;
            return Decision.CONVERGENCE_FAILED;
        }

        consecutiveCatchUps++;
        state = State.CATCHING_UP;
        catchUpSinceNs = nowNs;
        lastCatchUpAtNs = nowNs;
        lastReason = reason;
        sustainedSinceNs = UNSET;
        clearSinceNs = UNSET;
        return Decision.START_CATCH_UP;
    }

    /**
     * Reports that the decoder accepted a join point, which ends the current episode.
     *
     * <p>A no-op outside an episode. The next clear sample starts the convergence timer, so a
     * second episode inside {@link #CONVERGED_CLEAR_NANOS} still counts as consecutive.</p>
     *
     * @param nowNs monotonic time of the join point, in nanoseconds
     */
    public void onJoinPoint(long nowNs) {
        if (state != State.CATCHING_UP) {
            return;
        }
        state = State.IDLE;
        catchUpSinceNs = UNSET;
    }

    /**
     * Opens an episode that was not caused by backlog, such as a lost whole access unit.
     *
     * <p>A no-op while an episode is already open: times are not refreshed, so one episode cannot
     * be prolonged indefinitely by repeated loss. Never increments the consecutive count, never
     * sets the cooldown and never starts the convergence timer, because a loss is a network fault
     * rather than a decoder deficit and must not spend the hold budget of {@code DV-027}. The event
     * is also ignored while attempts are held off, so a loss cannot cut that hold short.</p>
     *
     * @param nowNs monotonic time of the loss, in nanoseconds
     */
    public void onExternalCatchUp(long nowNs) {
        if (state != State.IDLE) {
            return;
        }
        state = State.CATCHING_UP;
        catchUpSinceNs = nowNs;
        lastReason = Reason.EXTERNAL;
    }

    /**
     * Returns whether the current episode is still waiting for its join point.
     */
    public boolean isCatchingUp() {
        return state == State.CATCHING_UP;
    }

    /**
     * Returns which signal detected the latest backlog, or {@link Reason#NONE} before the first
     * episode.
     */
    public Reason getLastReason() {
        return lastReason;
    }

    /**
     * Returns how many catch-ups ran without a converged period in between.
     */
    public int getConsecutiveCatchUps() {
        return consecutiveCatchUps;
    }

    /**
     * Returns whether a decode-queue task was overtaken by a catch-up and must be discarded.
     *
     * <p>Every task records the catch-up generation it was submitted under. When an episode bumps
     * the generation, the tasks already queued belong to frames that the catch-up deliberately
     * skipped, so decoding them would only replay stale pictures before the new join point.
     * Descriptors are never discarded: a descriptor is configuration, not media, and losing one
     * would leave the session waiting for an epoch it can no longer learn.</p>
     *
     * @param taskGeneration    generation captured when the task was submitted
     * @param currentGeneration generation the client currently accepts
     * @param isDescriptor      whether the task carries a stream descriptor
     * @return true when the task must be dropped without decoding
     */
    public static boolean isSuperseded(int taskGeneration, int currentGeneration,
                                       boolean isDescriptor) {
        return taskGeneration != currentGeneration && !isDescriptor;
    }

    /**
     * Classifies one queue sample against the two backlog thresholds.
     */
    private static Reason backlogReason(int queueDepth, long queueWaitNs) {
        if (queueWaitNs >= BACKLOG_WAIT_NANOS) {
            return Reason.QUEUE_WAIT;
        }
        if (queueDepth >= BACKLOG_QUEUE_DEPTH) {
            return Reason.QUEUE_DEPTH;
        }
        return Reason.NONE;
    }

    /**
     * Resets the consecutive count once the queue stayed clear long enough after a catch-up.
     */
    private void noteClearSample(long nowNs) {
        if (lastCatchUpAtNs == UNSET) {
            return;
        }
        if (clearSinceNs == UNSET) {
            clearSinceNs = nowNs;
        }
        if (nowNs - clearSinceNs >= CONVERGED_CLEAR_NANOS) {
            consecutiveCatchUps = 0;
        }
    }
}
