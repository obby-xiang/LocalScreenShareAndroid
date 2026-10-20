package com.obby.android.localscreenshare.client;

/**
 * Receives the display geometry of the stream a client session is about to decode.
 *
 * <p>A descriptor is the only place the display side learns how large the incoming content is and
 * how it is rotated; without it the viewer can only size its window from its own screen and the
 * decoded pixels get stretched. The callback is reported on the client's decode thread before the
 * session applies the descriptor, so the display side is expected to hand the values to its own
 * thread before touching any window.</p>
 */
public interface StreamGeometryListener {

    /**
     * Reports the visible stream size and the rotation the display side must apply.
     *
     * <p>The visible size excludes encoder alignment padding, so it — not the coded size — is the
     * geometry the viewer keeps its aspect ratio from. Rotation is reported as
     * {@code 0}, {@code 90}, {@code 180} or {@code 270} degrees.</p>
     *
     * @param visibleWidth    width of the region to display in pixels; always positive
     * @param visibleHeight   height of the region to display in pixels; always positive
     * @param rotationDegrees display rotation in degrees
     */
    void onStreamGeometry(int visibleWidth, int visibleHeight, int rotationDegrees);
}
