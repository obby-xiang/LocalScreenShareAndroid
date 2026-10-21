package com.obby.android.localscreenshare.service;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Ownership handover between an asynchronous server start and a stop request.
 */
public class ServerStartCoordinatorTest {

    private ServerStartCoordinator mCoordinator;

    @Before
    public void setUp() {
        mCoordinator = new ServerStartCoordinator();
    }

    @Test
    public void aFreshCoordinatorIsIdle() {
        assertEquals(ServerStartCoordinator.State.IDLE, mCoordinator.getState());
    }

    @Test
    public void theFirstStartIsAccepted() {
        assertNotEquals(ServerStartCoordinator.NO_GENERATION, mCoordinator.beginStart());
        assertEquals(ServerStartCoordinator.State.STARTING, mCoordinator.getState());
    }

    @Test
    public void aSecondStartIsRejectedWhileStartingOrRunning() {
        final long generation = mCoordinator.beginStart();
        assertEquals(ServerStartCoordinator.NO_GENERATION, mCoordinator.beginStart());

        assertTrue(mCoordinator.finishStart(generation));
        assertEquals(ServerStartCoordinator.NO_GENERATION, mCoordinator.beginStart());
        assertEquals(ServerStartCoordinator.State.RUNNING, mCoordinator.getState());
    }

    @Test
    public void aStopWhileStartingLeavesTheServerToTheStartTask() {
        final long generation = mCoordinator.beginStart();

        assertFalse("the start task still owns the server", mCoordinator.beginStop());
        assertEquals(ServerStartCoordinator.State.IDLE, mCoordinator.getState());
        assertFalse("the start task has to stop what it started",
            mCoordinator.finishStart(generation));
    }

    @Test
    public void aStopAfterTheStartKeepsTheSessionStoppable() {
        final long generation = mCoordinator.beginStart();
        assertTrue(mCoordinator.finishStart(generation));

        assertTrue(mCoordinator.beginStop());
    }

    @Test
    public void stoppingAnIdleSessionStopsNothingInFlight() {
        assertTrue(mCoordinator.beginStop());
        assertEquals(ServerStartCoordinator.State.IDLE, mCoordinator.getState());
    }

    @Test
    public void aStoppedSessionCanBeStartedAgain() {
        final long generation = mCoordinator.beginStart();
        mCoordinator.finishStart(generation);
        mCoordinator.beginStop();

        final long next = mCoordinator.beginStart();
        assertNotEquals(ServerStartCoordinator.NO_GENERATION, next);
        assertNotEquals(generation, next);
        assertEquals(ServerStartCoordinator.State.STARTING, mCoordinator.getState());
    }

    @Test
    public void anAbandonedStartCannotClaimALaterSession() {
        final long abandoned = mCoordinator.beginStart();
        mCoordinator.beginStop();

        final long next = mCoordinator.beginStart();
        assertFalse("the old task must not complete the new session",
            mCoordinator.finishStart(abandoned));
        assertEquals(ServerStartCoordinator.State.STARTING, mCoordinator.getState());

        assertTrue(mCoordinator.finishStart(next));
        assertEquals(ServerStartCoordinator.State.RUNNING, mCoordinator.getState());
    }
}
