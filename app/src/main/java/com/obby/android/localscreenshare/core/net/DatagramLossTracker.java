package com.obby.android.localscreenshare.core.net;

import com.obby.android.localscreenshare.core.util.NanoClock;

/**
 * Decides when datagram loss has been bad enough to ask for a fresh key frame.
 *
 * <p>A lost fragment loses a whole access unit, and the frames that reference it decode into
 * garbage until a new key frame arrives. Asking for one costs a full-frame encode and a burst of
 * bandwidth, so the request is bound to evidence rather than to a single missing unit: two
 * incomplete units in a row, or three within half a second, make the loss a trend instead of an
 * accident. A granted request then waits a second before it can fire again, which is the time a key
 * frame needs to reach the viewer.</p>
 *
 * <p>Completing a unit clears the consecutive count but not the time window: the window measures
 * how many losses happened recently, and one good unit between two losses does not undo them. Both
 * counters are cleared when a request is granted, so the next request needs new evidence.</p>
 *
 * <p>Not thread safe; the receiver thread owns it.</p>
 */
public final class DatagramLossTracker {

    /**
     * Incomplete units in a row that justify a request.
     */
    public static final int CONSECUTIVE_THRESHOLD = 2;

    /**
     * Incomplete units within the window that justify a request.
     */
    public static final int WINDOW_THRESHOLD = 3;

    /**
     * Length of the sliding window of losses, in milliseconds.
     */
    public static final long WINDOW_MS = 500L;

    /**
     * Minimum spacing between two requests, in milliseconds.
     */
    public static final long COOLDOWN_MS = 1_000L;

    /**
     * Window converted to the clock unit.
     */
    public static final long WINDOW_NS = WINDOW_MS * 1_000_000L;

    /**
     * Cooldown converted to the clock unit.
     */
    public static final long COOLDOWN_NS = COOLDOWN_MS * 1_000_000L;

    private final NanoClock clock;

    private final long[] lossesAtNs = new long[WINDOW_THRESHOLD];

    private int lossCount;

    private int consecutive;

    private long lastRequestNs = Long.MIN_VALUE;

    /**
     * Creates a tracker.
     *
     * @param clock clock used for the window and the cooldown; required
     * @throws IllegalArgumentException if {@code clock} is null
     */
    public DatagramLossTracker(NanoClock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.clock = clock;
    }

    /**
     * Records one access unit that was delivered whole.
     */
    public void onAccessUnitComplete() {
        consecutive = 0;
    }

    /**
     * Records one access unit that had to be dropped as incomplete.
     */
    public void onAccessUnitIncomplete() {
        long nowNs = clock.nowNs();
        consecutive++;
        if (lossCount == WINDOW_THRESHOLD) {
            System.arraycopy(lossesAtNs, 1, lossesAtNs, 0, WINDOW_THRESHOLD - 1);
            lossCount--;
        }
        lossesAtNs[lossCount++] = nowNs;
    }

    /**
     * Returns whether the loss so far justifies a key-frame request now.
     *
     * <p>A true answer also starts the cooldown and clears the evidence, so the caller can ask
     * unconditionally on every unit and still send at most one request per second.</p>
     */
    public boolean shouldRequestJoinPoint() {
        long nowNs = clock.nowNs();
        prune(nowNs);
        boolean trending = consecutive >= CONSECUTIVE_THRESHOLD || lossCount >= WINDOW_THRESHOLD;
        if (!trending) {
            return false;
        }
        if (lastRequestNs != Long.MIN_VALUE && nowNs - lastRequestNs < COOLDOWN_NS) {
            return false;
        }
        lastRequestNs = nowNs;
        consecutive = 0;
        lossCount = 0;
        return true;
    }

    /**
     * Returns how many incomplete units happened in a row since the last complete one or request.
     */
    public int getConsecutiveIncomplete() {
        return consecutive;
    }

    /**
     * Returns how many losses are inside the window at this moment.
     */
    public int getWindowCount() {
        prune(clock.nowNs());
        return lossCount;
    }

    private void prune(long nowNs) {
        int kept = 0;
        for (int i = 0; i < lossCount; i++) {
            if (nowNs - lossesAtNs[i] <= WINDOW_NS) {
                lossesAtNs[kept++] = lossesAtNs[i];
            }
        }
        lossCount = kept;
    }
}
