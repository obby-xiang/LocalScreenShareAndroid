package com.obby.android.localscreenshare.core.support;

import android.view.Surface;

import com.obby.android.localscreenshare.core.render.EncodedImageFrame;
import com.obby.android.localscreenshare.core.render.RenderTarget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JVM-only {@link RenderTarget} that records every rendered encoded image in order.
 *
 * <p>{@link #getSurface()} always returns {@code null}, so decoders must use the encoded-image
 * path. {@link #renderEncodedImage} rejects null frames. {@code close()} only counts
 * invocations, which lets tests assert that the runtime closed the target without any real
 * resource being involved. {@link #setContentSize(int, int)} and {@link #setFrameRate(int)} only
 * record the reported values.</p>
 */
public final class FakeRenderTarget implements RenderTarget {

    private final List<EncodedImageFrame> images = new ArrayList<>();

    private final List<int[]> contentSizes = new ArrayList<>();

    private final List<Integer> frameRates = new ArrayList<>();

    private int closeCount;

    @Override
    public Surface getSurface() {
        return null;
    }

    @Override
    public void setContentSize(int width, int height) {
        contentSizes.add(new int[]{width, height});
    }

    @Override
    public void setFrameRate(int frameRate) {
        frameRates.add(frameRate);
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

    /**
     * Returns the frame sizes reported through
     * {@link #setContentSize(int, int)}, in call order.
     */
    public List<int[]> getContentSizes() {
        return Collections.unmodifiableList(contentSizes);
    }

    /**
     * Returns the content frame-rate hints reported through
     * {@link #setFrameRate(int)}, in call order.
     */
    public List<Integer> getFrameRates() {
        return Collections.unmodifiableList(frameRates);
    }

    public void close() {
        closeCount++;
    }

    public int getCloseCount() {
        return closeCount;
    }
}
