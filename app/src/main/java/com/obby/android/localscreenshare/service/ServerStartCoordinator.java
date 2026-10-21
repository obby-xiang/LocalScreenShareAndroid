package com.obby.android.localscreenshare.service;

import androidx.annotation.NonNull;

/**
 * Orders an asynchronous server start against stop requests.
 *
 * <p>The capture service starts its server on a background thread, because binding the port and
 * preparing TLS material touch the AndroidKeyStore and can block for seconds when the identity key
 * is unusable. A stop request, a projection revocation or a second start can therefore arrive while
 * the start is still running, and only one of the two sides may stop the resulting server: stopping
 * it twice is harmless only by accident, and stopping it before the port is bound does not stop the
 * start that is still in flight.</p>
 *
 * <p>The owner is decided by {@link #beginStop()}: it returns {@code true} when the caller must stop
 * the server itself, and {@code false} when the server is still being started, in which case
 * {@link #finishStart(long)} returns {@code false} to the start task and that task stops the server
 * as soon as {@code start()} returns. {@link #beginStart()} hands out a generation token, so a task
 * that reports long after its session was replaced cannot claim the newer one.</p>
 *
 * <p>All methods are thread-safe and never block.</p>
 */
public final class ServerStartCoordinator {

    /**
     * Token returned by {@link #beginStart()} when a server is already starting or running.
     */
    public static final long NO_GENERATION = 0L;

    /**
     * Phase of the server within one capture session.
     */
    public enum State {
        /**
         * No server exists or its start was abandoned.
         */
        IDLE,

        /**
         * A server is being started on the control thread.
         */
        STARTING,

        /**
         * The start completed and the server is running.
         */
        RUNNING
    }

    // Guarded by this. Bumped by every accepted start, so an old start task is recognizable.
    private long mGeneration = NO_GENERATION;

    // Guarded by this.
    @NonNull
    private State mState = State.IDLE;

    /**
     * Claims the session for a new start.
     *
     * @return the token to pass to {@link #finishStart(long)}, or {@link #NO_GENERATION} when a
     *     server is already starting or running
     */
    public synchronized long beginStart() {
        if (mState != State.IDLE) {
            return NO_GENERATION;
        }
        mState = State.STARTING;
        return ++mGeneration;
    }

    /**
     * Releases the session and decides who stops the server.
     *
     * @return {@code true} when the caller must stop the server now, {@code false} when a start is
     *     in flight and its task will stop the server instead
     */
    public synchronized boolean beginStop() {
        final boolean isStartInFlight = mState == State.STARTING;
        mState = State.IDLE;
        return !isStartInFlight;
    }

    /**
     * Reports that {@code start()} returned, either way.
     *
     * @param generation token returned by the matching {@link #beginStart()}
     * @return {@code true} when the caller still owns the session, {@code false} when a stop or a
     *     newer session abandoned it and the caller must stop the server it just started
     */
    public synchronized boolean finishStart(final long generation) {
        if (mState != State.STARTING || generation != mGeneration) {
            return false;
        }
        mState = State.RUNNING;
        return true;
    }

    /**
     * Returns the current phase.
     */
    @NonNull
    public synchronized State getState() {
        return mState;
    }
}
