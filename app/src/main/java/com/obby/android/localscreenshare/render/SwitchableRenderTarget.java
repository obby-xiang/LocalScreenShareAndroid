package com.obby.android.localscreenshare.render;

import android.view.Surface;

import com.obby.android.localscreenshare.core.render.EncodedImageFrame;
import com.obby.android.localscreenshare.core.render.RenderTarget;

/**
 * Render target that forwards every call to the display target that is currently active.
 *
 * <p>The client session is bound to one target for its whole life, because a decoder is created
 * against the surface that target exposes. A viewer that can move between a floating window and a
 * full-screen activity changes the surface underneath it, and the session must not be rebuilt for
 * that: rebuilding it would drop the descriptor, the secure flag and the geometry reports that only
 * arrive once per connection. This indirection gives the session a stable target whose delegate can
 * be replaced, which is what lets the owning service re-point the decoder output at another window
 * without touching the network session.</p>
 *
 * <p><b>Switching protocol.</b> Replace the delegate first, then ask the session to re-create its
 * decoder through {@code ClientMediaSession.rebindSurface()}. The old target must not be released
 * or detached before that call returned, because the decoder still writes into its surface until
 * the session closes it. The delegate is volatile because frames are rendered from the decode thread
 * while the main thread performs the switch; the session serializes decoder creation against
 * decoding, so a frame either goes to the previous target or the new one, never to a target that
 * was released in between.</p>
 *
 * <p><b>Content frame rate.</b> The hint is remembered here and forwarded only when the value
 * changes, so a repeated descriptor does not assert it twice. After a switch the new delegate gets
 * the remembered hint once, but only when it is positive: zero means "unknown or cleared" and must
 * not be replayed onto a host that never heard of the previous value.</p>
 */
public final class SwitchableRenderTarget implements RenderTarget {

    // Read on the decode thread for every rendered frame, written on the main thread when the
    // display host changes.
    private volatile RenderTarget delegate;

    // Content frame-rate hint of the current stream; zero means unknown or cleared.
    private volatile int frameRate;

    /**
     * Creates a target whose initial delegate is {@code initialDelegate}.
     *
     * @param initialDelegate target that receives frames until the first switch; required
     * @throws IllegalArgumentException if {@code initialDelegate} is null
     */
    public SwitchableRenderTarget(RenderTarget initialDelegate) {
        if (initialDelegate == null) {
            throw new IllegalArgumentException("initialDelegate must not be null");
        }
        this.delegate = initialDelegate;
    }

    /**
     * Returns the target that currently receives frames.
     */
    public RenderTarget getDelegate() {
        return delegate;
    }

    /**
     * Re-points this target at {@code nextDelegate}; frames rendered afterwards reach it.
     *
     * <p>The caller is responsible for the switch protocol described in the class documentation:
     * replace the delegate, re-create the decoder, and only then release the previous target.</p>
     *
     * @param nextDelegate target that receives frames from now on; required
     * @throws IllegalArgumentException if {@code nextDelegate} is null
     */
    public void setDelegate(RenderTarget nextDelegate) {
        if (nextDelegate == null) {
            throw new IllegalArgumentException("nextDelegate must not be null");
        }
        this.delegate = nextDelegate;
        if (frameRate > 0) {
            nextDelegate.setFrameRate(frameRate);
        }
    }

    @Override
    public Surface getSurface() {
        return delegate.getSurface();
    }

    @Override
    public void setContentSize(int width, int height) {
        delegate.setContentSize(width, height);
    }

    @Override
    public void setFrameRate(int frameRate) {
        int normalized = Math.max(0, frameRate);
        if (normalized == this.frameRate) {
            return;
        }
        this.frameRate = normalized;
        delegate.setFrameRate(normalized);
    }

    @Override
    public void renderEncodedImage(EncodedImageFrame frame) {
        delegate.renderEncodedImage(frame);
    }
}
