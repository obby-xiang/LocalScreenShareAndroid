package com.obby.android.localscreenshare.render;

import android.graphics.Bitmap;
import android.graphics.Rect;
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
 * <p><b>Threading.</b> All methods except {@link #showEncodedFrame(Bitmap)} are called on the main
 * thread by the service. The texture view the host returns is the decoder output surface, and the
 * service wraps it together with the host's frame layer in its own {@code HostRenderTarget}, which
 * it releases when the host is detached. {@link #showEncodedFrame(Bitmap)} also runs on the main
 * thread, but it is posted by the bitmap half rather than called by the service, so a decoded JPEG
 * frame never touches a view on the decode thread.</p>
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
     * Shows one decoded JPEG frame.
     *
     * <p>Called on the main thread by the bitmap half of the host's render target. The frame is
     * drawn in the same content box as the live picture and stays until the next frame replaces it,
     * the session ends, or the host detaches. The host owns the bitmap from now on and must not
     * recycle it.</p>
     *
     * @param frame decoded frame; required
     */
    void showEncodedFrame(Bitmap frame);

    /**
     * Clears the JPEG frame layer, restoring the surface-only picture.
     *
     * <p>Called when a JPEG session ends or the host is torn down. An H.264 session never shows a
     * frame here, so the layer stays hidden and does not affect that path.</p>
     */
    void clearEncodedFrame();

    /**
     * Reports the box the picture is drawn in, in view coordinates.
     *
     * <p>The bitmap target uses it to pick a decode sample size: without it a frame would always be
     * decoded at the encoder's coded size, however small the window is. An empty rectangle means the
     * box is not known yet, which decodes at full size.</p>
     *
     * @param out receives the box; required
     */
    void getContentBox(Rect out);

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
