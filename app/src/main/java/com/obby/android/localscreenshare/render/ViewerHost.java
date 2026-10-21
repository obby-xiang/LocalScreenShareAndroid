package com.obby.android.localscreenshare.render;

import android.graphics.Bitmap;
import android.view.TextureView;

import java.util.List;

/**
 * One display host of a viewing session: the floating window or the full-screen activity.
 *
 * <p>The session owns the network stream, the decoder and the last frame; a host only shows what
 * the session gives it. Moving the output from one host to the other is therefore an attach/detach
 * of this contract - never a new connection, never a new service instance. The service keeps the
 * stream geometry, the secure flag and the debug lines and forwards them to whichever host is
 * active, so a host that becomes active late still receives the current values instead of waiting
 * for the next descriptor.</p>
 *
 * <p><b>Threading.</b> All methods are called on the main thread by the service. The texture view
 * the host returns is the decoder output surface; the service wraps it in its own
 * {@code SurfaceRenderTarget} and releases that target when the host is detached.</p>
 */
public interface ViewerHost {

    /**
     * Returns the texture view the decoder renders into; never null.
     */
    TextureView getTextureView();

    /**
     * Applies the visible stream geometry reported by the descriptor.
     *
     * @param visibleWidth    visible stream width in pixels
     * @param visibleHeight   visible stream height in pixels
     * @param rotationDegrees rotation applied to the content
     */
    void setContentGeometry(int visibleWidth, int visibleHeight, int rotationDegrees);

    /**
     * Applies the secure-content flag of the session.
     *
     * @param secure whether the displayed picture must be excluded from screenshots and recordings
     */
    void setSecure(boolean secure);

    /**
     * Replaces the debug overlay lines.
     *
     * @param lines rendered lines, or an empty list to hide the overlay
     */
    void setHudLines(List<String> lines);

    /**
     * Shows one captured frame above the live picture until the first decoded frame arrives.
     *
     * @param snapshot frame to show, or null to hide the layer
     */
    void showSnapshot(Bitmap snapshot);

    /**
     * Hides the captured frame; called when the live picture is available again.
     */
    void hideSnapshot();

    /**
     * Captures the frame this host is currently showing.
     *
     * <p>Called right before the output moves to the other host, which displays the result until its
     * own texture produced a decoded frame. Returning null is allowed: the switch then simply has no
     * still picture to show.</p>
     *
     * @return a copy of the current frame, or null when it cannot be read
     */
    Bitmap captureSnapshot();

    /**
     * Reports that the session ended, so a host that lives in the back stack leaves with it.
     */
    void onSessionEnded();
}
