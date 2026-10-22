package com.obby.android.localscreenshare.core.net;

/**
 * Sliding window that decides whether a datagram is new, repeated or too old to matter.
 *
 * <p>Datagrams are not ordered, so a receiver has to keep a bounded record of what it already saw.
 * The window holds the last {@value #WINDOW_SIZE} sequence numbers in one bit mask: a sequence
 * below the window is stale (it can only be a replay of something long past), a sequence already
 * set is a duplicate, and anything else is accepted - including a sequence below the highest one
 * seen, which is a reorder rather than an error.</p>
 *
 * <p><b>Before decryption.</b> {@link #isStale(long)} is the cheap pre-check the plan requires: a
 * datagram older than the window is dropped without spending a decryption on it. The window is
 * only advanced by {@link #accept(long)}, which the caller runs after the datagram authenticated -
 * otherwise a forged datagram with a fresh sequence number would mark that number as received and
 * the genuine one would be discarded as a duplicate.</p>
 *
 * <p>Statistics are cumulative for the life of the session: duplicates, stale datagrams, accepted
 * reorders and the holes the window skipped over, which is what a loss ratio is derived from.</p>
 *
 * <p>Not thread safe; one receiver thread owns it.</p>
 */
public final class DatagramReorderWindow {

    /**
     * Number of sequence numbers the window remembers.
     */
    public static final int WINDOW_SIZE = 64;

    /**
     * Outcome of feeding one sequence number to the window.
     */
    public enum Decision {

        /**
         * The datagram is new and was recorded.
         */
        ACCEPT,

        /**
         * The sequence number was already recorded.
         */
        DUPLICATE,

        /**
         * The sequence number is older than the window.
         */
        STALE
    }

    // Bit i of "bits" describes base + i.
    private long base;

    private long bits;

    private long highWater;

    private boolean seeded;

    private long duplicates;

    private long stale;

    private long reordered;

    private long lost;

    /**
     * Returns whether {@code wireSeq} is older than the window, without changing any state.
     *
     * @param wireSeq sequence number of the datagram
     */
    public boolean isStale(long wireSeq) {
        return seeded && wireSeq < base;
    }

    /**
     * Records one sequence number and reports what it was.
     *
     * @param wireSeq sequence number of an authenticated datagram
     * @return the decision for this sequence number
     */
    public Decision accept(long wireSeq) {
        if (!seeded) {
            seeded = true;
            // The window opens behind the first sequence number, so datagrams that were merely
            // reordered across the start of the session are still recognised as reorders.
            base = Math.max(0L, wireSeq - WINDOW_SIZE + 1L);
            bits = 1L << (wireSeq - base);
            highWater = wireSeq;
            return Decision.ACCEPT;
        }
        if (wireSeq < base) {
            stale++;
            return Decision.STALE;
        }
        long index = wireSeq - base;
        if (index < WINDOW_SIZE) {
            long mask = 1L << index;
            if ((bits & mask) != 0) {
                duplicates++;
                return Decision.DUPLICATE;
            }
            bits |= mask;
            if (wireSeq < highWater) {
                reordered++;
            } else {
                lost += wireSeq - highWater - 1L;
                highWater = wireSeq;
            }
            return Decision.ACCEPT;
        }
        long shift = index - WINDOW_SIZE + 1L;
        if (shift >= WINDOW_SIZE) {
            bits = 0L;
        } else {
            bits >>>= shift;
        }
        lost += wireSeq - highWater - 1L;
        base = wireSeq - WINDOW_SIZE + 1L;
        bits |= 1L << (WINDOW_SIZE - 1);
        highWater = wireSeq;
        return Decision.ACCEPT;
    }

    /**
     * Returns the highest sequence number accepted so far, or zero before the first one.
     */
    public long getHighWater() {
        return highWater;
    }

    /**
     * Returns how many datagrams were duplicates.
     */
    public long getDuplicates() {
        return duplicates;
    }

    /**
     * Returns how many datagrams were older than the window.
     */
    public long getStale() {
        return stale;
    }

    /**
     * Returns how many datagrams were accepted out of order.
     */
    public long getReordered() {
        return reordered;
    }

    /**
     * Returns how many sequence numbers the window skipped over, which is the loss count observed
     * on this direction.
     */
    public long getLost() {
        return lost;
    }

    /**
     * Returns whether any sequence number was accepted yet.
     */
    public boolean isSeeded() {
        return seeded;
    }
}
