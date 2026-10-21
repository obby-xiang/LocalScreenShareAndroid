package com.obby.android.localscreenshare.render;

/**
 * Pure geometry for the last-frame snapshot animation between the two display hosts.
 *
 * <p>The floating window and the full-screen activity are separate windows, so the platform offers
 * no shared element between them. The visual continuity comes from one captured frame that is drawn
 * on top of the incoming host and animated from the outgoing host's rectangle to the incoming one.
 * Because the snapshot view is already laid out at the destination rectangle, the animation only
 * needs a start scale and a start translation: the frame appears exactly where the previous host
 * was, grows (or shrinks) into place, and is then cross-faded with the live picture.</p>
 *
 * <p><b>Purity.</b> Rectangles and factors are plain integers and floats, and the class keeps no
 * state, so the mapping is unit-testable on the JVM. Both rectangles must be expressed in the same
 * coordinate space; the caller converts the other host's screen rectangle into its own view
 * coordinates first.</p>
 */
public final class ViewerTransitionGeometry {

    private ViewerTransitionGeometry() {
    }

    /**
     * Computes the start of the snapshot animation.
     *
     * <p>The snapshot view is assumed to be laid out exactly at the target rectangle. Scaling it
     * down to the source size and moving it to the source center makes it cover the outgoing host;
     * animating both properties to {@code 1} and {@code 0} lands it on the live picture.</p>
     *
     * @param sourceLeft   left edge of the outgoing host's rectangle
     * @param sourceTop    top edge of the outgoing host's rectangle
     * @param sourceWidth  width of the outgoing host's rectangle in pixels
     * @param sourceHeight height of the outgoing host's rectangle in pixels
     * @param targetLeft   left edge of the incoming host's content box
     * @param targetTop    top edge of the incoming host's content box
     * @param targetWidth  width of the incoming host's content box in pixels
     * @param targetHeight height of the incoming host's content box in pixels
     * @return an immutable start state; never null
     */
    public static Start compute(int sourceLeft, int sourceTop, int sourceWidth, int sourceHeight,
                                int targetLeft, int targetTop, int targetWidth,
                                int targetHeight) {
        if (targetWidth <= 0 || targetHeight <= 0 || sourceWidth <= 0 || sourceHeight <= 0) {
            // A degenerate rectangle cannot be mapped; the caller shows the frame in place instead
            // of animating from a size that does not exist.
            return Start.NONE;
        }
        final float scaleX = sourceWidth / (float) targetWidth;
        final float scaleY = sourceHeight / (float) targetHeight;
        final float sourceCenterX = sourceLeft + sourceWidth / 2f;
        final float sourceCenterY = sourceTop + sourceHeight / 2f;
        final float targetCenterX = targetLeft + targetWidth / 2f;
        final float targetCenterY = targetTop + targetHeight / 2f;
        return new Start(scaleX, scaleY, sourceCenterX - targetCenterX,
            sourceCenterY - targetCenterY);
    }

    /**
     * Start state of one snapshot animation: the scale and translation applied to a view that is
     * already laid out at the destination rectangle.
     */
    public static final class Start {

        /**
         * State that leaves the snapshot exactly where it is laid out.
         */
        public static final Start NONE = new Start(1f, 1f, 0f, 0f);

        private final float scaleX;

        private final float scaleY;

        private final float translationX;

        private final float translationY;

        private Start(float scaleX, float scaleY, float translationX, float translationY) {
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.translationX = translationX;
            this.translationY = translationY;
        }

        /**
         * Returns the horizontal scale to apply before the animation.
         */
        public float getScaleX() {
            return scaleX;
        }

        /**
         * Returns the vertical scale to apply before the animation.
         */
        public float getScaleY() {
            return scaleY;
        }

        /**
         * Returns the horizontal offset from the destination center, in pixels.
         */
        public float getTranslationX() {
            return translationX;
        }

        /**
         * Returns the vertical offset from the destination center, in pixels.
         */
        public float getTranslationY() {
            return translationY;
        }

        /**
         * Returns whether this state actually differs from the destination rectangle.
         */
        public boolean isVisible() {
            return scaleX != 1f || scaleY != 1f || translationX != 0f || translationY != 0f;
        }
    }
}
