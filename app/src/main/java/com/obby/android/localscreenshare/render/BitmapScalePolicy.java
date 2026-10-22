package com.obby.android.localscreenshare.render;

/**
 * Chooses a {@code BitmapFactory} sample size for a frame that will be drawn in a display box.
 *
 * <p>JPEG frames arrive at the encoder's coded size, which can be several times larger than the
 * window or the full-screen content box that shows them. Decoding at full size costs memory and
 * time for pixels that are then thrown away by downscaling, so the frame is decoded with
 * {@code inSampleSize}, which skips whole pixel blocks during decoding instead of scaling
 * afterwards.</p>
 *
 * <p><b>Why both axes are sorted.</b> The box is the rotated content box, while the frame is
 * upright, so a frame of {@code 1080x2340} is compared with a box of {@code 2072x956} in a
 * landscape window. Comparing the long sides with the long sides and the short sides with the short
 * sides makes the decision independent of a 90-degree rotation, and the smaller of the two ratios
 * keeps the decoded frame at least as large as the box on both axes.</p>
 *
 * <p>The result is always a power of two, as {@code BitmapFactory} requires, clamped to
 * {@value #MAX_SAMPLE_SIZE}. The clamp bounds both the quality loss and the work a single frame can
 * cause; nothing here measures memory or frame time, that belongs to the device regression list.</p>
 */
public final class BitmapScalePolicy {

    /**
     * Largest sample size this policy returns.
     */
    public static final int MAX_SAMPLE_SIZE = 8;

    private BitmapScalePolicy() {
    }

    /**
     * Returns the sample size to decode a frame of {@code frameWidth} x {@code frameHeight} for a
     * box of {@code boxWidth} x {@code boxHeight}.
     *
     * <p>A non-positive frame or box dimension means the size is not known yet, which happens before
     * the first descriptor and before the host is laid out; the frame is then decoded at full size,
     * because guessing a downscale for an unmeasured box would only blur the picture.</p>
     *
     * @param frameWidth  coded frame width in pixels
     * @param frameHeight coded frame height in pixels
     * @param boxWidth    display box width in pixels
     * @param boxHeight   display box height in pixels
     * @return a power of two in {@code [1, }{@value #MAX_SAMPLE_SIZE}{@code ]}
     */
    public static int sampleSize(int frameWidth, int frameHeight, int boxWidth, int boxHeight) {
        if (frameWidth <= 0 || frameHeight <= 0 || boxWidth <= 0 || boxHeight <= 0) {
            return 1;
        }
        final long longFrame = Math.max(frameWidth, frameHeight);
        final long shortFrame = Math.min(frameWidth, frameHeight);
        final long longBox = Math.max(boxWidth, boxHeight);
        final long shortBox = Math.min(boxWidth, boxHeight);
        final long ratio = Math.min(longFrame / longBox, shortFrame / shortBox);
        if (ratio <= 1L) {
            return 1;
        }
        int sampleSize = 1;
        while (sampleSize < MAX_SAMPLE_SIZE && sampleSize * 2L <= ratio) {
            sampleSize *= 2;
        }
        return sampleSize;
    }
}
