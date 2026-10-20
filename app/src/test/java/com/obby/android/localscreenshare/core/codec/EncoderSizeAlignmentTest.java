package com.obby.android.localscreenshare.core.codec;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * JVM tests for the capture-size alignment.
 *
 * <p>The device report that motivated this class: 82% of 1080x2340 is 885x1918, and the HiSilicon
 * encoder refused the configuration with {@code Picture Width(885) ... invalid, should N*2}, so the
 * whole session lost its encoder. The alignment therefore has to turn such a percentage into a
 * size the encoder accepts while keeping the display on exactly the same geometry.</p>
 */
public class EncoderSizeAlignmentTest {

    @Test
    public void evenAlignment_roundsOddDimensionsDown() {
        EncoderSizeAlignment alignment = EncoderSizeAlignment.EVEN;

        // The two sizes the device report failed on.
        assertEquals(884, alignment.alignWidth(885));
        assertEquals(1918, alignment.alignHeight(1918));
        assertEquals(852, alignment.alignWidth(853));
        assertEquals(1848, alignment.alignHeight(1848));
    }

    @Test
    public void evenAlignment_keepsEvenDimensions() {
        EncoderSizeAlignment alignment = EncoderSizeAlignment.EVEN;

        assertEquals(1080, alignment.alignWidth(1080));
        assertEquals(2340, alignment.alignHeight(2340));
        assertEquals(864, alignment.alignWidth(864));
        assertEquals(1872, alignment.alignHeight(1872));
    }

    @Test
    public void largerAlignment_roundsDownToItsStep() {
        EncoderSizeAlignment alignment = EncoderSizeAlignment.of(16, 8);

        assertEquals(1904, alignment.alignWidth(1918));
        assertEquals(864, alignment.alignWidth(865));
        assertEquals(2472, alignment.alignHeight(2475));
        assertEquals(16, alignment.getWidthAlignment());
        assertEquals(8, alignment.getHeightAlignment());
    }

    @Test
    public void sizeBelowOneStep_neverRoundsDownToZero() {
        EncoderSizeAlignment alignment = EncoderSizeAlignment.of(16, 16);

        assertEquals(16, alignment.alignWidth(1));
        assertEquals(16, alignment.alignWidth(15));
        assertEquals(16, alignment.alignHeight(0));
    }

    @Test
    public void minimumSize_alignment_roundsUpToItsStep() {
        // The minimum-size probe raises the advertised minimum before it is compared with an
        // aligned capture: rounding down would undershoot a minimum that is not itself a multiple
        // of the step.
        EncoderSizeAlignment alignment = EncoderSizeAlignment.of(4, 4);

        assertEquals(144, alignment.alignWidthUp(143));
        assertEquals(144, alignment.alignWidthUp(144));
        assertEquals(108, alignment.alignHeightUp(105));
        assertEquals(4, alignment.alignWidthUp(1));
        assertEquals(2, EncoderSizeAlignment.EVEN.alignWidthUp(1));
        assertEquals(1080, EncoderSizeAlignment.EVEN.alignHeightUp(1080));
    }

    @Test(expected = IllegalArgumentException.class)
    public void nonPositiveWidthAlignment_isRejected() {
        EncoderSizeAlignment.of(0, 2);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nonPositiveHeightAlignment_isRejected() {
        EncoderSizeAlignment.of(2, -1);
    }
}
