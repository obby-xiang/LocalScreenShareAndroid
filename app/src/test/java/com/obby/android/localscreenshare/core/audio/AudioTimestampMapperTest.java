package com.obby.android.localscreenshare.core.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

/**
 * Unit tests for {@link AudioTimestampMapper}.
 */
public class AudioTimestampMapperTest {

    private static final long ONE_SECOND_NS = 1_000_000_000L;

    @Test
    public void fallsBackToLinearDerivationAt48k() {
        long ptsUs = AudioTimestampMapper.ptsUsForFrame(48_000L, -1L, -1L, 0L, 48_000);

        assertEquals(1_000_000L, ptsUs);
    }

    @Test
    public void fallsBackToLinearDerivationAt44100() {
        long ptsUs = AudioTimestampMapper.ptsUsForFrame(44_100L, -1L, -1L, 0L, 44_100);

        assertEquals(1_000_000L, ptsUs);
    }

    @Test
    public void fallbackKeepsCaptureAnchor() {
        long ptsUs = AudioTimestampMapper.ptsUsForFrame(0L, -1L, -1L, 5_000_000_000L, 48_000);

        assertEquals(5_000_000L, ptsUs);
    }

    @Test
    public void mapsFrameBehindTheTimestampedPosition() {
        // 100 samples behind the timestamp at 48 kHz is 2 083 333 ns earlier.
        long ptsUs = AudioTimestampMapper.ptsUsForFrame(100L, 200L, ONE_SECOND_NS, 0L, 48_000);

        assertEquals(997_916L, ptsUs);
    }

    @Test
    public void mapsFrameAheadOfTheTimestampedPosition() {
        long ptsUs = AudioTimestampMapper.ptsUsForFrame(200L, 100L, ONE_SECOND_NS, 0L, 48_000);

        assertEquals(1_002_083L, ptsUs);
    }

    @Test
    public void unusableTimestampFallsBack() {
        assertEquals(500_000L,
                AudioTimestampMapper.ptsUsForFrame(24_000L, -1L, ONE_SECOND_NS, 0L, 48_000));
        assertEquals(500_000L,
                AudioTimestampMapper.ptsUsForFrame(24_000L, 1_000L, 0L, 0L, 48_000));
    }

    @Test
    public void staleOrResetPositionFallsBack() {
        // The frame is three seconds behind the timestamp: the pair describes a reset position.
        long ptsUs = AudioTimestampMapper.ptsUsForFrame(0L, 144_000L, ONE_SECOND_NS, 0L, 48_000);

        assertEquals(0L, ptsUs);
    }

    @Test
    public void oneHourOfFramesStaysWithinOneMillisecond() {
        final long oneHourFrames = 48_000L * 3_600L;
        final long expectedUs = 3_600_000_000L;

        long fallback = AudioTimestampMapper.ptsUsForFrame(oneHourFrames, -1L, -1L, 0L, 48_000);
        long anchored = AudioTimestampMapper.ptsUsForFrame(oneHourFrames, oneHourFrames,
                ONE_SECOND_NS + expectedUs * 1_000L, 0L, 48_000);

        assertEquals(0L, Math.abs(fallback - expectedUs));
        assertEquals(0L, Math.abs(anchored - expectedUs - 1_000_000L));
    }

    @Test
    public void nullTimestampFallsBack() {
        assertEquals(1_000_000L,
                AudioTimestampMapper.ptsUsForFrame(48_000L, (AudioCaptureSource.CaptureTimestamp) null,
                        0L, 48_000));
    }

    @Test
    public void durationAndArgumentsAreValidated() {
        assertEquals(21_333L, AudioTimestampMapper.durationUs(1024L, 48_000));
        assertThrows(IllegalArgumentException.class,
                () -> AudioTimestampMapper.ptsUsForFrame(-1L, -1L, -1L, 0L, 48_000));
        assertThrows(IllegalArgumentException.class,
                () -> AudioTimestampMapper.ptsUsForFrame(0L, -1L, -1L, 0L, 0));
        assertThrows(IllegalArgumentException.class,
                () -> AudioTimestampMapper.durationUs(1L, 0));
    }
}
