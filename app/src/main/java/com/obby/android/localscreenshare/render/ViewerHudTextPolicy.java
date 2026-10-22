package com.obby.android.localscreenshare.render;

/**
 * Pure text size policy for the monospaced debug overlay of the viewer panel.
 *
 * <p>The overlay shows one line per metric block in a fixed-pitch font inside the floating window,
 * and the window can be dragged down to a fraction of the display. A fixed size therefore either
 * overflows the window or, worse, lets the text view wrap a line onto the next one, which hides the
 * numbers behind each other. The policy answers exactly one question: which text size, shared by
 * every overlay line, keeps the widest line inside the available width.</p>
 *
 * <p><b>Rules.</b> Text width grows linearly with the text size for a fixed string, so the widest
 * measured line at {@code maxTextSizePx} is scaled by {@code availableWidthPx / widestLineWidthPx}
 * and floored to whole pixels, which is the granularity of {@link android.widget.TextView}'s text
 * size. The result never exceeds the maximum, which keeps the overlay in the full-screen host and
 * in a normal sized window exactly as it was, and never drops below the minimum. When the widest
 * line overflows even at the minimum, the policy still returns the minimum: truncating that line is
 * the view's job, not a reason to shrink further and make the overlay unreadable.</p>
 *
 * <p><b>Unknown geometry.</b> A non-positive available width means the window has not been laid
 * out yet, and a non-positive widest line means there is no text; both return the maximum so a
 * pre-layout call never shrinks the overlay.</p>
 *
 * <p><b>Purity.</b> The policy reads no configuration, keeps no state and touches no Android type,
 * so every rule is unit-testable on the JVM.</p>
 */
public final class ViewerHudTextPolicy {

    /**
     * Text size of the overlay while every line fits the window, in scale-independent pixels.
     */
    public static final float MAX_TEXT_SIZE_SP = 11f;

    /**
     * Smallest text size the overlay shrinks to before the widest line is truncated.
     */
    public static final float MIN_TEXT_SIZE_SP = 9f;

    private ViewerHudTextPolicy() {
    }

    /**
     * Returns the text size, in pixels, shared by every overlay line.
     *
     * @param availableWidthPx  width the overlay text may occupy in pixels; non-positive means the
     *                          window is not laid out yet
     * @param maxTextSizePx     size used while the widest line fits in pixels
     * @param minTextSizePx     smallest size the overlay shrinks to in pixels
     * @param widestLineWidthPx width of the widest overlay line at {@code maxTextSizePx} in pixels;
     *                          non-positive means there is nothing to measure
     * @return a size in {@code minTextSizePx..maxTextSizePx}, never zero
     * @throws IllegalArgumentException if the bounds do not satisfy
     *                                  {@code 0 < minTextSizePx <= maxTextSizePx}
     */
    public static int fitTextSizePx(int availableWidthPx, int maxTextSizePx, int minTextSizePx,
                                    int widestLineWidthPx) {
        if (maxTextSizePx <= 0 || minTextSizePx <= 0 || minTextSizePx > maxTextSizePx) {
            throw new IllegalArgumentException("text size bounds must satisfy 0 < min <= max");
        }
        if (availableWidthPx <= 0 || widestLineWidthPx <= 0
            || widestLineWidthPx <= availableWidthPx) {
            return maxTextSizePx;
        }
        // Long arithmetic keeps the product exact for any realistic pixel size and width.
        final int scaled = (int) ((long) maxTextSizePx * availableWidthPx / widestLineWidthPx);
        return Math.max(minTextSizePx, Math.min(maxTextSizePx, scaled));
    }
}
