package com.obby.android.localscreenshare.core.audio;

/**
 * Decides whether playback-audio capture should run, given how many viewers are connected.
 *
 * <p>Audio is captured only while somebody can hear it (decision D9 of the audio plan): the first
 * viewer starts the recorder and the last one releases it, so a share that nobody watches does not
 * hold the capture device or spend power on encoding. The decision is a pure function of the
 * switch intent, the viewer count, the lifecycle state and the published reason, which is what lets
 * the JVM tests cover every combination; the service only maps the returned action to
 * {@code start}/{@code stop} calls and to the notification.</p>
 *
 * <p><b>Two states are sticky.</b> An {@link AudioSessionState#UNAVAILABLE} track is never resumed
 * by a viewer joining, because the state machine promises that only an explicit retry leaves a
 * failure; and a reason that already explains a failure
 * ({@link AudioAvailability#PERMISSION_DENIED} and the other failure reasons) is never overwritten
 * by {@link AudioAvailability#NO_VIEWERS}, so a broken capture does not look like a paused one.</p>
 */
public final class AudioCapturePolicy {

    /**
     * What the share side should do with capture now.
     */
    public enum Action {

        /**
         * Capture and the reported reason already match the viewer count; change nothing.
         */
        NONE,

        /**
         * Capture should run and does not: open the recorder.
         */
        START,

        /**
         * Capture runs although no viewer is connected: release the recorder.
         */
        STOP,

        /**
         * Capture must not run although the switch is on and nothing was opened yet: publish
         * {@link AudioAvailability#NO_VIEWERS} so the notification explains why there is no sound.
         */
        PAUSED
    }

    private AudioCapturePolicy() {
    }

    /**
     * Decides the next capture action.
     *
     * @param viewerCount     connected viewers; a value of zero or less means nobody is watching
     * @param isSwitchEnabled whether the user asked for sound; a disabled switch leaves capture and
     *                        the reason to the switch path
     * @param state           current audio lifecycle state; must not be null
     * @param availability    reason currently published; must not be null
     * @return the action to apply, never null
     * @throws IllegalArgumentException if a required argument is null
     */
    public static Action decide(int viewerCount, boolean isSwitchEnabled,
                                AudioSessionState state, AudioAvailability availability) {
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        if (availability == null) {
            throw new IllegalArgumentException("availability must not be null");
        }
        if (!isSwitchEnabled) {
            return Action.NONE;
        }
        if (viewerCount > 0) {
            // A failure (UNAVAILABLE) and an open in progress (STARTING) are left alone, so a
            // viewer joining never retries a failed track or races the start path.
            if (state == AudioSessionState.OFF
                    && (availability == AudioAvailability.AVAILABLE
                    || availability == AudioAvailability.NO_VIEWERS)) {
                return Action.START;
            }
            return Action.NONE;
        }
        if (state.isRunning()) {
            return Action.STOP;
        }
        if (state == AudioSessionState.OFF && availability == AudioAvailability.AVAILABLE) {
            return Action.PAUSED;
        }
        return Action.NONE;
    }
}
