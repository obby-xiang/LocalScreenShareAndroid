package com.obby.android.localscreenshare.client;

/**
 * Backoff and attempt limit for reconnecting a viewer.
 *
 * <p>A lost connection is usually transient, so the viewer offers a retry instead of ending the
 * session. Retrying immediately on every failure, however, turns a peer that is rebooting or off
 * the network into a burst of connections that keeps the radio and the decode pipeline busy. This
 * policy hands out one delay per attempt - immediate for the first one, then one, two, four and
 * eight seconds - and stops offering retries after {@value #MAX_ATTEMPTS} attempts, which the
 * viewer reports as "still not reachable".</p>
 *
 * <p>One instance belongs to one viewing session: the service calls {@link #reset()} whenever a
 * connection succeeds, so a healthy stream that drops hours later starts at the immediate retry
 * again. All methods are thread-safe; calls beyond the last delay keep returning the capped value
 * instead of growing an array index.</p>
 */
public final class ReconnectPolicy {

    /**
     * Number of retries a session may offer before it reports that the peer is unreachable.
     */
    public static final int MAX_ATTEMPTS = 5;

    private static final long[] DELAYS_MS = {0L, 1_000L, 2_000L, 4_000L, 8_000L};

    // Attempts consumed so far; never exceeds MAX_ATTEMPTS.
    private int mAttempts;

    /**
     * Returns the delay for the next retry and consumes that attempt.
     *
     * <p>The first call returns zero, so the first retry is immediate. Further calls return one,
     * two, four and eight seconds; once the schedule is exhausted the last delay is returned
     * again, which keeps a caller that ignores {@link #canRetry()} inside the same bound.</p>
     *
     * @return delay before the retry, in milliseconds
     */
    public synchronized long nextDelayMs() {
        final int index = Math.min(mAttempts, DELAYS_MS.length - 1);
        mAttempts = Math.min(mAttempts + 1, MAX_ATTEMPTS);
        return DELAYS_MS[index];
    }

    /**
     * Returns whether another retry may be offered.
     */
    public synchronized boolean canRetry() {
        return mAttempts < MAX_ATTEMPTS;
    }

    /**
     * Returns how many attempts were consumed since the last {@link #reset()}.
     */
    public synchronized int getAttempts() {
        return mAttempts;
    }

    /**
     * Returns to the first attempt; called when a connection succeeds.
     */
    public synchronized void reset() {
        mAttempts = 0;
    }
}
