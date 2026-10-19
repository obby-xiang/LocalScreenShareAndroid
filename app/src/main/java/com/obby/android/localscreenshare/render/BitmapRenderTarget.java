package com.obby.android.localscreenshare.render;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.Surface;
import android.widget.ImageView;

import com.obby.android.localscreenshare.core.render.EncodedImageFrame;
import com.obby.android.localscreenshare.core.render.RenderTarget;

/**
 * Encoded-image {@link RenderTarget} that decodes JPEG frames into an {@link ImageView}.
 *
 * <p>{@link #getSurface()} always returns {@code null}: JPEG does not use a surface. The display
 * side owns the image view and applies any scaling, rounding or matrix outside this target.</p>
 */
public final class BitmapRenderTarget implements RenderTarget {

    private final ImageView imageView;

    /**
     * Creates a target over {@code imageView}.
     *
     * @throws IllegalArgumentException if {@code imageView} is null
     */
    public BitmapRenderTarget(ImageView imageView) {
        if (imageView == null) {
            throw new IllegalArgumentException("imageView must not be null");
        }
        this.imageView = imageView;
    }

    @Override
    public Surface getSurface() {
        return null;
    }

    @Override
    public void renderEncodedImage(EncodedImageFrame frame) {
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
        Bitmap bitmap = BitmapFactory.decodeByteArray(frame.getPayload(), 0,
                frame.getPayloadLength());
        if (bitmap == null) {
            throw new IllegalStateException("failed to decode JPEG frame");
        }
        imageView.setImageBitmap(bitmap);
    }
}
