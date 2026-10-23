package com.obby.android.localscreenshare.core.render;

import android.view.Surface;

/**
 * Destination that receives decoded or display-ready media from a stream.
 *
 * <p>A target offers two output paths and a codec uses one of them. Surface-output codecs render
 * into the surface returned by {@link #getSurface()}; codecs that produce compressed images hand
 * them to {@link #renderEncodedImage(EncodedImageFrame)}. The target is owned by the display side
 * and outlives every decoder: decoders must not close or release it, and callers must not replace
 * it while a decoder is configured against it.</p>
 *
 * <p>Methods are called on the thread that drives decoding, which is serialized by the owning
 * client session; implementations should not assume a particular thread but are not required to
 * support concurrent calls.</p>
 */
public interface RenderTarget {

    /**
     * Returns the surface to render into, or {@code null} when no surface is currently attached.
     *
     * <p>For a codec whose descriptor requires surface output, a client session must not configure
     * a decoder while this returns null; it stays in the resynchronizing state and retries later.
     * The returned surface is owned by this target and must not be released by a decoder.</p>
     */
    Surface getSurface();

    /**
     * Declares the pixel size of the frames the decoder will render into {@link #getSurface()}.
     *
     * <p>A surface-backed target has to be told the frame size explicitly: the surface texture it
     * wraps keeps the buffer geometry of the view it was created from, so after a stream changes
     * its coded size in the middle of a session the texture would keep allocating buffers of the
     * previous size and the picture would be drawn into a corner of the window until the viewer
     * reconnected. The session reports the size before it creates the decoder for a descriptor, and
     * a target without a surface path may ignore the call.</p>
     *
     * <p>The size is the decoder's coded size, not the visible size: it describes the buffers the
     * codec writes, which may include alignment padding.</p>
     *
     * @param width  coded frame width in pixels; non-positive means "unknown"
     * @param height coded frame height in pixels; non-positive means "unknown"
     */
    void setContentSize(int width, int height);

    /**
     * Declares the content frame rate of the frames rendered into {@link #getSurface()}.
     *
     * <p>A hint only. Zero means "unknown or cleared": implementations must not assert anything on
     * the platform and must stop replaying a previously asserted value. Interface default methods
     * are supported natively at minSdk 25, so no desugaring is involved.</p>
     *
     * @param frameRate content frame rate in frames per second; non-positive means unknown or
     *                  cleared
     */
    default void setFrameRate(int frameRate) {
    }

    /**
     * Renders one compressed image frame.
     *
     * <p>Used by codecs that deliver encoded images instead of a surface. The payload is
     * compressed data, never decoded pixels; this target is responsible for decoding and display.
     * An implementation may throw an unchecked exception on failure, which the decoder wraps in
     * {@code DecoderException}.</p>
     */
    void renderEncodedImage(EncodedImageFrame frame);
}
