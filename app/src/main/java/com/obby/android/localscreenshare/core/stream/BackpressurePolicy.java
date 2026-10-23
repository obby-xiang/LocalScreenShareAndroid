package com.obby.android.localscreenshare.core.stream;

import com.obby.android.localscreenshare.core.model.StreamFeedback;

/**
 * Pure decision table of the single-client bitrate backpressure.
 *
 * <p>The policy knows nothing about threads, metrics, encoders or protobuf: it turns one report and
 * the current bitrate into a level and an action, which keeps the risky part - when to lower and
 * when to raise - testable without an encoder. The coordinator owns the state and applies the
 * result on its own thread.</p>
 *
 * <p><b>Why eight and sixty-four.</b> A depth of {@value #CONGESTED_DEPTH} is an early warning:
 * the viewer has a backlog but can still recover, so lowering the bitrate now avoids a catch-up.
 * The catch-up policy's depth of sixty-four is a different signal: at that point the backlog is
 * unrecoverable and the client discards it and waits for a join point. The two numbers differ by an
 * order of magnitude on purpose; neither is a typo, and they serve different decisions.</p>
 *
 * <p>Every threshold below is a first cut that still needs device calibration. They are constants
 * instead of settings so the values that were measured cannot drift silently.</p>
 */
public final class BackpressurePolicy {

    /**
     * Queue depth at or above which a report counts as congested; early warning, pending device
     * calibration.
     */
    public static final int CONGESTED_DEPTH = 8;

    /**
     * Queue wait at or above which a report counts as congested, in milliseconds; pending device
     * calibration.
     */
    public static final int CONGESTED_WAIT_MS = 400;

    /**
     * Queue depth at or below which a report may count as clear; pending device calibration.
     */
    public static final int CLEAR_DEPTH = 2;

    /**
     * Queue wait at or below which a report may count as clear, in milliseconds; pending device
     * calibration.
     */
    public static final int CLEAR_WAIT_MS = 100;

    /**
     * One lowering step as a percentage of the current target; pending device calibration.
     */
    public static final int STEP_PERCENT = 75;

    /**
     * Floor of a lowered target as a percentage of the pre-override ceiling; pending device
     * calibration.
     */
    public static final int FLOOR_PERCENT = 40;

    /**
     * Consecutive clear reports required before a lowered target is raised again; pending device
     * calibration.
     */
    public static final int CLEAR_REPORTS_TO_RAISE = 2;

    /**
     * Minimum time between two bitrate actions, in milliseconds; pending device calibration.
     */
    public static final long MIN_ACTION_INTERVAL_MS = 5_000L;

    /**
     * Whether one report shows congestion or a cleared backlog.
     */
    public enum Level {

        /**
         * The backlog reached a threshold or a catch-up happened.
         */
        CONGESTED,

        /**
         * All three readings are at or below their clear thresholds.
         */
        CLEAR
    }

    /**
     * One bitrate action.
     */
    public enum Action {

        /**
         * No encoder call.
         */
        NONE,

        /**
         * Lower one step, clamped at the floor.
         */
        LOWER,

        /**
         * Return straight to the ceiling after enough clear reports.
         */
        RAISE,

        /**
         * Return straight to the ceiling because the override may not continue (multiple clients,
         * another codec, the master switch or shutdown).
         */
        RESTORE
    }

    private BackpressurePolicy() {
    }

    /**
     * Classifies one report.
     *
     * <p>Congestion needs only one signal at its threshold: depth at {@value #CONGESTED_DEPTH}, a
     * wait at {@value #CONGESTED_WAIT_MS} ms or any catch-up. Clear needs all three at or below
     * their clear thresholds, so a reading between the two sets stays congested and cannot start a
     * raise on an ambiguous report.</p>
     *
     * @param feedback the report; must not be null
     * @return the level of this report
     * @throws IllegalArgumentException if {@code feedback} is null
     */
    public static Level classify(StreamFeedback feedback) {
        if (feedback == null) {
            throw new IllegalArgumentException("feedback must not be null");
        }
        if (feedback.getQueueDepthPeak() >= CONGESTED_DEPTH
                || feedback.getQueueWaitPeakMs() >= CONGESTED_WAIT_MS
                || feedback.getCatchUpEvents() > 0) {
            return Level.CONGESTED;
        }
        if (feedback.getQueueDepthPeak() <= CLEAR_DEPTH
                && feedback.getQueueWaitPeakMs() <= CLEAR_WAIT_MS
                && feedback.getCatchUpEvents() == 0) {
            return Level.CLEAR;
        }
        return Level.CONGESTED;
    }

    /**
     * Decides the next action.
     *
     * <p>The caller passes {@code current} as {@link Action#LOWER} only while an override is in
     * force. A non-positive or non-H.264 stream never acts, but an existing override is restored so
     * a lowered stream cannot keep serving a client set that no longer qualifies. The minimum
     * action interval also bounds a restore. A report whose {@code clearStreak} is zero is
     * congested; otherwise the streak counts consecutive clear reports and a raise needs
     * {@value #CLEAR_REPORTS_TO_RAISE} of them.</p>
     *
     * @param current       action in force, {@link Action#LOWER} while overridden
     * @param currentBps    target bitrate currently in force
     * @param ceilingBps    bitrate before the override, or zero when none is in force
     * @param sessionCount  attached clients
     * @param h264          whether the published codec is H.264
     * @param clearStreak   consecutive clear reports, zero when the latest report was congested
     * @param sinceActionMs time since the last applied action
     * @return the action to apply
     * @throws IllegalArgumentException if {@code current} is null
     */
    public static Action decide(Action current, int currentBps, int ceilingBps, int sessionCount,
                                boolean h264, int clearStreak, long sinceActionMs) {
        if (current == null) {
            throw new IllegalArgumentException("current must not be null");
        }
        if (sinceActionMs < MIN_ACTION_INTERVAL_MS) {
            return Action.NONE;
        }
        if (sessionCount != 1 || !h264) {
            return current == Action.LOWER ? Action.RESTORE : Action.NONE;
        }
        if (clearStreak <= 0) {
            return Action.LOWER;
        }
        if (current == Action.LOWER && clearStreak >= CLEAR_REPORTS_TO_RAISE) {
            return Action.RAISE;
        }
        return Action.NONE;
    }

    /**
     * Computes the target of one action.
     *
     * <p>{@link Action#LOWER} takes one step down and is clamped at the floor; {@link
     * Action#RAISE} and {@link Action#RESTORE} go straight back to the ceiling; {@link
     * Action#NONE} keeps the current value.</p>
     *
     * @param action      action to translate
     * @param currentBps  target bitrate currently in force
     * @param ceilingBps  bitrate before the override
     * @return the target bitrate of the action
     * @throws IllegalArgumentException if {@code action} is null
     */
    public static int targetBitrate(Action action, int currentBps, int ceilingBps) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        switch (action) {
            case LOWER:
                final int floorBps = (int) Math.round(ceilingBps * FLOOR_PERCENT / 100d);
                final int steppedBps = (int) Math.round(currentBps * STEP_PERCENT / 100d);
                return Math.max(floorBps, steppedBps);
            case RAISE:
            case RESTORE:
                return ceilingBps;
            case NONE:
            default:
                return currentBps;
        }
    }
}
