package com.obby.android.localscreenshare.render;

/**
 * Pure geometry for the transform that maps decoded content into the box that displays it.
 *
 * <p>The decoder writes an unrotated frame into a {@code TextureView}, which stretches the texture
 * to the view bounds. Two adjustments turn that stretch into a correct picture: the content has to
 * keep its aspect ratio inside a box that may be smaller than the view (letterboxing), and a
 * quarter-turn stream has to be rotated, with its axes swapped before the rotation. The floating
 * window sizes itself to the content, so its box equals the view; the full-screen activity fills
 * the display and letterboxes the content inside it.</p>
 *
 * <p><b>Rules.</b> {@code displayWidth}/{@code displayHeight} is the size of the rotated content
 * box. For {@code 90} and {@code 270} degrees the box describes the picture after rotation, so the
 * scale applied before the rotation swaps the two factors; this is exactly what the window did when
 * box and view were the same size. A non-positive view size or a non-positive box yields
 * {@link #IDENTITY}, which is what an unattached view looks like. A rotation other than
 * {@code 90}/{@code 180}/{@code 270} is ignored, matching the descriptor validation upstream.</p>
 *
 * <p><b>Purity.</b> The computation touches no Android type and keeps no state, so every rule is
 * unit-testable on the JVM. The caller applies the result with
 * {@code Matrix.setScale(scaleX, scaleY, centerX, centerY)} followed by
 * {@code postRotate(rotationDegrees, centerX, centerY)} when the rotation is not zero.</p>
 */
public final class ViewerContentTransform {

    /**
     * Transform applied when there is nothing to map: unit scale, no rotation.
     */
    public static final ViewerContentTransform IDENTITY =
        new ViewerContentTransform(1f, 1f, 0, 0f, 0f);

    private final float scaleX;

    private final float scaleY;

    private final int rotationDegrees;

    private final float centerX;

    private final float centerY;

    private ViewerContentTransform(float scaleX, float scaleY, int rotationDegrees,
                                   float centerX, float centerY) {
        this.scaleX = scaleX;
        this.scaleY = scaleY;
        this.rotationDegrees = rotationDegrees;
        this.centerX = centerX;
        this.centerY = centerY;
    }

    /**
     * Computes the transform for one view size and content box.
     *
     * @param viewWidth       width of the rendering view in pixels
     * @param viewHeight      height of the rendering view in pixels
     * @param displayWidth    width of the content box in pixels; zero or less means "the view"
     * @param displayHeight   height of the content box in pixels; zero or less means "the view"
     * @param rotationDegrees rotation applied to the content; {@code 90} and {@code 270} swap the
     *                        axes, any other value leaves them unchanged
     * @return an immutable result; never null
     */
    public static ViewerContentTransform compute(int viewWidth, int viewHeight, int displayWidth,
                                                 int displayHeight, int rotationDegrees) {
        if (viewWidth <= 0 || viewHeight <= 0) {
            return IDENTITY;
        }
        final int boxWidth = displayWidth > 0 ? displayWidth : viewWidth;
        final int boxHeight = displayHeight > 0 ? displayHeight : viewHeight;
        final boolean swapAxes = rotationDegrees == 90 || rotationDegrees == 270;
        // The box describes the picture after rotation, so the factors that map the unrotated
        // texture into it are swapped for a quarter turn.
        final float scaleX = (swapAxes ? boxHeight : boxWidth) / (float) viewWidth;
        final float scaleY = (swapAxes ? boxWidth : boxHeight) / (float) viewHeight;
        final int rotation = rotationDegrees == 90 || rotationDegrees == 180
            || rotationDegrees == 270 ? rotationDegrees : 0;
        return new ViewerContentTransform(scaleX, scaleY, rotation,
            viewWidth / 2f, viewHeight / 2f);
    }

    /**
     * Returns the horizontal scale factor, applied about the view center.
     */
    public float getScaleX() {
        return scaleX;
    }

    /**
     * Returns the vertical scale factor, applied about the view center.
     */
    public float getScaleY() {
        return scaleY;
    }

    /**
     * Returns the rotation in degrees: {@code 0}, {@code 90}, {@code 180} or {@code 270}.
     */
    public int getRotationDegrees() {
        return rotationDegrees;
    }

    /**
     * Returns the horizontal center of the transformation, in view pixels.
     */
    public float getCenterX() {
        return centerX;
    }

    /**
     * Returns the vertical center of the transformation, in view pixels.
     */
    public float getCenterY() {
        return centerY;
    }
}
