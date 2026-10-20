package com.obby.android.localscreenshare.render;

/**
 * Pure geometry policy that maps stream geometry onto the floating viewer window size.
 *
 * <p>The window is sized from the region that should be displayed, not from the viewer's own
 * screen: the visible size of the stream, the display rotation, the usable display bounds, the
 * user's viewing percentage, the minimum window size and the {@link ZoomMode}. Because the window
 * keeps the content aspect ratio, the {@link android.view.TextureView} that renders the decoder
 * output is never stretched, which is what the legacy sizing (bounds times percentage) got
 * wrong.</p>
 *
 * <p><b>Rules.</b> Rotation swaps the content axes for {@code 90} and {@code 270} degrees and is
 * ignored for any other value, including the {@code 0}/{@code 180} pair; callers are expected to
 * pass a value already validated by {@code StreamDescriptor}. The viewing percentage is a
 * percentage of the usable display area, clamped
 * to {@code 1..100}. The result is never smaller than the minimum size on either axis: when the
 * window would be smaller, both axes are scaled up together, so the aspect ratio survives. The
 * minimum size wins over the bounds when both cannot hold; the legacy viewer behaved the same
 * way. Before the first descriptor arrives there is no content geometry, and the bounds sized
 * window of the legacy implementation is returned with {@link Fit#isFallback()} set.</p>
 *
 * <p><b>Purity.</b> The policy reads no configuration, keeps no state and touches no Android
 * type, so the same input always produces the same result and every rule is unit-testable on the
 * JVM.</p>
 */
public final class ViewerFitPolicy {

    /**
     * Scales used by {@link ZoomMode#INTEGER}, from the largest window to the smallest.
     */
    private static final float[] INTEGER_SCALES = {1f, 0.5f, 1f / 3f, 0.25f};

    /**
     * Tolerance used when comparing a requested percentage with a discrete scale.
     */
    private static final float SCALE_EPSILON = 1e-4f;

    private static final int MIN_SCALE_PERCENT = 1;

    private static final int MAX_SCALE_PERCENT = 100;

    private ViewerFitPolicy() {
    }

    /**
     * Computes the window size for the given stream geometry and viewing preferences.
     *
     * @param contentWidth    visible stream width in pixels; non-positive means "unknown yet"
     * @param contentHeight   visible stream height in pixels; non-positive means "unknown yet"
     * @param rotationDegrees display rotation applied by the viewer; {@code 90} and {@code 270}
     *                        swap the axes, any other value leaves them unchanged
     * @param boundsWidth     width of the usable display area in pixels
     * @param boundsHeight    height of the usable display area in pixels
     * @param scalePercent    viewing percentage, clamped to {@code 1..100}
     * @param minSizePx       minimum window size on either axis in pixels; non-positive disables it
     * @param mode            scaling strategy; must not be null
     * @return an immutable result; never null
     * @throws IllegalArgumentException if {@code mode} is null
     */
    public static Fit fit(int contentWidth, int contentHeight, int rotationDegrees,
                          int boundsWidth, int boundsHeight, int scalePercent, int minSizePx,
                          ZoomMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }

        final int percent = Math.max(MIN_SCALE_PERCENT, Math.min(MAX_SCALE_PERCENT, scalePercent));
        final int minSize = Math.max(0, minSizePx);

        if (contentWidth <= 0 || contentHeight <= 0 || boundsWidth <= 0 || boundsHeight <= 0) {
            return fallback(boundsWidth, boundsHeight, percent, minSize);
        }

        final boolean swapAxes = rotationDegrees == 90 || rotationDegrees == 270;
        final int contentW = swapAxes ? contentHeight : contentWidth;
        final int contentH = swapAxes ? contentWidth : contentHeight;

        switch (mode) {
            case ONE_TO_ONE:
                return oneToOne(contentW, contentH, boundsWidth, boundsHeight, minSize);
            case INTEGER:
                return integer(contentW, contentH, boundsWidth, boundsHeight, percent, minSize);
            case FIT:
            default:
                return fitToBounds(contentW, contentH, boundsWidth, boundsHeight, percent, minSize);
        }
    }

    /**
     * Sizes the window to the percentage of the bounds, keeping the aspect ratio.
     */
    private static Fit fitToBounds(int contentW, int contentH, int boundsWidth, int boundsHeight,
                                   int percent, int minSize) {
        final double requested = percent / 100d;
        final double scale = Math.min(boundsWidth * requested / contentW,
                boundsHeight * requested / contentH);
        return sized(contentW, contentH, scale, minSize, false, false, false);
    }

    /**
     * Sizes the window to the content pixels, reduced uniformly when it does not fit.
     */
    private static Fit oneToOne(int contentW, int contentH, int boundsWidth, int boundsHeight,
                                int minSize) {
        boolean clamped = false;
        double scale = 1d;
        if (contentW > boundsWidth || contentH > boundsHeight) {
            scale = Math.min((double) boundsWidth / contentW, (double) boundsHeight / contentH);
            clamped = true;
        }
        return sized(contentW, contentH, scale, minSize, false, clamped, false);
    }

    /**
     * Snaps the percentage to the nearest whole-number reduction, then clamps it to the bounds.
     */
    private static Fit integer(int contentW, int contentH, int boundsWidth, int boundsHeight,
                               int percent, int minSize) {
        final float requested = percent / 100f;
        float tier = INTEGER_SCALES[INTEGER_SCALES.length - 1];
        float bestDistance = Float.MAX_VALUE;
        for (float candidate : INTEGER_SCALES) {
            final float distance = Math.abs(requested - candidate);
            // Tiers are visited from largest to smallest, so a strict comparison keeps the larger
            // candidate when two tiers are equally close.
            if (distance < bestDistance) {
                bestDistance = distance;
                tier = candidate;
            }
        }

        double scale = tier;
        boolean clamped = false;
        if (contentW * scale > boundsWidth || contentH * scale > boundsHeight) {
            scale = Math.min((double) boundsWidth / contentW, (double) boundsHeight / contentH);
            clamped = true;
        }
        final boolean snapped = Math.abs(tier - requested) > SCALE_EPSILON;
        return sized(contentW, contentH, scale, minSize, snapped, clamped, false);
    }

    /**
     * Applies the minimum size and rounds the window dimensions.
     *
     * <p>The minimum size is applied to the scale factor, not to a single axis, so the aspect
     * ratio is preserved; the final {@code max} only compensates rounding at the very edge.</p>
     */
    private static Fit sized(int contentW, int contentH, double scale, int minSize,
                             boolean snapped, boolean clamped, boolean fallback) {
        double effectiveScale = scale;
        if (minSize > 0) {
            final double minScale = Math.max((double) minSize / contentW,
                    (double) minSize / contentH);
            effectiveScale = Math.max(effectiveScale, minScale);
        }

        int width = (int) Math.round(contentW * effectiveScale);
        int height = (int) Math.round(contentH * effectiveScale);
        if (minSize > 0) {
            width = Math.max(width, minSize);
            height = Math.max(height, minSize);
        }
        return new Fit(width, height, (float) effectiveScale, snapped, clamped, fallback);
    }

    /**
     * Returns the legacy "bounds times percentage" size used before the content geometry is known.
     */
    private static Fit fallback(int boundsWidth, int boundsHeight, int percent, int minSize) {
        int width = (int) Math.round(Math.max(0, boundsWidth) * percent / 100d);
        int height = (int) Math.round(Math.max(0, boundsHeight) * percent / 100d);
        width = Math.max(width, minSize);
        height = Math.max(height, minSize);
        return new Fit(width, height, percent / 100f, false, false, true);
    }

    /**
     * Immutable result of one {@link #fit} computation.
     *
     * <p>{@code width} and {@code height} are the window pixel size; {@code scale} is the factor
     * applied to the rotated content. {@code snapped} reports that the mode replaced the viewing
     * percentage with a discrete scale, {@code clamped} reports that the bounds reduced the
     * target size, and {@code fallback} reports that the content geometry was unusable and the
     * legacy bounds sized window was returned.</p>
     */
    public static final class Fit {

        private final int width;
        private final int height;
        private final float scale;
        private final boolean snapped;
        private final boolean clamped;
        private final boolean fallback;

        private Fit(int width, int height, float scale, boolean snapped, boolean clamped,
                    boolean fallback) {
            this.width = width;
            this.height = height;
            this.scale = scale;
            this.snapped = snapped;
            this.clamped = clamped;
            this.fallback = fallback;
        }

        /**
         * Returns the window width in pixels.
         */
        public int getWidth() {
            return width;
        }

        /**
         * Returns the window height in pixels.
         */
        public int getHeight() {
            return height;
        }

        /**
         * Returns the scale applied to the rotated content, where {@code 1} means one window
         * pixel per stream pixel.
         */
        public float getScale() {
            return scale;
        }

        /**
         * Returns whether the mode replaced the viewing percentage with a discrete scale.
         */
        public boolean isSnapped() {
            return snapped;
        }

        /**
         * Returns whether the usable display area reduced the target size.
         */
        public boolean isClamped() {
            return clamped;
        }

        /**
         * Returns whether the result is the legacy bounds sized window, used while the stream
         * geometry is still unknown.
         */
        public boolean isFallback() {
            return fallback;
        }
    }
}
