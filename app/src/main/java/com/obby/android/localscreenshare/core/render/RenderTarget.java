package com.obby.android.localscreenshare.core.render;

import android.view.Surface;

/**
 * Destination that receives encoded media from a stream.
 *
 * <p>An implementation consumes either a {@link Surface} via {@link #getSurface()}
 * or encoded image frames via {@link #renderEncodedImage(EncodedImageFrame)}.</p>
 */
public interface RenderTarget {

    Surface getSurface();

    void renderEncodedImage(EncodedImageFrame frame);
}
