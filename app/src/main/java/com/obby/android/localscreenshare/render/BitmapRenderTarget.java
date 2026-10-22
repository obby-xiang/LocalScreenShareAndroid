package com.obby.android.localscreenshare.render;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.core.render.EncodedImageFrame;
import com.obby.android.localscreenshare.core.render.RenderTarget;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Encoded-image {@link RenderTarget} that decodes JPEG frames for one display host.
 *
 * <p>JPEG is the compatibility mode of the stream: the server sends one complete image per frame and
 * the viewer decodes it here. That makes this target the bitmap half of a {@link HostRenderTarget},
 * next to the surface that carries H.264. {@link #getSurface()} always returns {@code null}, so a
 * codec that requires surface output can never be configured against this target, and
 * {@link #setContentSize(int, int)} is ignored because every frame carries its own visible size.</p>
 *
 * <p><b>Threading.</b> {@link #renderEncodedImage(EncodedImageFrame)} runs on the session's decode
 * thread and does not touch a view: it decodes the frame, keeps it as the pending frame and asks the
 * injected executor to deliver it. {@link #deliver()} runs on the main thread, hands the frame to
 * {@link ViewerHost#showEncodedFrame(Bitmap)} and reads back the host's content box, which is the
 * box the next frame is sampled for. Decoding off the main thread is what keeps a large JPEG from
 * blocking the view hierarchy, and the single pending frame is what keeps a slow host from queueing
 * a burst of stale pictures.</p>
 *
 * <p><b>Memory.</b> The frame is decoded with an {@code inSampleSize} taken from
 * {@link BitmapScalePolicy}, so a 1080p frame shown in a small floating window is never allocated at
 * full size. {@code inBitmap} is deliberately not used: the previous bitmap may still be displayed
 * while the next frame is decoded, and reusing its pixels would race with the view's drawing
 * thread. A bitmap that is replaced before it was ever displayed is safe to recycle and is recycled;
 * a bitmap that reached the host belongs to the view and is left to the garbage collector.</p>
 */
public final class BitmapRenderTarget implements RenderTarget {

    @NonNull
    private final ViewerHost host;

    // Main-thread delivery; injected so the target itself never assumes an Android looper.
    @NonNull
    private final Executor mainExecutor;

    // Decoded frame waiting for the main thread; replaced by every new frame.
    @NonNull
    private final AtomicReference<Bitmap> pending = new AtomicReference<>();

    // Whether a delivery is already queued; only one is kept in flight, so a burst collapses.
    @NonNull
    private final AtomicBoolean isDeliveryScheduled = new AtomicBoolean();

    // Content box of the host, read on the main thread while delivering and used on the decode
    // thread to pick a sample size. Zero means "not measured yet", which decodes at full size.
    private volatile int boxWidth;

    private volatile int boxHeight;

    /**
     * Creates the bitmap half of a host's output.
     *
     * @param host         display host that shows the decoded frames; required
     * @param mainExecutor executor that runs on the main thread; required
     * @throws IllegalArgumentException if an argument is null
     */
    public BitmapRenderTarget(@NonNull final ViewerHost host,
                              @NonNull final Executor mainExecutor) {
        if (host == null) {
            throw new IllegalArgumentException("host must not be null");
        }
        if (mainExecutor == null) {
            throw new IllegalArgumentException("mainExecutor must not be null");
        }
        this.host = host;
        this.mainExecutor = mainExecutor;
    }

    @Override
    @Nullable
    public Surface getSurface() {
        return null;
    }

    /**
     * Ignored: an encoded-image frame carries its own visible size.
     */
    @Override
    public void setContentSize(final int width, final int height) {
        // No surface buffer to size.
    }

    @Override
    public void renderEncodedImage(@NonNull final EncodedImageFrame frame) {
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = BitmapScalePolicy.sampleSize(
            frame.getWidth(), frame.getHeight(), boxWidth, boxHeight);
        final Bitmap bitmap = BitmapFactory.decodeByteArray(frame.getPayload(), 0,
            frame.getPayloadLength(), options);
        if (bitmap == null) {
            throw new IllegalStateException("failed to decode JPEG frame");
        }
        final Bitmap replaced = pending.getAndSet(bitmap);
        if (replaced != null) {
            // The replaced frame never reached the host, so no view holds it.
            replaced.recycle();
        }
        if (isDeliveryScheduled.compareAndSet(false, true)) {
            mainExecutor.execute(this::deliver);
        }
    }

    /**
     * Drops the pending frame and clears the host's frame layer; call when the host is torn down.
     *
     * <p>A frame already handed to the host is not recycled here: the view owns it until it is
     * replaced, and recycling it under the drawing thread would crash the renderer.</p>
     */
    public void clear() {
        final Bitmap dropped = pending.getAndSet(null);
        if (dropped != null) {
            dropped.recycle();
        }
        host.clearEncodedFrame();
    }

    /**
     * Shows the pending frame on the main thread and refreshes the sampling box.
     */
    private void deliver() {
        isDeliveryScheduled.set(false);
        final Bitmap bitmap = pending.getAndSet(null);
        if (bitmap == null) {
            return;
        }
        host.showEncodedFrame(bitmap);
        final Rect box = new Rect();
        host.getContentBox(box);
        boxWidth = box.width();
        boxHeight = box.height();
    }
}
