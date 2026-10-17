package com.obby.android.localscreenshare.core.support;

import android.view.Surface;

import com.obby.android.localscreenshare.core.render.EncodedImageFrame;
import com.obby.android.localscreenshare.core.render.RenderTarget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JVM-only {@link RenderTarget} that records every rendered encoded image.
 */
public final class FakeRenderTarget implements RenderTarget {

    private final List<EncodedImageFrame> images = new ArrayList<>();

    @Override
    public Surface getSurface() {
        return null;
    }

    @Override
    public void renderEncodedImage(EncodedImageFrame frame) {
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
        images.add(frame);
    }

    public List<EncodedImageFrame> getImages() {
        return Collections.unmodifiableList(images);
    }
}
