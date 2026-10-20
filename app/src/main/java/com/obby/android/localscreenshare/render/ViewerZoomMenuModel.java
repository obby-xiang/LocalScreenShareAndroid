package com.obby.android.localscreenshare.render;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure projection of the viewer's zoom menu rows for one stream geometry.
 *
 * <p>The menu used to be a fixed list of three labels, which hid two facts from the user: the
 * effective scale of a row, and that the integer preset can land on exactly the same window size as
 * the actual-size row. On the default viewing percentage of eighty percent the integer preset snaps
 * to {@code 1}, so both rows produced the same 1050x2274 window and choosing between them changed
 * nothing visible. Each row of this model therefore carries the scale it would apply, and the
 * integer row is left out when it would render exactly the same window as the actual-size row.</p>
 *
 * <p><b>Purity.</b> The model reads no configuration, keeps no state and touches no Android type,
 * so the same input always produces the same rows and every rule is unit-testable on the JVM. The
 * row order is fixed: fit, actual size, integer preset.</p>
 */
public final class ViewerZoomMenuModel {

    /**
     * Percent shown for a row whose effective scale rounds to zero, which only happens for a
     * degenerate geometry that the policy already refuses.
     */
    private static final int MIN_REPORTED_PERCENT = 1;

    private ViewerZoomMenuModel() {
    }

    /**
     * Builds the zoom menu rows for the given stream geometry and viewing preferences.
     *
     * <p>The actual-size row is clamped exactly like the window: a stream larger than the usable
     * display area is reduced to fit, and the row then reports the reduced scale so the label can
     * say that the size was scaled down. The integer row is omitted when it would produce the same
     * window size as the actual-size row, and the actual-size row is then marked as selected for a
     * stored integer preset, because the two are the same size.</p>
     *
     * @param contentWidth    visible stream width in pixels; non-positive means "unknown yet"
     * @param contentHeight   visible stream height in pixels; non-positive means "unknown yet"
     * @param rotationDegrees display rotation applied by the viewer; {@code 90} and {@code 270}
     *                        swap the axes
     * @param boundsWidth     width of the usable display area in pixels
     * @param boundsHeight    height of the usable display area in pixels
     * @param scalePercent    viewing percentage, clamped to {@code 1..100}
     * @param minSizePx       minimum window size on either axis in pixels; non-positive disables it
     * @param currentMode     currently applied preset; must not be null
     * @return an immutable list of at least two rows; never null
     * @throws IllegalArgumentException if {@code currentMode} is null
     */
    public static List<Entry> build(int contentWidth, int contentHeight, int rotationDegrees,
                                    int boundsWidth, int boundsHeight, int scalePercent,
                                    int minSizePx, ZoomMode currentMode) {
        if (currentMode == null) {
            throw new IllegalArgumentException("currentMode must not be null");
        }

        final boolean geometryKnown = contentWidth > 0 && contentHeight > 0;
        final ViewerFitPolicy.Fit fit = ViewerFitPolicy.fit(contentWidth, contentHeight,
                rotationDegrees, boundsWidth, boundsHeight, scalePercent, minSizePx, ZoomMode.FIT);
        final ViewerFitPolicy.Fit oneToOne = ViewerFitPolicy.fit(contentWidth, contentHeight,
                rotationDegrees, boundsWidth, boundsHeight, scalePercent, minSizePx,
                ZoomMode.ONE_TO_ONE);
        final ViewerFitPolicy.Fit integer = ViewerFitPolicy.fit(contentWidth, contentHeight,
                rotationDegrees, boundsWidth, boundsHeight, scalePercent, minSizePx,
                ZoomMode.INTEGER);

        final boolean integerIsDuplicate = geometryKnown && sameWindow(integer, oneToOne);
        final List<Entry> entries = new ArrayList<>(3);
        entries.add(new Entry(ZoomMode.FIT, percentOf(fit), fit.isClamped(),
                currentMode == ZoomMode.FIT));
        entries.add(new Entry(ZoomMode.ONE_TO_ONE, percentOf(oneToOne), oneToOne.isClamped(),
                currentMode == ZoomMode.ONE_TO_ONE
                        || (integerIsDuplicate && currentMode == ZoomMode.INTEGER)));
        if (!integerIsDuplicate) {
            entries.add(new Entry(ZoomMode.INTEGER, percentOf(integer), integer.isClamped(),
                    currentMode == ZoomMode.INTEGER));
        }
        return Collections.unmodifiableList(entries);
    }

    /**
     * Returns whether two fits produce the same window on both axes.
     */
    private static boolean sameWindow(ViewerFitPolicy.Fit a, ViewerFitPolicy.Fit b) {
        return a.getWidth() == b.getWidth() && a.getHeight() == b.getHeight();
    }

    /**
     * Converts an effective scale to whole percent, clamped to at least one percent.
     */
    private static int percentOf(ViewerFitPolicy.Fit fit) {
        return Math.max(MIN_REPORTED_PERCENT, Math.round(fit.getScale() * 100f));
    }

    /**
     * One zoom menu row: the preset it applies, the scale it would use and whether it is the
     * currently applied preset.
     */
    public static final class Entry {

        private final ZoomMode mode;
        private final int effectivePercent;
        private final boolean clamped;
        private final boolean selected;

        private Entry(ZoomMode mode, int effectivePercent, boolean clamped, boolean selected) {
            this.mode = mode;
            this.effectivePercent = effectivePercent;
            this.clamped = clamped;
            this.selected = selected;
        }

        /**
         * Returns the preset this row applies; never null.
         */
        public ZoomMode getMode() {
            return mode;
        }

        /**
         * Returns the scale this row would apply, as whole percent, where {@code 100} means one
         * window pixel per stream pixel.
         */
        public int getEffectivePercent() {
            return effectivePercent;
        }

        /**
         * Returns whether the usable display area reduces this row's target size, which is what
         * the actual-size row reports for a stream larger than the screen.
         */
        public boolean isClamped() {
            return clamped;
        }

        /**
         * Returns whether this row is the currently applied preset, or its size-equivalent.
         */
        public boolean isSelected() {
            return selected;
        }
    }
}
