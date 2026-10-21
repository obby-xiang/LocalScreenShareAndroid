package com.obby.android.localscreenshare.core.stream;

/**
 * Decides whether the capture display keeps rendering into the encoder input.
 *
 * <p><b>Why.</b> A running share composes the screen into the virtual display and encodes every
 * frame even when no client is attached, so an unwatched share costs the same heat and battery as
 * a watched one. This policy answers the single question the owner has to act on: with the current
 * viewer count, should capture keep running, pause, or resume?</p>
 *
 * <p><b>First publication gate.</b> Pausing is only allowed after the runtime became
 * {@code READY}. An H.264 encoder reports its configuration (SPS/PPS plus the coded size) from its
 * first output format, which needs at least one frame; a display that never rendered would leave
 * the runtime in {@code CONFIGURING} with no published descriptor, and every join would be
 * answered {@code NOT_READY} forever. Capture therefore always runs until the first configuration
 * exists, and the first viewer after that finds a ready stream.</p>
 *
 * <p><b>Unknown viewer count.</b> A negative count means the owner does not know (no hub, or a
 * teardown in progress) and must keep the current state; treating it as zero would pause capture
 * during teardown for no benefit.</p>
 *
 * <p>Pure decision, no Android types, so the rule is covered by JVM tests instead of a device.</p>
 */
public final class ViewerCapturePolicy {

    /**
     * Action the capture owner has to perform; {@link #NONE} means "leave it as it is".
     */
    public enum Action {

        /**
         * Keep the current capture state.
         */
        NONE,

        /**
         * Detach the capture display from the encoder input.
         */
        PAUSE,

        /**
         * Re-attach the capture display and ask for a random-access point.
         */
        RESUME
    }

    private ViewerCapturePolicy() {
    }

    /**
     * Decides what to do with capture for the given viewer count and stream state.
     *
     * @param viewers        number of attached clients; a negative value means unknown
     * @param isCapturePaused whether capture currently paused
     * @param isStreamReady  whether the runtime published its first configuration
     * @return the action to perform; never null
     */
    public static Action decide(int viewers, boolean isCapturePaused, boolean isStreamReady) {
        if (viewers < 0 || !isStreamReady) {
            return Action.NONE;
        }
        if (viewers == 0) {
            return isCapturePaused ? Action.NONE : Action.PAUSE;
        }
        return isCapturePaused ? Action.RESUME : Action.NONE;
    }
}
