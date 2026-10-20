package com.obby.android.localscreenshare.core.codec;

/**
 * Rounds a requested capture size onto the size alignment an encoder accepts.
 *
 * <p><b>Why.</b> A hardware encoder may accept coded sizes only in multiples of its width and
 * height alignment. The HiSilicon encoder of the 2026-10-21 device test rejected odd dimensions
 * with {@code Picture Width(885) ... invalid, should N*2} and the whole codec configuration failed,
 * so a capture size derived from a percentage (82% of 1080 rounds to 885) produced no encoder at
 * all. The capture size is aligned here before the encoder and the virtual display are asked to use
 * it, which keeps both on the same geometry: aligning only the coded size would leave the display
 * composing at a different width than the encoder expects and the picture would be squashed.</p>
 *
 * <p><b>Rule.</b> Sizes round down to the nearest multiple of the alignment. Rounding down never
 * exceeds the size the user asked for and never exceeds the usable display area, so the aligned
 * capture is always a little smaller than requested rather than larger. A size below one alignment
 * step is raised to exactly one step, because a zero-sized capture is unusable and the callers
 * clamp the capture percentage to a sane minimum long before this point.</p>
 *
 * <p><b>Purity.</b> No Android type is read, so the rule is unit-tested on the JVM. The alignment
 * values themselves come from the platform encoder's advertised capabilities, which means the same
 * probe decides what the encoder accepts and what the display is asked to render.</p>
 */
public final class EncoderSizeAlignment {

    /**
     * The alignment every {@code MediaCodec} encoder accepts: even width and height.
     *
     * <p>Used when the platform cannot report the alignment of the encoder that will be created.</p>
     */
    public static final EncoderSizeAlignment EVEN = new EncoderSizeAlignment(2, 2);

    private final int widthAlignment;

    private final int heightAlignment;

    private EncoderSizeAlignment(int widthAlignment, int heightAlignment) {
        this.widthAlignment = widthAlignment;
        this.heightAlignment = heightAlignment;
    }

    /**
     * Creates an alignment from the width and height steps an encoder advertises.
     *
     * @param widthAlignment  width step in pixels
     * @param heightAlignment height step in pixels
     * @return an immutable alignment; never null
     * @throws IllegalArgumentException if either step is smaller than one pixel
     */
    public static EncoderSizeAlignment of(int widthAlignment, int heightAlignment) {
        if (widthAlignment < 1) {
            throw new IllegalArgumentException("widthAlignment must be positive");
        }
        if (heightAlignment < 1) {
            throw new IllegalArgumentException("heightAlignment must be positive");
        }
        return new EncoderSizeAlignment(widthAlignment, heightAlignment);
    }

    /**
     * Returns the width step in pixels.
     */
    public int getWidthAlignment() {
        return widthAlignment;
    }

    /**
     * Returns the height step in pixels.
     */
    public int getHeightAlignment() {
        return heightAlignment;
    }

    /**
     * Returns {@code width} rounded down to a multiple of the width alignment.
     */
    public int alignWidth(int width) {
        return align(width, widthAlignment);
    }

    /**
     * Returns {@code height} rounded down to a multiple of the height alignment.
     */
    public int alignHeight(int height) {
        return align(height, heightAlignment);
    }

    private static int align(int value, int alignment) {
        if (value < alignment) {
            // A capture smaller than one alignment step is unusable when rounded down to zero, so
            // the smallest acceptable size is exactly one step.
            return alignment;
        }
        return value / alignment * alignment;
    }
}
