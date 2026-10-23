package com.obby.android.localscreenshare.core.audio;

import java.util.Locale;

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
 * picture. The default is a 120 ms target, drained towards 250 ms after exceeding 400 ms a couple of
 * frames at a time, and at most 1500 ms of prefill wait before playback starts with whatever
 * arrived.</p>
 *
 * <p><b>Depth.</b> {@link #plan(long, long, long, long)} is told how much media is actually
 * buffered ahead of the playhead - what was written to the sink and not yet presented, plus what
 * still waits for the writer - and never a frame count of the session's own history. The
 * distinction matters: a monotonically growing "depth" would cross every threshold once and then
 * request a drop per frame for the rest of the session.</p>
 *
 * <p><b>Gaps.</b> A missing frame is never "played immediately": the session inserts silence of the
 * same length, so the audio keeps its rate instead of speeding up. Frames that go backwards are
 * dropped, because a late frame cannot be placed in the timeline without disturbing it.</p>
 *
 * <p><b>Starvation.</b> {@link #onUnderrun(long, long)} turns a measured gap into either the number
 * of silence frames that closes it or a request to restart playback: a gap longer than
 * {@link #RESTART_GAP_MS} is cheaper to re-prefill than to pad.</p>
 *
 * <p><b>Drift.</b> Comparing the audible position with the session clock reveals both a fixed offset
 * and its movement. The guard allows at most one correction per two seconds, only acts when the
 * offset has moved by more than 40 ms since the last correction, and never chases a fixed offset:
 * one frame of media cannot remove a constant bias, and repeating the attempt would pad the output
 * for the whole session.</p>
 */
public final class AudioJitterBuffer {

    /**
     * Depth the buffer aims for before playback starts, in milliseconds.
     */
    public static final long TARGET_DEPTH_MS = 120L;

    /**
     * Depth above which the oldest frames are dropped, in milliseconds.
     *
     * <p>It has to sit well above the steady state of a healthy stream - the 128 ms track plus the
     * roughly 106 ms of prefill the writer still holds - otherwise every video burst that deepens the
     * queue once crosses it. The previous 250 ms bound was only a few milliseconds above that steady
     * state, so a burst dropped six frames (128 ms) of audible audio every 13-18 s. 400 ms leaves
     * about 150 ms of headroom over the steady state and is still short enough not to turn a real
     * backlog into permanent latency.</p>
     */
    public static final long MAX_DEPTH_MS = 400L;

    /**
     * Depth the buffer is drained to when it exceeded {@link #MAX_DEPTH_MS}, in milliseconds.
     *
     * <p>It is just above the steady state, so the drain stops where the stream normally lives instead
     * of cutting into the reservoir the sink needs.</p>
     *
     * <p>The drain is a latch rather than a per-frame comparison: once the depth has passed
     * {@link #MAX_DEPTH_MS} the buffer keeps shedding until it is back at this value, not only until
     * it is a few frames below the bound. Stopping at the bound leaves the stream parked just above
     * it, because the sink plays at exactly the rate the frames arrive and nothing else removes the
     * media: one burst then costs the rest of the session a fixed offset of the bound's size, which
     * is what a device reported as a stable half-second lag.</p>
     */
    public static final long RECOVER_DEPTH_MS = 250L;

    /**
     * Largest number of frames one {@link #plan(long, long, long, long)} call may drop.
     *
     * <p>An excess is shed a couple of frames at a time rather than in one cut: two frames are 43 ms,
     * which is below the length of a note and far cheaper than the 128 ms hole the previous
     * drop-to-recover-depth rule opened. The incoming frame rate removes an excess faster than real
     * time even at this rate, so a backlog still drains.</p>
     */
    public static final int MAX_DROP_FRAMES_PER_PLAN = 2;

    /**
     * Longest wait for the target depth before playback starts anyway, in milliseconds.
     *
     * <p>Only a sender that delivers less than real time can reach it: at the nominal frame rate the
     * target depth is buffered long before. It exists so that a stream which never fills the target
     * still starts playing, instead of staying silent for the whole session.</p>
     */
    public static final long MAX_PREFILL_WAIT_MS = 1500L;

    /**
     * Gap above which starved playback is restarted instead of padded, in milliseconds.
     *
     * <p>Padding a long gap means writing dozens of silence frames while the sink stays empty, which
     * is both slower than the gap and audible. Re-prefilling rebuilds the reservoir and re-anchors
     * the timeline on the next decoded frame.</p>
     */
    public static final long RESTART_GAP_MS = 100L;

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

    private final AudioLog log;

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

    /**
     * What the session should do after a measured starvation gap.
     */
    public static final class Underrun {

        private final boolean restart;

        private final int silenceFrames;

        private Underrun(boolean restart, int silenceFrames) {
            this.restart = restart;
            this.silenceFrames = silenceFrames;
        }

        /**
         * Returns whether playback has to be restarted instead of padded.
         */
        public boolean isRestart() {
            return restart;
        }

        /**
         * Returns how many silence frames close the gap; zero when a restart is required.
         */
        public int getSilenceFrames() {
            return silenceFrames;
        }
    }

    private long lastPtsUs = UNSET;

    private boolean started;

    private boolean draining;

    private long firstPlanMs = UNSET;

    private long lastDriftCorrectionMs = UNSET;

    private long underruns;

    private long droppedFrames;

    private long silencedFrames;

    /**
     * Creates a buffer that reports its decisions nowhere.
     */
    public AudioJitterBuffer() {
        this(AudioLog.NONE);
    }

    /**
     * Creates a buffer that reports the frames it drops and the gaps it fills.
     *
     * @param log diagnostic sink; required, may be {@link AudioLog#NONE}
     * @throws IllegalArgumentException if {@code log} is null
     */
    public AudioJitterBuffer(AudioLog log) {
        if (log == null) {
            throw new IllegalArgumentException("log must not be null");
        }
        this.log = log;
    }

    /**
     * Decides what to do with a decoded frame.
     *
     * @param ptsUs           presentation timestamp of the frame
     * @param frameDurationUs duration of one frame, or a non-positive value to use the AAC default
     * @param bufferedUs      media time buffered ahead of the playhead before this frame, in
     *                        microseconds; see the class documentation for what counts
     * @param nowMs           monotonic time in milliseconds
     * @return the plan; never null
     */
    public Plan plan(long ptsUs, long frameDurationUs, long bufferedUs, long nowMs) {
        final long frameUs = frameDurationUs > 0 ? frameDurationUs : DEFAULT_FRAME_US;
        final long frameMs = Math.max(1L, (frameUs + 500L) / 1_000L);
        final long depthMs = (Math.max(0L, bufferedUs) + frameUs) / 1_000L;

        if (started && depthMs > MAX_DEPTH_MS) {
            draining = true;
        } else if (draining && depthMs <= RECOVER_DEPTH_MS) {
            draining = false;
        }

        int dropOldest = 0;
        if (draining) {
            final long excessFrames = Math.max(1L,
                    (depthMs - RECOVER_DEPTH_MS + frameMs - 1) / frameMs);
            dropOldest = (int) Math.min(MAX_DROP_FRAMES_PER_PLAN, excessFrames);
            log.warn(String.format(Locale.US,
                    "dropping %d buffered frames, excess = %d frames, depth = %d ms",
                    dropOldest, excessFrames, depthMs));
        }

        int silence = 0;
        if (lastPtsUs != UNSET) {
            if (ptsUs <= lastPtsUs) {
                droppedFrames++;
                log.warn(String.format(Locale.US, "dropping a late frame, pts = %d us after %d us",
                        ptsUs, lastPtsUs));
                return Plan.rejected();
            }
            final long gapFrames = (ptsUs - lastPtsUs + frameUs / 2) / frameUs;
            if (gapFrames >= 2) {
                silence = (int) Math.min(gapFrames - 1, MAX_SILENCE_FRAMES);
                log.warn(String.format(Locale.US, "gap: %d silence frames before pts = %d us",
                        silence, ptsUs));
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
     * Decides how to recover from a starvation that has already lasted {@code gapMs}.
     *
     * <p>The number of silence frames is the gap itself, rounded up to whole frames, because that is
     * how much media time the output is missing: writing one frame per empty poll - the previous
     * behaviour - recovered slower than the gap grew and left the sink empty in between. A gap past
     * {@link #RESTART_GAP_MS} is not padded at all: re-prefilling the reservoir is both cheaper and
     * quieter than writing dozens of frames while the track stays starved.</p>
     *
     * @param gapMs           measured time the output has been starved, in milliseconds
     * @param frameDurationUs duration of one frame, or non-positive for the AAC default
     * @return the recovery decision; never null
     */
    public Underrun onUnderrun(long gapMs, long frameDurationUs) {
        underruns++;
        if (gapMs >= RESTART_GAP_MS) {
            return new Underrun(true, 0);
        }
        final long frameUs = frameDurationUs > 0 ? frameDurationUs : DEFAULT_FRAME_US;
        final long frameMs = Math.max(1L, (frameUs + 500L) / 1_000L);
        final int frames = (int) Math.max(1L, (Math.max(0L, gapMs) + frameMs - 1) / frameMs);
        silencedFrames += frames;
        return new Underrun(false, frames);
    }

    /**
     * Decides whether the drift guard should correct the drift now.
     *
     * <p>The argument is a drift, not an absolute offset: a fixed audio/video offset cannot be
     * removed by one frame of silence, so correcting it would repeat the same correction every
     * cooldown period for as long as the stream lasts. What the guard can absorb is the part that
     * moves - the difference between the offset measured now and the offset it left behind after the
     * previous correction.</p>
     *
     * @param driftUs         movement of the audible position relative to the timeline since the last
     *                        correction, or {@link #UNSET} when nothing has been measured yet
     * @param frameDurationUs duration of one frame, or non-positive for the AAC default
     * @param nowMs           monotonic time in milliseconds
     * @return {@code 1} to insert one silence frame (the audible audio moved ahead of the timeline
     *         and has to be delayed), {@code -1} to drop one buffered frame (the audible audio moved
     *         behind and the following content has to be brought forward), or {@code 0} to do
     *         nothing
     */
    public int driftCorrectionFrames(long driftUs, long frameDurationUs, long nowMs) {
        if (driftUs == UNSET) {
            return 0;
        }
        if (lastDriftCorrectionMs != UNSET
                && nowMs - lastDriftCorrectionMs < DRIFT_COOLDOWN_MS) {
            return 0;
        }
        final long driftMs = driftUs / 1_000L;
        if (Math.abs(driftMs) <= DRIFT_THRESHOLD_MS) {
            return 0;
        }
        lastDriftCorrectionMs = nowMs;
        return driftMs > 0 ? 1 : -1;
    }

    /**
     * Clears every timing decision; used when the audio configuration changes.
     */
    public void reset() {
        lastPtsUs = UNSET;
        started = false;
        draining = false;
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
