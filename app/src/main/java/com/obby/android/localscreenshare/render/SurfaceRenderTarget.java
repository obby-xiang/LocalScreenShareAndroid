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

    private final Runnable onSurfaceAvailable;

    private SurfaceTexture lastTexture;

    private Surface surface;

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
                return false;
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
            surface = new Surface(texture);
        }
        return surface;
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
    }

    private void releaseSurface() {
        if (surface != null) {
            surface.release();
            surface = null;
        }
    }
}
