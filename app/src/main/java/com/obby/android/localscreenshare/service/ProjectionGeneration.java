package com.obby.android.localscreenshare.service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Hands out capture generations so frames from a retired projection are dropped.
 *
 * <p>Every reader replacement and every teardown starts a new generation. The capture thread
 * snapshots the token that belongs to the reader which produced its pixels and validates it again
 * after the JPEG encode, so a frame that outlived a resize or a teardown is never submitted to the
 * encoder of a later session. The counter only increases, which keeps a stale token invalid even
 * after a new session has begun.</p>
 *
 * <p>Thread-safe. The main thread calls {@link #begin()} and {@link #invalidate()}; the capture
 * thread only calls {@link #isCurrent(long)}.</p>
 */
public final class ProjectionGeneration {

    private final AtomicLong mGeneration = new AtomicLong();

    /**
     * Starts a new generation, which invalidates every earlier token.
     *
     * @return token identifying the new generation; always positive
     */
    public long begin() {
        return mGeneration.incrementAndGet();
    }

    /**
     * Returns whether the token still identifies the current generation.
     *
     * <p>A token that was never issued is not current, so a caller that failed to snapshot one
     * drops the frame instead of submitting it into whatever session is running.</p>
     *
     * @param token token returned by {@link #begin()}
     * @return {@code true} while no later {@code begin()} or {@code invalidate()} happened
     */
    public boolean isCurrent(long token) {
        return token > 0L && mGeneration.get() == token;
    }

    /**
     * Invalidates every issued token; the next {@link #begin()} starts from a fresh generation.
     */
    public void invalidate() {
        mGeneration.incrementAndGet();
    }
}
