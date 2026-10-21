package com.obby.android.localscreenshare.core.audio;

/**
 * Buffer policy of the client audio player: a pure decision engine with no I/O.
 *
 * <p>The session asks for a {@link Plan} for every decoded frame and for every underrun. The plan
 * says whether to accept the frame, how many older frames to drop, how much silence to insert
 * before it and whether playback may start. Keeping the decisions here means the timing rules can
 * be tested exhaustively without an {@code AudioTrack}.</p>
 *
 * <p><b>Why a buffer at all.</b> Audio is played from a sink that needs a small reservoir in front
 * of it: too little and every scheduling hiccup becomes a click, too much and the sound lags the
 * picture. The default is a 60 ms target, drained until it reaches 90 ms after exceeding 150 ms,
 * and at most 120 ms of prefill wait before playback starts with whatever arrived.</p>
 *
 * <p><b>Gaps.</b> A missing frame is never "played immediately": the session inserts silence of the
 * same length, so the audio keeps its rate instead of speeding up. Frames that go backwards are
 * dropped, because a late frame cannot be placed in the timeline without disturbing it.</p>
 *
 * <p><b>Drift.</b> Comparing the sink's playhead with the session clock can reveal a constant
 * offset. The guard allows at most one correction per two seconds and only acts when the difference
 * exceeds 40 ms, so it never oscillates around the measurement noise.</p>
 */
public final class AudioJitterBuffer {

    /**
     * Depth the buffer aims for before playback starts, in milliseconds.
     */
    public static final long TARGET_DEPTH_MS = 60L;

    /**
     * Depth above which the oldest frames are dropped, in milliseconds.
     */
    public static final long MAX_DEPTH_MS = 150L;

    /**
     * Depth the buffer is drained to when it exceeded {@link #MAX_DEPTH_MS}, in milliseconds.
     */
    public static final long RECOVER_DEPTH_MS = 90L;

    /**
     * Longest wait for the target depth before playback starts anyway, in milliseconds.
     */
    public static final long MAX_PREFILL_WAIT_MS = 120L;

    /**
     * Offset above which the drift guard corrects, in milliseconds.
     */
    public static final long DRIFT_THRESHOLD_MS = 40L;

    /**
     * Minimum interval between two drift corrections, in milliseconds.
     */
    public static final long DRIFT_COOLDOWN_MS = 2_000L;

    /**
     * Sentinel for an unknown playhead or expected timestamp in
     * {@link #driftCorrectionFrames(long, long, long, long)}.
     */
    public static final long UNSET = Long.MIN_VALUE;

    private static final long DEFAULT_FRAME_US = 21_333L;

    private static final int MAX_SILENCE_FRAMES = 25;

    /**
     * What the session should do with one decoded frame.
     */
    public static final class Plan {

        private final boolean accept;

        private final int dropOldestFrames;

        private final int silenceFrames;

        private final boolean startPlayback;

        private Plan(boolean accept, int dropOldestFrames, int silenceFrames,
                     boolean startPlayback) {
            this.accept = accept;
            this.dropOldestFrames = dropOldestFrames;
            this.silenceFrames = silenceFrames;
            this.startPlayback = startPlayback;
        }

        private static Plan rejected() {
            return new Plan(false, 0, 0, false);
        }

        /**
         * Returns whether the frame may be buffered.
         */
        public boolean isAccept() {
            return accept;
        }

        /**
         * Returns how many of the oldest buffered frames to discard before adding this one.
         */
        public int getDropOldestFrames() {
            return dropOldestFrames;
        }

        /**
         * Returns how many silence frames to insert before this one.
         */
        public int getSilenceFrames() {
            return silenceFrames;
        }

        /**
         * Returns whether playback may start now.
         */
        public boolean isStartPlayback() {
            return startPlayback;
        }
    }

    private long lastPtsUs = UNSET;

    private boolean started;

    private long firstPlanMs = UNSET;

    private long lastDriftCorrectionMs = UNSET;

    private long underruns;

    private long droppedFrames;

    private long silencedFrames;

    /**
     * Decides what to do with a decoded frame.
     *
     * @param ptsUs           presentation timestamp of the frame
     * @param frameDurationUs duration of one frame, or a non-positive value to use the AAC default
     * @param bufferedFrames  frames already waiting in the player before this one
     * @param nowMs           monotonic time in milliseconds
     * @return the plan; never null
     */
    public Plan plan(long ptsUs, long frameDurationUs, int bufferedFrames, long nowMs) {
        final long frameUs = frameDurationUs > 0 ? frameDurationUs : DEFAULT_FRAME_US;
        final long frameMs = Math.max(1L, (frameUs + 500L) / 1_000L);
        final int depthFrames = Math.max(0, bufferedFrames) + 1;
        final long depthMs = depthFrames * frameMs;

        int dropOldest = 0;
        if (started && depthMs > MAX_DEPTH_MS) {
            dropOldest = (int) Math.max(1L,
                    (depthMs - RECOVER_DEPTH_MS + frameMs - 1) / frameMs);
        }

        int silence = 0;
        if (lastPtsUs != UNSET) {
            if (ptsUs <= lastPtsUs) {
                droppedFrames++;
                return Plan.rejected();
            }
            final long gapFrames = (ptsUs - lastPtsUs + frameUs / 2) / frameUs;
            if (gapFrames >= 2) {
                silence = (int) Math.min(gapFrames - 1, MAX_SILENCE_FRAMES);
            }
        }

        boolean startPlayback = started;
        if (!started) {
            if (firstPlanMs == UNSET) {
                firstPlanMs = nowMs;
            }
            if (depthMs >= TARGET_DEPTH_MS || nowMs - firstPlanMs >= MAX_PREFILL_WAIT_MS) {
                started = true;
                startPlayback = true;
            }
        }

        lastPtsUs = ptsUs;
        silencedFrames += silence;
        return new Plan(true, dropOldest, silence, startPlayback);
    }

    /**
     * Records an underrun and asks for one silence frame.
     *
     * @param frameDurationUs duration of one frame, or non-positive for the AAC default
     * @return the number of silence frames to write, always one
     */
    public int onUnderrun(long frameDurationUs) {
        underruns++;
        silencedFrames++;
        return 1;
    }

    /**
     * Decides whether the drift guard should correct the offset now.
     *
     * @param expectedPtsUs   stream timestamp the session clock expects to be audible now
     * @param playheadPtsUs   stream timestamp the sink reports as audible, or {@link #UNSET}
     * @param frameDurationUs duration of one frame, or non-positive for the AAC default
     * @param nowMs           monotonic time in milliseconds
     * @return {@code -1} to drop one frame (audio is ahead), {@code 1} to insert one silence frame
     *         (audio is behind), or {@code 0} to do nothing
     */
    public int driftCorrectionFrames(long expectedPtsUs, long playheadPtsUs, long frameDurationUs,
                                     long nowMs) {
        if (expectedPtsUs == UNSET || playheadPtsUs == UNSET) {
            return 0;
        }
        if (lastDriftCorrectionMs != UNSET
                && nowMs - lastDriftCorrectionMs < DRIFT_COOLDOWN_MS) {
            return 0;
        }
        final long diffMs = (playheadPtsUs - expectedPtsUs) / 1_000L;
        if (Math.abs(diffMs) <= DRIFT_THRESHOLD_MS) {
            return 0;
        }
        lastDriftCorrectionMs = nowMs;
        return diffMs > 0 ? -1 : 1;
    }

    /**
     * Clears every timing decision; used when the audio configuration changes.
     */
    public void reset() {
        lastPtsUs = UNSET;
        started = false;
        firstPlanMs = UNSET;
        lastDriftCorrectionMs = UNSET;
    }

    /**
     * Returns whether playback has started.
     */
    public boolean isStarted() {
        return started;
    }

    public long getUnderruns() {
        return underruns;
    }

    public long getDroppedFrames() {
        return droppedFrames;
    }

    public long getSilencedFrames() {
        return silencedFrames;
    }
}
