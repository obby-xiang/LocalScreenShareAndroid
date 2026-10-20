package com.obby.android.localscreenshare.render;

import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;

import com.obby.android.localscreenshare.core.render.EncodedImageFrame;
import com.obby.android.localscreenshare.core.render.RenderTarget;

/**
 * Surface-output {@link RenderTarget} backed by a {@link TextureView}.
 *
 * <p>The surface is created from the view's current {@link SurfaceTexture} and cached; it is
 * recreated only when the underlying texture changes and released by {@link #release()}, which the
 * display side calls when the window is torn down. Before the texture is available
 * {@link #getSurface()} returns {@code null}, which keeps a surface-output codec's session in the
 * resynchronizing state until the window is ready. When the texture is destroyed the cached surface
 * is released and the framework is asked to release the old texture, so a later re-attach starts
 * from a fresh texture and re-runs the availability callback.</p>
 *
 * <p><b>Buffer geometry.</b> The surface texture allocates its buffers with a default size that it
 * takes from the view at creation time, and a producer such as a hardware decoder only overrides
 * that size when it is told to. A stream whose coded size changes during a session therefore has to
 * report the new size through {@link #setContentSize(int, int)}, which forwards it to
 * {@link SurfaceTexture#setDefaultBufferSize(int, int)}: without it the texture keeps allocating
 * buffers of the previous size and the decoder output is drawn into a fraction of the window until
 * the viewer reconnects. The requested size is remembered and re-applied whenever a new texture
 * appears, so a window that is re-attached after being torn down starts with the right geometry.</p>
 */
public final class SurfaceRenderTarget implements RenderTarget {

    private final TextureView textureView;

    private final Runnable onSurfaceAvailable;

    private SurfaceTexture lastTexture;

    private Surface surface;

    // Frame size requested by the session and the size last pushed into the current texture; both
    // zero until a descriptor arrives or a new texture needs the size again.
    private int contentWidth;

    private int contentHeight;

    private int appliedWidth;

    private int appliedHeight;

    /**
     * Creates a target over {@code textureView}.
     *
     * @param onSurfaceAvailable optional callback, run on the main thread each time the view's
     *                           surface texture becomes available again
     * @throws IllegalArgumentException if {@code textureView} is null
     */
    public SurfaceRenderTarget(TextureView textureView, Runnable onSurfaceAvailable) {
        if (textureView == null) {
            throw new IllegalArgumentException("textureView must not be null");
        }
        this.textureView = textureView;
        this.onSurfaceAvailable = onSurfaceAvailable;
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                if (onSurfaceAvailable != null) {
                    onSurfaceAvailable.run();
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                // The view is leaving the window: drop the cached Surface so a later getSurface()
                // returns null until the view is re-attached with a fresh texture. Returning true
                // lets the framework release the old texture instead of leaking it.
                releaseSurface();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            }
        });
    }

    @Override
    public Surface getSurface() {
        SurfaceTexture texture = textureView.getSurfaceTexture();
        if (texture == null) {
            return null;
        }
        if (texture != lastTexture) {
            releaseSurface();
            lastTexture = texture;
        }
        applyBufferSize(texture);
        if (surface == null) {
            surface = new Surface(texture);
        }
        return surface;
    }

    /**
     * Stores the coded frame size and pushes it into the current texture, if there is one.
     *
     * <p>Called by the client session after the previous decoder was closed and before the new one
     * is configured, so no producer is connected while the buffer geometry changes. A texture that
     * does not exist yet keeps the size for {@link #getSurface()}.</p>
     */
    @Override
    public void setContentSize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        contentWidth = width;
        contentHeight = height;
        SurfaceTexture texture = textureView.getSurfaceTexture();
        if (texture != null) {
            if (texture != lastTexture) {
                releaseSurface();
                lastTexture = texture;
            }
            applyBufferSize(texture);
        }
    }

    @Override
    public void renderEncodedImage(EncodedImageFrame frame) {
        throw new UnsupportedOperationException(
                "surface render target does not render encoded images");
    }

    /**
     * Releases the cached surface; call when the window is torn down.
     */
    public void release() {
        releaseSurface();
        lastTexture = null;
        appliedWidth = 0;
        appliedHeight = 0;
    }

    private void applyBufferSize(SurfaceTexture texture) {
        if (contentWidth <= 0 || contentHeight <= 0) {
            return;
        }
        if (appliedWidth == contentWidth && appliedHeight == contentHeight) {
            return;
        }
        texture.setDefaultBufferSize(contentWidth, contentHeight);
        appliedWidth = contentWidth;
        appliedHeight = contentHeight;
    }

    private void releaseSurface() {
        if (surface != null) {
            surface.release();
            surface = null;
        }
    }
}
