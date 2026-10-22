package com.obby.android.localscreenshare.render;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * Video surface, encoded-frame layer, last-frame snapshot and debug overlay shared by both display
 * hosts.
 *
 * <p>The floating window and the full-screen activity must show exactly the same picture and offer
 * the same debug overlay, so the rendering part lives here once: a {@link TextureView} the decoder
 * renders into directly, an {@link ImageView} that shows decoded JPEG frames, an {@link ImageView}
 * that holds one captured frame during a host switch and a monospaced text overlay. The owner only
 * decides where the content box goes - the window sizes itself to the content, the activity fills
 * the display and letterboxes it - and supplies the stream geometry.</p>
 *
 * <p><b>Two picture paths.</b> H.264 arrives through the texture and never touches the frame layer.
 * JPEG arrives as one complete image per frame, which the bitmap render target decodes and hands to
 * {@link #showFrame(Bitmap)}; the frame layer is hidden until the first frame and is cleared when a
 * JPEG session ends, so the H.264 path looks exactly as it did before the layer existed. The layer
 * sits above the texture and below the snapshot, is never clickable or focusable, and therefore
 * never consumes a touch meant for the texture.</p>
 *
 * <p><b>Content geometry.</b> {@link #setContentGeometry(int, int, int)} stores the visible size and
 * rotation reported by the stream descriptor; {@link #setDisplaySize(int, int)} sets the box the
 * rotated picture should fill, centered in the view. A zero display size means "the whole view",
 * which is the window case. {@link ViewerContentTransform} turns both into the texture transform, so
 * a quarter-turn stream is rotated and a letterboxed picture keeps its aspect ratio. The frame layer
 * and the snapshot share that transform, which is what makes a decoded JPEG frame land in exactly
 * the same box as a texture frame.</p>
 *
 * <p><b>Snapshot.</b> {@link #captureSnapshot()} returns the JPEG frame while the frame layer is
 * showing one, and otherwise reads one frame from the texture; the caller hands it to the incoming
 * host, which shows it through {@link #showSnapshot(Bitmap)} until its first decoded frame arrives
 * and then drops it with {@link #fadeOutSnapshot(long)}. The snapshot layer is UI only: it is not a
 * second decoder output, and it never starts or stops decoding.</p>
 *
 * <p><b>Debug overlay.</b> The overlay is one vertical stack of single-line text views over the
 * picture. Every line gets the same text size, chosen by {@link ViewerHudTextPolicy} from the width
 * the panel actually has: the normal window and the full-screen host keep the legacy size, a window
 * dragged smaller shrinks the text first, and a line that still does not fit is truncated with an
 * ellipsis instead of wrapping onto a second visual line, which used to cover the lines below.</p>
 *
 * <p><b>Threading.</b> Every method is called on the main thread; the decoder writes into the
 * texture on its own thread and never touches this view.</p>
 */
public final class ViewerPanelView extends FrameLayout {

    // Corner radius as a fraction of the shorter side, matching the legacy bitmap viewer.
    private static final float CORNER_SIZE_RATIO = 1 / 16f;

    // Overlay padding in pixels, as the single legacy text view used it.
    private static final int HUD_PADDING_HORIZONTAL_PX = 12;

    private static final int HUD_PADDING_VERTICAL_PX = 8;

    // Baseline-to-baseline spacing of the overlay lines.
    private static final float HUD_LINE_SPACING_MULT = 1.1f;

    // One spare pixel so a line measured by the overlay paint cannot wrap through the difference
    // between that measurement and the line width the layout computes.
    private static final int HUD_WIDTH_SAFETY_MARGIN_PX = 1;

    private final TextureView textureView;

    private final ImageView frameView;

    private final ImageView snapshotView;

    private final LinearLayout hudView;

    // Measures the overlay lines at the maximum size; the size policy scales that measurement.
    private final Paint hudMeasurePaint;

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

        frameView = new ImageView(context);
        // A decoded JPEG frame is drawn with the same explicit matrix as the snapshot, so both land
        // in exactly the box the live picture uses, letterboxing and rotation included.
        frameView.setScaleType(ImageView.ScaleType.MATRIX);
        // The layer must never take a touch or the focus away from the texture view: the window
        // drag, the tap menu and the double-tap menu all listen on the texture view underneath.
        frameView.setClickable(false);
        frameView.setFocusable(false);
        frameView.setVisibility(View.GONE);
        frameView.setOutlineProvider(new ViewOutlineProvider() {
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

        hudView = new LinearLayout(context);
        hudView.setOrientation(LinearLayout.VERTICAL);
        hudView.setBackground(new ColorDrawable(0x99000000));
        hudView.setPadding(HUD_PADDING_HORIZONTAL_PX, HUD_PADDING_VERTICAL_PX,
            HUD_PADDING_HORIZONTAL_PX, HUD_PADDING_VERTICAL_PX);
        // The overlay never consumes input: without a click listener and with clickable false, a
        // touch falls through to the texture view underneath.
        hudView.setClickable(false);
        hudView.setFocusable(false);
        hudView.setVisibility(View.GONE);

        hudMeasurePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        hudMeasurePaint.setTypeface(Typeface.MONOSPACE);

        addView(textureView, new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Above the texture, below the snapshot: a decoded JPEG frame covers the (empty) texture,
        // while the switch snapshot still covers both during a host change.
        addView(frameView, new LayoutParams(
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
        } else {
            final float maxCornerSize = getResources().getDimension(
                com.obby.android.localscreenshare.support.ResourceUtils.resolveAttribute(getContext(),
                    com.google.android.material.R.attr.shapeCornerSizeLarge));
            cornerSize = Math.min(
                Math.min(getWidth(), getHeight()) * CORNER_SIZE_RATIO, maxCornerSize);
        }
        applyCornerClip(textureView);
        applyCornerClip(frameView);
    }

    /**
     * Applies the current radius to {@code view}, so every picture layer is cut to the same shape.
     *
     * <p>The frame layer draws over the texture, so it needs the same clip; otherwise a rounded
     * window would show square corners as soon as a JPEG frame arrives.</p>
     */
    private void applyCornerClip(View view) {
        view.setClipToOutline(cornerSize > 0f);
        view.invalidateOutline();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        applyCornerSize();
        applyHudTextSize();
        updateContentTransform();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyCornerSize();
        applyHudTextSize();
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
     * <p>Each line gets its own single-line text view, so a line that does not fit the window is
     * truncated with an end ellipsis instead of wrapping onto a second visual line and covering the
     * lines below it. All lines share one text size, recomputed from the width the panel actually
     * has: the normal window and the full-screen host keep the legacy size, a window dragged smaller
     * shrinks the overlay down to {@link ViewerHudTextPolicy#MIN_TEXT_SIZE_SP} before anything is
     * truncated.</p>
     *
     * <p>An empty list hides the overlay instead of leaving stale numbers on screen, which is what a
     * disabled overlay or a disabled metric scope looks like.</p>
     *
     * @param lines rendered lines, or an empty list to hide the overlay
     */
    public void setHudLines(List<String> lines) {
        if (lines.isEmpty()) {
            hudView.removeAllViews();
            hudView.setVisibility(View.GONE);
            return;
        }
        // The metric lines are stable per session, so the views are rebuilt only when the line count
        // changes; the once-per-second refresh then only swaps the text.
        if (hudView.getChildCount() != lines.size()) {
            hudView.removeAllViews();
            for (int i = 0; i < lines.size(); i++) {
                hudView.addView(createHudLineView(), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }
        for (int i = 0; i < lines.size(); i++) {
            ((TextView) hudView.getChildAt(i)).setText(lines.get(i));
        }
        hudView.setVisibility(View.VISIBLE);
        applyHudTextSize();
    }

    /**
     * Creates one overlay line: monospaced, white, single line and truncated at the end.
     */
    private TextView createHudLineView() {
        final TextView view = new TextView(getContext());
        view.setTextColor(Color.WHITE);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, ViewerHudTextPolicy.MAX_TEXT_SIZE_SP);
        view.setLineSpacing(0f, HUD_LINE_SPACING_MULT);
        // Single line plus an end ellipsis is what guarantees the overlay never wraps; the size
        // policy makes the ellipsis unnecessary at the normal window size.
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setClickable(false);
        view.setFocusable(false);
        return view;
    }

    /**
     * Applies one text size to every overlay line, chosen from the width the panel has.
     *
     * <p>The widest line is measured with the overlay paint at the maximum size, which is the input
     * {@link ViewerHudTextPolicy} scales down; measuring with the same monospace typeface keeps the
     * decision equal to the size the layout will use. Before the first layout the panel has no
     * width, and the policy then keeps the maximum size.</p>
     */
    private void applyHudTextSize() {
        final int lineCount = hudView.getChildCount();
        if (lineCount == 0) {
            return;
        }
        final int maxTextSizePx = hudTextSizePx(ViewerHudTextPolicy.MAX_TEXT_SIZE_SP);
        final int minTextSizePx = Math.min(maxTextSizePx,
            hudTextSizePx(ViewerHudTextPolicy.MIN_TEXT_SIZE_SP));
        hudMeasurePaint.setTextSize(maxTextSizePx);
        int widestLineWidthPx = 0;
        for (int i = 0; i < lineCount; i++) {
            final CharSequence text = ((TextView) hudView.getChildAt(i)).getText();
            widestLineWidthPx = Math.max(widestLineWidthPx,
                (int) Math.ceil(hudMeasurePaint.measureText(text, 0, text.length())));
        }
        final int availableWidthPx = getWidth() - hudView.getPaddingLeft()
            - hudView.getPaddingRight() - HUD_WIDTH_SAFETY_MARGIN_PX;
        final int textSizePx = ViewerHudTextPolicy.fitTextSizePx(availableWidthPx, maxTextSizePx,
            minTextSizePx, widestLineWidthPx);
        for (int i = 0; i < lineCount; i++) {
            ((TextView) hudView.getChildAt(i)).setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx);
        }
    }

    /**
     * Converts {@code sizeSp} to whole pixels with the current font scale.
     */
    private int hudTextSizePx(float sizeSp) {
        final float sizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp,
            getResources().getDisplayMetrics());
        return Math.max(1, Math.round(sizePx));
    }

    /**
     * Captures the currently displayed frame, or returns null when nothing can be read.
     *
     * <p>Used once per host switch: the frame covers the incoming host until its first decoded
     * frame, so the picture never drops to black. A JPEG session has no texture content, so the
     * frame layer is the picture and its bitmap is returned; the caller must not recycle it, because
     * the layer keeps showing it. An H.264 session has an empty frame layer and gets a full copy
     * read from the texture instead.</p>
     */
    @Nullable
    public Bitmap captureSnapshot() {
        final Bitmap frame = currentFrame();
        if (frame != null) {
            return frame;
        }
        if (!textureView.isAvailable()) {
            return null;
        }
        try {
            return textureView.getBitmap();
        } catch (RuntimeException e) {
            // A texture that is being torn down can refuse the read; the switch then simply has no
            // still frame to show and the incoming host waits for its first decoded frame.
            return null;
        }
    }

    /**
     * Shows {@code frame} in the content box, in place of the (empty) texture.
     *
     * <p>Called on the main thread by the bitmap half of the host's render target once per decoded
     * JPEG frame. The layer is created hidden and stays hidden for an H.264 session, so this is the
     * only path that makes it visible.</p>
     *
     * @param frame decoded frame to show; null clears the layer
     */
    public void showFrame(@Nullable Bitmap frame) {
        if (frame == null) {
            clearFrame();
            return;
        }
        frameView.setImageBitmap(frame);
        frameView.setVisibility(View.VISIBLE);
        applyDrawableMatrix(frameView, contentMatrix());
    }

    /**
     * Hides the frame layer and drops its reference to the bitmap.
     *
     * <p>The bitmap is not recycled: it may be the still frame another host is showing, and the
     * garbage collector reclaims it once neither view references it.</p>
     */
    public void clearFrame() {
        frameView.setVisibility(View.GONE);
        frameView.setImageBitmap(null);
    }

    /**
     * Returns the bitmap the frame layer is showing, or null when it is hidden or empty.
     */
    @Nullable
    private Bitmap currentFrame() {
        if (frameView.getVisibility() != View.VISIBLE) {
            return null;
        }
        final Drawable drawable = frameView.getDrawable();
        if (!(drawable instanceof BitmapDrawable)) {
            return null;
        }
        final Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
        return bitmap == null || bitmap.isRecycled() ? null : bitmap;
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
     * Applies the content transform to the texture, the frame layer and the still frame.
     *
     * <p>A {@code TextureView} stretches its texture to the view bounds before the transform is
     * applied, while an {@code ImageView} with an explicit matrix draws its drawable at its intrinsic
     * size. Both image layers therefore get the same transform composed after a drawable-to-view
     * scale, which is what makes a decoded JPEG frame and a switch snapshot land in exactly the same
     * box as the live picture.</p>
     */
    private void updateContentTransform() {
        final Matrix matrix = contentMatrix();
        textureView.setTransform(matrix);
        applyDrawableMatrix(frameView, matrix);
        applyDrawableMatrix(snapshotView, matrix);
    }

    /**
     * Returns the transform the texture applies, which every other picture layer reuses.
     */
    @NonNull
    private Matrix contentMatrix() {
        final ViewerContentTransform transform = ViewerContentTransform.compute(
            getWidth(), getHeight(), displayWidth, displayHeight, rotationDegrees);
        final Matrix matrix = new Matrix();
        matrix.setScale(transform.getScaleX(), transform.getScaleY(),
            transform.getCenterX(), transform.getCenterY());
        if (transform.getRotationDegrees() != 0) {
            matrix.postRotate(transform.getRotationDegrees(),
                transform.getCenterX(), transform.getCenterY());
        }
        return matrix;
    }

    /**
     * Places {@code view}'s drawable in the content box by composing the drawable-to-view scale with
     * {@code contentTransform}; a view without a drawable or before the first layout is left alone.
     */
    private void applyDrawableMatrix(@NonNull ImageView view, @NonNull Matrix contentTransform) {
        final Drawable drawable = view.getDrawable();
        if (drawable == null) {
            return;
        }
        final int drawableWidth = drawable.getIntrinsicWidth();
        final int drawableHeight = drawable.getIntrinsicHeight();
        if (drawableWidth <= 0 || drawableHeight <= 0 || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        final Matrix matrix = new Matrix();
        matrix.setScale(getWidth() / (float) drawableWidth,
            getHeight() / (float) drawableHeight);
        matrix.postConcat(contentTransform);
        view.setImageMatrix(matrix);
    }
}
