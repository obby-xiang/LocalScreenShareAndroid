package com.obby.android.localscreenshare.render;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;

/**
 * Video surface, last-frame snapshot and debug overlay shared by both display hosts.
 *
 * <p>The floating window and the full-screen activity must show exactly the same picture and offer
 * the same debug overlay, so the rendering part lives here once: a {@link TextureView} the decoder
 * renders into directly, an {@link ImageView} that holds one captured frame during a host switch and
 * a monospaced text overlay. The owner only decides where the content box goes - the window sizes
 * itself to the content, the activity fills the display and letterboxes it - and supplies the
 * stream geometry.</p>
 *
 * <p><b>Content geometry.</b> {@link #setContentGeometry(int, int, int)} stores the visible size and
 * rotation reported by the stream descriptor; {@link #setDisplaySize(int, int)} sets the box the
 * rotated picture should fill, centered in the view. A zero display size means "the whole view",
 * which is the window case. {@link ViewerContentTransform} turns both into the texture transform, so
 * a quarter-turn stream is rotated and a letterboxed picture keeps its aspect ratio.</p>
 *
 * <p><b>Snapshot.</b> {@link #captureSnapshot()} reads one frame from the texture; the caller hands
 * it to the incoming host, which shows it through {@link #showSnapshot(Bitmap)} until its first
 * decoded frame arrives and then drops it with {@link #fadeOutSnapshot(long)}. The snapshot layer is
 * UI only: it is not a second decoder output, and it never starts or stops decoding.</p>
 *
 * <p><b>Threading.</b> Every method is called on the main thread; the decoder writes into the
 * texture on its own thread and never touches this view.</p>
 */
public final class ViewerPanelView extends FrameLayout {

    // Corner radius as a fraction of the shorter side, matching the legacy bitmap viewer.
    private static final float CORNER_SIZE_RATIO = 1 / 16f;

    private final TextureView textureView;

    private final ImageView snapshotView;

    private final TextView hudView;

    private int contentWidth;

    private int contentHeight;

    private int rotationDegrees;

    // Content box requested by the owner; zero means "the whole view".
    private int displayWidth;

    private int displayHeight;

    // Corner radius of the round-rect outline; 0 disables outline clipping.
    private float cornerSize;

    // Whether the rounded-corner preference is on; the radius is recomputed when the view is laid
    // out, because it depends on the view size.
    private boolean rounded;

    private ConfigurationListener configurationListener;

    /**
     * Receiver of display configuration changes of the host window.
     */
    public interface ConfigurationListener {

        /**
         * Called after the panel applied the new configuration; the host re-fits its window.
         */
        void onViewConfigurationChanged();
    }

    /**
     * Creates an empty panel: no geometry, a hidden snapshot and a hidden overlay.
     *
     * @param context view context; required
     */
    public ViewerPanelView(Context context) {
        this(context, null);
    }

    /**
     * Creates an empty panel from a layout file.
     *
     * @param context view context; required
     * @param attrs   attributes from the layout, or null when built in code
     */
    public ViewerPanelView(Context context, android.util.AttributeSet attrs) {
        super(context, attrs);
        textureView = new TextureView(context) {
            @Override
            protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
                super.onSizeChanged(width, height, oldWidth, oldHeight);
                updateContentTransform();
            }
        };
        textureView.setOpaque(false);
        textureView.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerSize);
            }
        });

        snapshotView = new ImageView(context);
        // The still frame is drawn with an explicit matrix so it lands in exactly the box the live
        // picture uses, letterboxing included.
        snapshotView.setScaleType(ImageView.ScaleType.MATRIX);
        snapshotView.setVisibility(View.GONE);

        hudView = new TextView(context);
        hudView.setTextColor(Color.WHITE);
        hudView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        hudView.setTypeface(Typeface.MONOSPACE);
        hudView.setBackground(new ColorDrawable(0x99000000));
        hudView.setPadding(12, 8, 12, 8);
        hudView.setLineSpacing(0f, 1.1f);
        // The overlay never consumes input: without a click listener and with clickable false, a
        // touch falls through to the texture view underneath.
        hudView.setClickable(false);
        hudView.setFocusable(false);
        hudView.setVisibility(View.GONE);

        addView(textureView, new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(snapshotView, new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(hudView, new LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.START));
    }

    /**
     * Returns the texture view the decoder renders into; never null.
     */
    public TextureView getTextureView() {
        return textureView;
    }

    /**
     * Returns the snapshot layer, so a host can animate it during a switch; never null.
     */
    public ImageView getSnapshotView() {
        return snapshotView;
    }

    /**
     * Stores the visible stream geometry and re-applies the content transform.
     *
     * <p>An unchanged geometry is ignored: a descriptor that only advances the configuration epoch
     * must not touch the view. The owner is expected to re-fit its own box afterwards when the
     * geometry change also changes the window or display size.</p>
     *
     * @param visibleWidth  visible stream width in pixels
     * @param visibleHeight visible stream height in pixels
     * @param rotationDegrees rotation applied to the content
     */
    public void setContentGeometry(int visibleWidth, int visibleHeight, int rotationDegrees) {
        if (contentWidth == visibleWidth && contentHeight == visibleHeight
            && this.rotationDegrees == rotationDegrees) {
            return;
        }
        contentWidth = visibleWidth;
        contentHeight = visibleHeight;
        this.rotationDegrees = rotationDegrees;
        updateContentTransform();
    }

    /**
     * Returns the visible stream width in pixels, or zero before the first descriptor.
     */
    public int getContentWidth() {
        return contentWidth;
    }

    /**
     * Returns the visible stream height in pixels, or zero before the first descriptor.
     */
    public int getContentHeight() {
        return contentHeight;
    }

    /**
     * Returns the rotation applied to the content in degrees.
     */
    public int getRotationDegrees() {
        return rotationDegrees;
    }

    /**
     * Sets the box the rotated picture fills, centered in the view.
     *
     * <p>A non-positive width or height restores the default of filling the whole view, which is
     * what the floating window wants because its window already has the content aspect ratio.</p>
     *
     * @param width  box width in pixels, or zero for the view width
     * @param height box height in pixels, or zero for the view height
     */
    public void setDisplaySize(int width, int height) {
        if (displayWidth == width && displayHeight == height) {
            return;
        }
        displayWidth = width;
        displayHeight = height;
        updateContentTransform();
    }

    /**
     * Applies the rounded-corner preference to the video surface.
     *
     * <p>The radius is a fixed fraction of the shorter side, capped by the theme's large corner
     * size, as the legacy bitmap viewer did. Rounding is disabled when the preference is off; the
     * radius is recomputed whenever the view is laid out, so a preference applied before the first
     * layout still takes effect.</p>
     *
     * @param rounded whether the picture is clipped to a round rectangle
     */
    public void setRounded(boolean rounded) {
        this.rounded = rounded;
        applyCornerSize();
    }

    /**
     * Recomputes the corner radius from the shorter side that is actually laid out.
     */
    private void applyCornerSize() {
        if (!rounded || getWidth() <= 0 || getHeight() <= 0) {
            cornerSize = 0f;
            textureView.setClipToOutline(false);
            textureView.invalidateOutline();
            return;
        }
        final float maxCornerSize = getResources().getDimension(
            com.obby.android.localscreenshare.support.ResourceUtils.resolveAttribute(getContext(),
                com.google.android.material.R.attr.shapeCornerSizeLarge));
        cornerSize = Math.min(
            Math.min(getWidth(), getHeight()) * CORNER_SIZE_RATIO, maxCornerSize);
        textureView.setClipToOutline(cornerSize > 0f);
        textureView.invalidateOutline();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        applyCornerSize();
        updateContentTransform();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyCornerSize();
        updateContentTransform();
        if (configurationListener != null) {
            configurationListener.onViewConfigurationChanged();
        }
    }

    /**
     * Registers the host that has to re-fit its window after a display configuration change.
     *
     * @param listener receiver of configuration changes, or null to clear it
     */
    public void setConfigurationListener(ConfigurationListener listener) {
        configurationListener = listener;
    }

    /**
     * Replaces the debug overlay text.
     *
     * <p>An empty list hides the overlay instead of leaving stale numbers on screen, which is what a
     * disabled overlay or a disabled metric scope looks like.</p>
     *
     * @param lines rendered lines, or an empty list to hide the overlay
     */
    public void setHudLines(List<String> lines) {
        if (lines.isEmpty()) {
            hudView.setText(null);
            hudView.setVisibility(View.GONE);
            return;
        }
        hudView.setText(TextUtils.join("\n", lines));
        hudView.setVisibility(View.VISIBLE);
    }

    /**
     * Captures the currently displayed frame, or returns null when the texture cannot be read.
     *
     * <p>Used once per host switch: the frame covers the incoming host until its first decoded
     * frame, so the picture never drops to black. The bitmap is a full copy and belongs to the
     * caller.</p>
     */
    public Bitmap captureSnapshot() {
        if (!textureView.isAvailable()) {
            return null;
        }
        try {
            return textureView.getBitmap();
        } catch (RuntimeException e) {
            // A texture that is being torn down can refuse the read; the switch then shows the
            // placeholder instead of failing.
            return null;
        }
    }

    /**
     * Shows {@code snapshot} in the content box, above the live picture.
     *
     * @param snapshot frame to show; null hides the layer
     */
    public void showSnapshot(Bitmap snapshot) {
        if (snapshot == null) {
            hideSnapshot();
            return;
        }
        // A show can replace a running transition animation (a fast back-and-forth keeps the old
        // end action alive), so the previous animation is stopped before the transform is reset.
        snapshotView.animate().cancel();
        snapshotView.setImageBitmap(snapshot);
        snapshotView.setAlpha(1f);
        snapshotView.setScaleX(1f);
        snapshotView.setScaleY(1f);
        snapshotView.setTranslationX(0f);
        snapshotView.setTranslationY(0f);
        snapshotView.setVisibility(View.VISIBLE);
        updateContentTransform();
    }

    /**
     * Hides the snapshot layer immediately.
     */
    public void hideSnapshot() {
        snapshotView.animate().cancel();
        snapshotView.setVisibility(View.GONE);
        snapshotView.setImageBitmap(null);
        snapshotView.setAlpha(1f);
    }

    /**
     * Cross-fades the snapshot out and hides it when the animation ends.
     *
     * @param durationMs fade duration in milliseconds; zero hides immediately
     */
    public void fadeOutSnapshot(long durationMs) {
        if (snapshotView.getVisibility() != View.VISIBLE) {
            hideSnapshot();
            return;
        }
        if (durationMs <= 0L) {
            hideSnapshot();
            return;
        }
        snapshotView.animate()
            .alpha(0f)
            .setDuration(durationMs)
            .withEndAction(this::hideSnapshot)
            .start();
    }

    /**
     * Returns whether the snapshot layer is currently visible.
     */
    public boolean isSnapshotVisible() {
        return snapshotView.getVisibility() == View.VISIBLE;
    }

    /**
     * Returns the content box in view coordinates, or an empty rectangle before the first layout.
     *
     * @param out receives the box; required
     */
    public void getContentBox(android.graphics.Rect out) {
        final int width = resolvedDisplayWidth();
        final int height = resolvedDisplayHeight();
        final int left = (getWidth() - width) / 2;
        final int top = (getHeight() - height) / 2;
        out.set(left, top, left + width, top + height);
    }

    private int resolvedDisplayWidth() {
        final int width = displayWidth > 0 ? displayWidth : getWidth();
        return Math.min(width, getWidth());
    }

    private int resolvedDisplayHeight() {
        final int height = displayHeight > 0 ? displayHeight : getHeight();
        return Math.min(height, getHeight());
    }

    /**
     * Applies the content transform to the texture and to the still frame.
     *
     * <p>A {@code TextureView} stretches its texture to the view bounds before the transform is
     * applied, while an {@code ImageView} with an explicit matrix draws its drawable at its intrinsic
     * size. The still frame therefore gets the same transform composed after a drawable-to-view
     * scale, which is what makes it land in exactly the same box as the live picture.</p>
     */
    private void updateContentTransform() {
        final ViewerContentTransform transform = ViewerContentTransform.compute(
            getWidth(), getHeight(), displayWidth, displayHeight, rotationDegrees);
        final Matrix matrix = new Matrix();
        matrix.setScale(transform.getScaleX(), transform.getScaleY(),
            transform.getCenterX(), transform.getCenterY());
        if (transform.getRotationDegrees() != 0) {
            matrix.postRotate(transform.getRotationDegrees(),
                transform.getCenterX(), transform.getCenterY());
        }
        textureView.setTransform(matrix);

        final android.graphics.drawable.Drawable drawable = snapshotView.getDrawable();
        if (drawable == null) {
            return;
        }
        final int drawableWidth = drawable.getIntrinsicWidth();
        final int drawableHeight = drawable.getIntrinsicHeight();
        if (drawableWidth <= 0 || drawableHeight <= 0 || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        final Matrix snapshotMatrix = new Matrix();
        snapshotMatrix.setScale(getWidth() / (float) drawableWidth,
            getHeight() / (float) drawableHeight);
        snapshotMatrix.postConcat(matrix);
        snapshotView.setImageMatrix(snapshotMatrix);
    }
}
