package com.obby.android.localscreenshare.core.render;

import android.view.Surface;

/**
 * Destination that receives decoded frames from a {@link Decoder}.
 *
 * <p>JPEG streams are delivered as {@link EncodedImageFrame}s; future MediaCodec
 * streams are rendered directly onto {@link #getSurface()}.</p>
 */
public interface RenderTarget {

    Surface getSurface();

    void renderEncodedImage(EncodedImageFrame frame);
}
