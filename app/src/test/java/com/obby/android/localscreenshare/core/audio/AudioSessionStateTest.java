package com.obby.android.localscreenshare.core.audio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link AudioSessionState}.
 */
public class AudioSessionStateTest {

    @Test
    public void offOnlyStarts() {
        assertTrue(AudioSessionState.OFF.canTransitionTo(AudioSessionState.STARTING));
        assertFalse(AudioSessionState.OFF.canTransitionTo(AudioSessionState.ACTIVE));
        assertFalse(AudioSessionState.OFF.canTransitionTo(AudioSessionState.UNAVAILABLE));
        assertFalse(AudioSessionState.OFF.canTransitionTo(AudioSessionState.OFF));
        assertFalse(AudioSessionState.OFF.canTransitionTo(null));
    }

    @Test
    public void startingReachesExactlyOneOutcome() {
        assertTrue(AudioSessionState.STARTING.canTransitionTo(AudioSessionState.ACTIVE));
        assertTrue(AudioSessionState.STARTING.canTransitionTo(AudioSessionState.UNAVAILABLE));
        assertTrue(AudioSessionState.STARTING.canTransitionTo(AudioSessionState.OFF));
        assertFalse(AudioSessionState.STARTING.canTransitionTo(AudioSessionState.STARTING));
    }

    @Test
    public void activeCanFailOrStopButNotRestart() {
        assertTrue(AudioSessionState.ACTIVE.canTransitionTo(AudioSessionState.UNAVAILABLE));
        assertTrue(AudioSessionState.ACTIVE.canTransitionTo(AudioSessionState.OFF));
        assertFalse(AudioSessionState.ACTIVE.canTransitionTo(AudioSessionState.STARTING));
        assertFalse(AudioSessionState.ACTIVE.canTransitionTo(AudioSessionState.ACTIVE));
    }

    @Test
    public void unavailableNeedsExplicitRetryOrOff() {
        assertTrue(AudioSessionState.UNAVAILABLE.canTransitionTo(AudioSessionState.STARTING));
        assertTrue(AudioSessionState.UNAVAILABLE.canTransitionTo(AudioSessionState.OFF));
        assertFalse(AudioSessionState.UNAVAILABLE.canTransitionTo(AudioSessionState.ACTIVE));
        assertFalse(AudioSessionState.UNAVAILABLE.canTransitionTo(AudioSessionState.UNAVAILABLE));
    }

    @Test
    public void everyStateHasAnExhaustiveTransitionTable() {
        for (AudioSessionState state : AudioSessionState.values()) {
            int allowed = 0;
            for (AudioSessionState next : AudioSessionState.values()) {
                if (state.canTransitionTo(next)) {
                    allowed++;
                }
            }
            assertTrue(state + " must have at least one successor", allowed >= 1);
        }
    }

    @Test
    public void runningCoversStartAndActiveOnly() {
        assertFalse(AudioSessionState.OFF.isRunning());
        assertTrue(AudioSessionState.STARTING.isRunning());
        assertTrue(AudioSessionState.ACTIVE.isRunning());
        assertFalse(AudioSessionState.UNAVAILABLE.isRunning());
    }
}
