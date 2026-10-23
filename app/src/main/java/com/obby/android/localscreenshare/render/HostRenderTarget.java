package com.obby.android.localscreenshare.render;

import android.view.Surface;

import androidx.annotation.NonNull;

import com.obby.android.localscreenshare.core.render.EncodedImageFrame;
import com.obby.android.localscreenshare.core.render.RenderTarget;

/**
 * Render target of one display host: a surface half for H.264 and a bitmap half for JPEG.
 *
 * <p>The stream delivers one of two kinds of media depending on the codec the server selected:
 * surface-output codecs (H.264) render into a surface, encoded-image codecs (JPEG) hand over
 * complete frames. A host has to be able to show either, and which one arrives is only known when
 * the descriptor reaches the session, which happens after the target was created and handed over.
 * Composing both halves up front therefore removes the need to swap the target at runtime: the
 * session keeps one stable target, and the method a codec calls decides which half is used.</p>
 *
 * <p>Dispatch is by method, not by state: {@link #getSurface()},
 * {@link #setContentSize(int, int)} and {@link #setFrameRate(int)} go to the surface half, while
 * {@link #renderEncodedImage} goes to the bitmap half. The bitmap half is never touched by an
 * H.264 session, and the surface half is never touched by a JPEG session.</p>
 *
 * <p><b>Release.</b> Both halves belong to the host and are released together when the host is torn
 * down or the session ends. Only the concrete implementations own a resource that has to be released
 * ({@link SurfaceRenderTarget} caches a {@link Surface}, {@link BitmapRenderTarget} holds a pending
 * bitmap and a frame layer), so the release is applied to each half that is one of them; a plain
 * target, such as a test double, has nothing to release.</p>
 */
public final class HostRenderTarget implements RenderTarget {

    @NonNull
    private final RenderTarget surfaceTarget;

    @NonNull
    private final RenderTarget bitmapTarget;

    /**
     * Creates the composed target of one host.
     *
     * @param surfaceTarget surface-output half; required
     * @param bitmapTarget  encoded-image half; required
     * @throws IllegalArgumentException if an argument is null
     */
    public HostRenderTarget(@NonNull final RenderTarget surfaceTarget,
                            @NonNull final RenderTarget bitmapTarget) {
        if (surfaceTarget == null) {
            throw new IllegalArgumentException("surfaceTarget must not be null");
        }
        if (bitmapTarget == null) {
            throw new IllegalArgumentException("bitmapTarget must not be null");
        }
        this.surfaceTarget = surfaceTarget;
        this.bitmapTarget = bitmapTarget;
    }

    @Override
    public Surface getSurface() {
        return surfaceTarget.getSurface();
    }

    @Override
    public void setContentSize(final int width, final int height) {
        surfaceTarget.setContentSize(width, height);
    }

    @Override
    public void setFrameRate(final int frameRate) {
        surfaceTarget.setFrameRate(frameRate);
    }

    @Override
    public void renderEncodedImage(final EncodedImageFrame frame) {
        bitmapTarget.renderEncodedImage(frame);
    }

    /**
     * Releases the resources of both halves; call when the host is torn down.
     */
    public void release() {
        if (surfaceTarget instanceof SurfaceRenderTarget) {
            ((SurfaceRenderTarget) surfaceTarget).release();
        }
        if (bitmapTarget instanceof BitmapRenderTarget) {
            ((BitmapRenderTarget) bitmapTarget).clear();
        }
    }
}
