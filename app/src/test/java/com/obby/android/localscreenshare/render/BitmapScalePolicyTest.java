package com.obby.android.localscreenshare.render;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for {@link BitmapScalePolicy}.
 *
 * <p>They pin the three properties the JPEG path relies on: a frame is never upscaled, the chosen
 * sample size is a power of two that does not shrink the frame below the box, and a 90-degree
 * rotation of the same stream leads to the same decision.</p>
 */
public class BitmapScalePolicyTest {

    @Test
    public void sameSize_decodesAtFullSize() {
        assertEquals(1, BitmapScalePolicy.sampleSize(1080, 2340, 1080, 2340));
    }

    @Test
    public void twiceTheBox_doublesTheSampleSize() {
        assertEquals(2, BitmapScalePolicy.sampleSize(2000, 1000, 1000, 500));
    }

    @Test
    public void threeTimesTheBox_roundsDownToAPowerOfTwo() {
        assertEquals(2, BitmapScalePolicy.sampleSize(3000, 1500, 1000, 500));
    }

    @Test
    public void nineTimesTheBox_isClampedToTheMaximum() {
        assertEquals(BitmapScalePolicy.MAX_SAMPLE_SIZE,
            BitmapScalePolicy.sampleSize(9000, 4500, 1000, 500));
    }

    @Test
    public void unknownBox_decodesAtFullSize() {
        assertEquals(1, BitmapScalePolicy.sampleSize(1080, 2340, 0, 0));
        assertEquals(1, BitmapScalePolicy.sampleSize(1080, 2340, 1000, 0));
        assertEquals(1, BitmapScalePolicy.sampleSize(0, 0, 1000, 500));
        assertEquals(1, BitmapScalePolicy.sampleSize(-1, 2340, 1000, 500));
    }

    @Test
    public void rotationDoesNotChangeTheDecision() {
        // A portrait 1080x2340 frame inside a landscape window: the same stream, rotated, must not
        // change whether the frame is downsampled.
        assertEquals(1, BitmapScalePolicy.sampleSize(1080, 2340, 2072, 956));
        assertEquals(2, BitmapScalePolicy.sampleSize(1080, 2340, 1000, 460));
        assertEquals(1, BitmapScalePolicy.sampleSize(2340, 1080, 956, 2072));
        assertEquals(2, BitmapScalePolicy.sampleSize(2340, 1080, 460, 1000));
    }
}
