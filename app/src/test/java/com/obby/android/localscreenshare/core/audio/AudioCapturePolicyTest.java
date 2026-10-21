package com.obby.android.localscreenshare.core.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.obby.android.localscreenshare.core.audio.AudioCapturePolicy.Action;

import org.junit.Test;

/**
 * Unit tests for {@link AudioCapturePolicy}.
 *
 * <p>The table covers the whole decision surface, because the decision is what keeps the switch,
 * the recorded state and the notification text from disagreeing: no viewer means no capture, the
 * first viewer starts it again, and neither a failure nor a disabled switch is overridden.</p>
 */
public class AudioCapturePolicyTest {

    @Test
    public void noViewerStopsARunningCapture() {
        assertEquals(Action.STOP, AudioCapturePolicy.decide(0, true,
                AudioSessionState.ACTIVE, AudioAvailability.AVAILABLE));
        assertEquals(Action.STOP, AudioCapturePolicy.decide(0, true,
                AudioSessionState.STARTING, AudioAvailability.AVAILABLE));
        assertEquals(Action.STOP, AudioCapturePolicy.decide(0, true,
                AudioSessionState.ACTIVE, AudioAvailability.NO_VIEWERS));
    }

    @Test
    public void firstViewerStartsAStoppedCapture() {
        assertEquals(Action.START, AudioCapturePolicy.decide(1, true,
                AudioSessionState.OFF, AudioAvailability.AVAILABLE));
        assertEquals(Action.START, AudioCapturePolicy.decide(2, true,
                AudioSessionState.OFF, AudioAvailability.NO_VIEWERS));
    }

    @Test
    public void viewerJoiningNeverRestartsAStoppedOrRunningTrack() {
        assertEquals(Action.NONE, AudioCapturePolicy.decide(1, true,
                AudioSessionState.ACTIVE, AudioAvailability.AVAILABLE));
        assertEquals(Action.NONE, AudioCapturePolicy.decide(1, true,
                AudioSessionState.STARTING, AudioAvailability.AVAILABLE));
    }

    @Test
    public void viewerJoiningNeverRetriesAFailure() {
        assertEquals(Action.NONE, AudioCapturePolicy.decide(1, true,
                AudioSessionState.UNAVAILABLE, AudioAvailability.PERMISSION_DENIED));
        assertEquals(Action.NONE, AudioCapturePolicy.decide(1, true,
                AudioSessionState.UNAVAILABLE, AudioAvailability.CAPTURE_FAILED));
        assertEquals(Action.NONE, AudioCapturePolicy.decide(1, true,
                AudioSessionState.UNAVAILABLE, AudioAvailability.NO_VIEWERS));
    }

    @Test
    public void noViewerAndNoCapturePublishesThePausedReasonOnce() {
        assertEquals(Action.PAUSED, AudioCapturePolicy.decide(0, true,
                AudioSessionState.OFF, AudioAvailability.AVAILABLE));
        assertEquals(Action.NONE, AudioCapturePolicy.decide(0, true,
                AudioSessionState.OFF, AudioAvailability.NO_VIEWERS));
    }

    @Test
    public void noViewerKeepsAFailureVisible() {
        assertEquals(Action.NONE, AudioCapturePolicy.decide(0, true,
                AudioSessionState.UNAVAILABLE, AudioAvailability.PERMISSION_DENIED));
        assertEquals(Action.NONE, AudioCapturePolicy.decide(0, true,
                AudioSessionState.OFF, AudioAvailability.CAPTURE_FAILED));
    }

    @Test
    public void disabledSwitchLeavesEverythingToTheSwitchPath() {
        for (AudioSessionState state : AudioSessionState.values()) {
            for (AudioAvailability availability : AudioAvailability.values()) {
                assertEquals("viewers present, " + state + "/" + availability, Action.NONE,
                        AudioCapturePolicy.decide(1, false, state, availability));
                assertEquals("no viewer, " + state + "/" + availability, Action.NONE,
                        AudioCapturePolicy.decide(0, false, state, availability));
            }
        }
    }

    @Test
    public void negativeViewerCountIsTreatedAsNobody() {
        assertEquals(Action.STOP, AudioCapturePolicy.decide(-1, true,
                AudioSessionState.ACTIVE, AudioAvailability.AVAILABLE));
    }

    @Test
    public void nullArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> AudioCapturePolicy.decide(0, true,
                null, AudioAvailability.AVAILABLE));
        assertThrows(IllegalArgumentException.class, () -> AudioCapturePolicy.decide(0, true,
                AudioSessionState.OFF, null));
    }
}
