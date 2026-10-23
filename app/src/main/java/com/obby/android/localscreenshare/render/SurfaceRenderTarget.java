package com.obby.android.localscreenshare.render;

import android.graphics.SurfaceTexture;
import android.os.Build;
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
 *
 * <p><b>Content frame rate.</b> The session reports the descriptor's content frame rate as a
 * display hint. It is applied only while a surface exists and the platform has the API for it
 * ({@link FrameRatePolicy} decides the variant); a hint that arrives before the surface is
 * remembered and applied when {@link #getSurface()} creates it. The same value is never sent
 * twice to one surface, but a newly created surface always starts with no applied value and
 * receives the hint once more, so the "equal value" early return can never suppress the replay.
 * Zero clears the local hint without touching the platform and without being replayed later. A
 * platform or vendor rejection is swallowed: the hint is best effort and must not disturb
 * playback.</p>
 */
public final class SurfaceRenderTarget implements RenderTarget {

    private final TextureView textureView;

    private final Runnable onSurfaceAvailable;

    private final SurfaceFactory surfaceFactory;

    private SurfaceTexture lastTexture;

    // Written on the thread that drives decoding and released from the view's listener thread.
    private volatile Surface surface;

    // Content frame-rate hint requested by the session and the value last asserted on the current
    // surface; the latter drops back to zero whenever the surface is released.
    private volatile int frameRate;

    private volatile int appliedFrameRate;

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
        this(textureView, onSurfaceAvailable, Surface::new);
    }

    /**
     * Creates a target over {@code textureView} with an injected surface factory.
     *
     * <p>Package-private seam for JVM tests: a unit test cannot construct a platform
     * {@link Surface}.</p>
     *
     * @param onSurfaceAvailable optional callback, run on the main thread each time the view's
     *                           surface texture becomes available again
     * @param surfaceFactory     creates the platform surface for a texture; required
     * @throws IllegalArgumentException if {@code textureView} or {@code surfaceFactory} is null
     */
    SurfaceRenderTarget(TextureView textureView, Runnable onSurfaceAvailable,
                        SurfaceFactory surfaceFactory) {
        if (textureView == null) {
            throw new IllegalArgumentException("textureView must not be null");
        }
        if (surfaceFactory == null) {
            throw new IllegalArgumentException("surfaceFactory must not be null");
        }
        this.textureView = textureView;
        this.onSurfaceAvailable = onSurfaceAvailable;
        this.surfaceFactory = surfaceFactory;
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
        boolean created = false;
        if (surface == null) {
            surface = surfaceFactory.create(texture);
            created = true;
        }
        if (created) {
            // A new surface owns no assertion yet. Clear the value recorded on the previous one
            // first, otherwise an equal hint would take the early return and the new surface
            // would never receive it.
            appliedFrameRate = 0;
            // Covers a hint that arrived before the surface existed, and a re-attached texture.
            applyFrameRate();
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

    /**
     * Stores the content frame-rate hint and asserts it on the current surface, if any.
     *
     * <p>Called by the client session before a decoder is configured. Zero clears the local hint
     * and leaves the platform alone; a positive value is asserted at most once per surface and is
     * asserted again when {@link #getSurface()} creates a new one.</p>
     */
    @Override
    public void setFrameRate(int frameRate) {
        int normalized = Math.max(0, frameRate);
        this.frameRate = normalized;
        if (normalized == 0) {
            appliedFrameRate = 0;
            return;
        }
        applyFrameRate();
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

    /**
     * Asserts the remembered hint on the current surface, swallowing platform rejections.
     *
     * <p>The API split lives in {@link FrameRatePolicy}; the explicit level checks here are what
     * let the platform call sites be verified statically, and they repeat a decision the policy
     * makes anyway. The applied value is recorded before the call so a failing surface is not
     * retried on every frame; a new surface resets it through {@link #releaseSurface()} and again
     * when {@link #getSurface()} creates it.</p>
     */
    private void applyFrameRate() {
        final Surface current = surface;
        final int rate = frameRate;
        if (current == null || rate <= 0 || appliedFrameRate == rate) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        appliedFrameRate = rate;
        try {
            FrameRatePolicy.applyFrameRate(rate, Build.VERSION.SDK_INT, (fps, seamless) -> {
                if (seamless && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    current.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                            Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    current.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
                }
            });
        } catch (RuntimeException e) {
            // The hint is best effort: a vendor surface that rejects it must not stop playback.
        }
    }

    private void releaseSurface() {
        Surface current = surface;
        if (current != null) {
            current.release();
            surface = null;
        }
        appliedFrameRate = 0;
    }

    /**
     * Creates the platform surface that receives decoded frames.
     *
     * <p>Package-private so a JVM test can supply a marker surface: the platform constructor is
     * native and cannot run off-device.</p>
     */
    interface SurfaceFactory {

        /**
         * Creates a surface for {@code texture}.
         *
         * @param texture texture the surface renders into
         * @return a new surface; must not be null
         */
        Surface create(SurfaceTexture texture);
    }
}
