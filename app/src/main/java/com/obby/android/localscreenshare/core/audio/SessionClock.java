package com.obby.android.localscreenshare.core.audio;

/**
 * One monotonic timeline shared by the audio and video tracks of a session.
 *
 * <p>The stream's own timestamps start at an arbitrary value and the device clock starts at
 * another, so both tracks need one mapping between them. This class owns exactly one anchor: the
 * pair {@code (ptsUs, localNs)} recorded once, whichever track arrives first. Every consumer maps
 * stream time through the same anchor, which is what keeps relative audio/video timing stable even
 * though the two tracks are buffered and decoded independently.</p>
 *
 * <p><b>Reset rules.</b> Only a stream restart or a video descriptor epoch change resets the anchor.
 * An audio descriptor change rebuilds the audio decoder but keeps the anchor, and a video flush or
 * catch-up never resets it: resetting on a video-only event would drag the audio timeline with it,
 * and resetting on an audio-only event would shift the picture.</p>
 *
 * <p><b>Threading.</b> Anchoring is atomic and happens at most once; {@link #map(long)} and
 * {@link #unmap(long)} are pure reads of the anchor. The class is safe to share between the decode,
 * drain and writer threads.</p>
 */
public final class SessionClock {

    /**
     * Marker returned by {@link #map(long)} and {@link #unmap(long)} before an anchor exists.
     */
    public static final long UNSET = Long.MIN_VALUE;

    private final Object lock = new Object();

    private long anchorPtsUs = UNSET;

    private long anchorNs = UNSET;

    /**
     * Anchors the timeline if it is not anchored yet.
     *
     * <p>The first caller wins and later calls change nothing, so a race between the first audio
     * frame and the first video frame resolves to one anchor instead of two competing ones.</p>
     *
     * @param ptsUs stream timestamp of the anchor
     * @param nowNs local monotonic time the anchor is presented at
     * @return true when this call created the anchor
     */
    public boolean anchorIfUnset(long ptsUs, long nowNs) {
        synchronized (lock) {
            if (anchorPtsUs != UNSET) {
                return false;
            }
            anchorPtsUs = ptsUs;
            anchorNs = nowNs;
            return true;
        }
    }

    /**
     * Returns whether the timeline is anchored.
     */
    public boolean isAnchored() {
        synchronized (lock) {
            return anchorPtsUs != UNSET;
        }
    }

    /**
     * Maps a stream timestamp onto the local monotonic clock.
     *
     * @return the local time in nanoseconds, or {@link #UNSET} before an anchor exists
     */
    public long map(long ptsUs) {
        final long pts;
        final long ns;
        synchronized (lock) {
            pts = anchorPtsUs;
            ns = anchorNs;
        }
        if (pts == UNSET) {
            return UNSET;
        }
        return ns + (ptsUs - pts) * 1_000L;
    }

    /**
     * Maps a local monotonic time back onto stream time.
     *
     * @return the stream timestamp in microseconds, or {@link #UNSET} before an anchor exists
     */
    public long unmap(long ns) {
        final long pts;
        final long anchor;
        synchronized (lock) {
            pts = anchorPtsUs;
            anchor = anchorNs;
        }
        if (pts == UNSET) {
            return UNSET;
        }
        return pts + (ns - anchor) / 1_000L;
    }

    /**
     * Returns the anchor's stream timestamp, or {@link #UNSET} before an anchor exists.
     */
    public long getAnchorPtsUs() {
        synchronized (lock) {
            return anchorPtsUs;
        }
    }

    /**
     * Returns the anchor's local monotonic time, or {@link #UNSET} before an anchor exists.
     */
    public long getAnchorNs() {
        synchronized (lock) {
            return anchorNs;
        }
    }

    /**
     * Clears the anchor, so the next {@link #anchorIfUnset(long, long)} wins.
     *
     * <p>Called only for a session restart or a video descriptor epoch change.</p>
     */
    public void reset() {
        synchronized (lock) {
            anchorPtsUs = UNSET;
            anchorNs = UNSET;
        }
    }
}
