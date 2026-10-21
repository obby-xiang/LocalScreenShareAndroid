package com.obby.android.localscreenshare.core.audio;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * JVM tests for the frame arithmetic behind the audio/video offset.
 *
 * <p>They pin the two conversions the drift guard depends on: a platform timestamp projected onto a
 * measurement instant, and the queued-buffer subtraction that turns written frames into the stream
 * timestamp leaving the speaker. Both used to be expressed in nanoseconds of different clocks, which
 * is what made the offset read as the device uptime.</p>
 */
public class AudioPlayheadTest {

    private static final int RATE = 48_000;

    @Test
    public void presentedFramesGrowWithTheElapsedNanoseconds() {
        long frames = AudioPlayhead.presentedFrames(4_800L, 1_000_000_000L, 1_250_000_000L, RATE);

        assertEquals(16_800L, frames);
    }

    @Test
    public void presentedFramesDoNotGoBackwardsForATimestampInTheFuture() {
        long frames = AudioPlayhead.presentedFrames(4_800L, 2_000_000_000L, 1_000_000_000L, RATE);

        assertEquals(4_800L, frames);
    }

    @Test
    public void presentedFramesKeepTheRawPositionWithoutASampleRate() {
        assertEquals(4_800L, AudioPlayhead.presentedFrames(4_800L, 0L, 1_000_000_000L, 0));
        assertEquals(4_800L, AudioPlayhead.presentedFrames(4_800L, 0L, 1_000_000_000L, -48_000));
    }

    @Test
    public void presentedFramesStayUnknownWithoutAPosition() {
        assertEquals(AudioPlayhead.UNKNOWN_FRAMES,
                AudioPlayhead.presentedFrames(-1L, 0L, 1_000_000_000L, RATE));
    }

    @Test
    public void audiblePositionSubtractsTheFramesStillQueuedInTheTrack() {
        // 48 000 written frames, 43 200 presented: 100 ms are still in the track buffer.
        long audible = AudioPlayhead.audiblePtsUs(1_000_000L, 48_000L, 43_200L, RATE);

        assertEquals(900_000L, audible);
    }

    @Test
    public void audiblePositionIsTheEndOfTheWrittenAudioWhenNothingIsQueued() {
        long audible = AudioPlayhead.audiblePtsUs(1_000_000L, 48_000L, 48_000L, RATE);

        assertEquals(1_000_000L, audible);
    }

    @Test
    public void audiblePositionClampsAPositionAheadOfTheWrittenCount() {
        long audible = AudioPlayhead.audiblePtsUs(1_000_000L, 48_000L, 60_000L, RATE);

        assertEquals(1_000_000L, audible);
    }

    @Test
    public void audiblePositionIsUnknownWithoutATimelinePositionOrAFrameCount() {
        assertEquals(SessionClock.UNSET,
                AudioPlayhead.audiblePtsUs(SessionClock.UNSET, 48_000L, 48_000L, RATE));
        assertEquals(SessionClock.UNSET,
                AudioPlayhead.audiblePtsUs(1_000_000L, 48_000L, -1L, RATE));
        assertEquals(SessionClock.UNSET,
                AudioPlayhead.audiblePtsUs(1_000_000L, 48_000L, 48_000L, 0));
    }

    @Test
    public void anHourOfAudioStaysInMicroseconds() {
        // The device has been up for 21 hours and has played an hour of audio on top of it.
        long writtenFrames = 48_000L * 3_600L;
        long endPtsUs = 3_600_000_000L;

        long audible = AudioPlayhead.audiblePtsUs(endPtsUs, writtenFrames, writtenFrames, RATE);

        assertEquals(endPtsUs, audible);
    }
}
