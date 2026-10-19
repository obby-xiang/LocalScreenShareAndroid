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
 * resynchronizing state until the window is ready.</p>
 */
public final class SurfaceRenderTarget implements RenderTarget {

    private final TextureView textureView;

    private SurfaceTexture lastTexture;

    private Surface surface;

    /**
     * Creates a target over {@code textureView}.
     *
     * @throws IllegalArgumentException if {@code textureView} is null
     */
    public SurfaceRenderTarget(TextureView textureView) {
        if (textureView == null) {
            throw new IllegalArgumentException("textureView must not be null");
        }
        this.textureView = textureView;
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
            surface = new Surface(texture);
        }
        return surface;
    }

    @Override
    public void renderEncodedImage(EncodedImageFrame frame) {
        throw new UnsupportedOperationException(
                "surface render target does not render encoded images");
    }

    /** Releases the cached surface; call when the window is torn down. */
    public void release() {
        releaseSurface();
        lastTexture = null;
    }

    private void releaseSurface() {
        if (surface != null) {
            surface.release();
            surface = null;
        }
    }
}
