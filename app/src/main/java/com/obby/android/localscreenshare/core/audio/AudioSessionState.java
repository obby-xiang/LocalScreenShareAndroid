package com.obby.android.localscreenshare.core.audio;

/**
 * Lifecycle state of the audio track, independent of the video stream.
 *
 * <p>The state is the single source of truth the user interface reads: "the switch is on" and
 * "audio is being captured" must never disagree. {@code OFF} means nothing is captured;
 * {@code STARTING} covers opening the recorder and configuring the encoder; {@code ACTIVE} means
 * frames are flowing; {@code UNAVAILABLE} means the attempt failed and only an explicit retry (the
 * user toggling the switch again) leaves it. Every transition is validated here so a race cannot
 * quietly produce an active capture behind an off switch.</p>
 */
public enum AudioSessionState {

    /**
     * Audio is off; nothing is captured and no audio session exists.
     */
    OFF,

    /**
     * Capture is being opened and configured.
     */
    STARTING,

    /**
     * Frames are being captured, encoded and fanned out.
     */
    ACTIVE,

    /**
     * Audio failed; the failure is visible and the video keeps running.
     */
    UNAVAILABLE;

    /**
     * Returns whether {@code next} is a legal successor of this state.
     *
     * <p>{@code UNAVAILABLE} may only be left by turning audio on again ({@code STARTING}) or off
     * ({@code OFF}); an unavailable track is never resumed silently.</p>
     */
    public boolean canTransitionTo(AudioSessionState next) {
        if (next == null) {
            return false;
        }
        switch (this) {
            case OFF:
                return next == STARTING;
            case STARTING:
                return next == ACTIVE || next == UNAVAILABLE || next == OFF;
            case ACTIVE:
                return next == UNAVAILABLE || next == OFF;
            case UNAVAILABLE:
                return next == STARTING || next == OFF;
            default:
                return false;
        }
    }

    /**
     * Returns whether audio is being captured or is about to be.
     */
    public boolean isRunning() {
        return this == STARTING || this == ACTIVE;
    }
}
