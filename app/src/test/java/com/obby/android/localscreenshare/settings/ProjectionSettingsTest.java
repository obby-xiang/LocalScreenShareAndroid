package com.obby.android.localscreenshare.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.obby.android.localscreenshare.core.codec.CodecIds;
import com.obby.android.localscreenshare.core.model.StreamParameters;

import org.junit.Test;

/**
 * Unit tests for {@link ProjectionSettings}.
 *
 * <p>Every assertion starts from a stored preference value a previous version could have written,
 * so the tests double as the contract of the validation: ranges are clamped, the key-frame interval
 * becomes a whole number of seconds, and an unknown codec falls back to H.264 without throwing.</p>
 */
public class ProjectionSettingsTest {

    @Test
    public void validValues_areKeptAsStored() {
        ProjectionSettings settings = ProjectionSettings.of(4_000_000, 60, 1_000, 100, 85,
                CodecIds.H264);

        assertEquals(4_000_000, settings.getBitrateBps());
        assertEquals(60, settings.getFrameRate());
        assertEquals(1_000, settings.getIdrIntervalMs());
        assertEquals(100, settings.getScalePercent());
        assertEquals(85, settings.getJpegQuality());
        assertEquals(CodecIds.H264, settings.getCodecId());
        assertTrue(settings.isH264());
    }

    @Test
    public void idrInterval_belowOneSecond_isRaisedToWholeSecond() {
        assertEquals(1_000, ProjectionSettings.of(4_000_000, 60, 500, 100, 85,
                CodecIds.H264).getIdrIntervalMs());
        assertEquals(1_000, ProjectionSettings.of(4_000_000, 60, 1, 100, 85,
                CodecIds.H264).getIdrIntervalMs());
        assertEquals(1_000, ProjectionSettings.of(4_000_000, 60, 0, 100, 85,
                CodecIds.H264).getIdrIntervalMs());
    }

    @Test
    public void idrInterval_isRoundedUpToWholeSeconds() {
        assertEquals(2_000, ProjectionSettings.of(4_000_000, 60, 1_500, 100, 85,
                CodecIds.H264).getIdrIntervalMs());
        assertEquals(3_000, ProjectionSettings.of(4_000_000, 60, 2_001, 100, 85,
                CodecIds.H264).getIdrIntervalMs());
        assertEquals(30_000, ProjectionSettings.of(4_000_000, 60, 30_000, 100, 85,
                CodecIds.H264).getIdrIntervalMs());
    }

    @Test
    public void idrInterval_aboveRange_isClampedToThirtySeconds() {
        assertEquals(30_000, ProjectionSettings.of(4_000_000, 60, 45_000, 100, 85,
                CodecIds.H264).getIdrIntervalMs());
        assertEquals(30_000, ProjectionSettings.of(4_000_000, 60, Integer.MAX_VALUE, 100, 85,
                CodecIds.H264).getIdrIntervalMs());
    }

    @Test
    public void bitrate_outsideRange_isClamped() {
        assertEquals(ProjectionSettings.MIN_BITRATE_BPS, ProjectionSettings.of(1, 60, 1_000, 100,
                85, CodecIds.H264).getBitrateBps());
        assertEquals(ProjectionSettings.MAX_BITRATE_BPS, ProjectionSettings.of(100_000_000, 60,
                1_000, 100, 85, CodecIds.H264).getBitrateBps());
    }

    @Test
    public void frameRate_outsideRange_isClamped() {
        assertEquals(ProjectionSettings.MIN_FRAME_RATE, ProjectionSettings.of(4_000_000, 0, 1_000,
                100, 85, CodecIds.H264).getFrameRate());
        assertEquals(ProjectionSettings.MAX_FRAME_RATE, ProjectionSettings.of(4_000_000, 500,
                1_000, 100, 85, CodecIds.H264).getFrameRate());
    }

    @Test
    public void scaleAndQuality_outsideRange_areClamped() {
        ProjectionSettings low = ProjectionSettings.of(4_000_000, 60, 1_000, 0, -5,
                CodecIds.H264);
        ProjectionSettings high = ProjectionSettings.of(4_000_000, 60, 1_000, 500, 500,
                CodecIds.H264);

        assertEquals(ProjectionSettings.MIN_SCALE_PERCENT, low.getScalePercent());
        assertEquals(ProjectionSettings.MIN_JPEG_QUALITY, low.getJpegQuality());
        assertEquals(ProjectionSettings.MAX_SCALE_PERCENT, high.getScalePercent());
        assertEquals(ProjectionSettings.MAX_JPEG_QUALITY, high.getJpegQuality());
    }

    @Test
    public void unknownCodec_fallsBackToH264() {
        for (String codecId : new String[]{null, "", "  ", CodecIds.HEVC, CodecIds.AV1,
                "unknown"}) {
            ProjectionSettings settings = ProjectionSettings.of(4_000_000, 60, 1_000, 100, 85,
                    codecId);
            assertEquals(String.valueOf(codecId), CodecIds.H264, settings.getCodecId());
            assertTrue(settings.isH264());
        }
    }

    @Test
    public void jpegCodec_isPreserved() {
        ProjectionSettings settings = ProjectionSettings.of(4_000_000, 60, 1_000, 100, 85,
                " JPEG ");

        assertEquals(CodecIds.JPEG, settings.getCodecId());
        assertFalse(settings.isH264());
    }

    @Test
    public void toStreamParameters_carriesTheValidatedValues() {
        StreamParameters parameters = ProjectionSettings.of(2_500_000, 30, 1_500, 50, 70,
                CodecIds.H264).toStreamParameters();

        assertEquals(2_500_000, parameters.getTargetBitrateBps());
        assertEquals(30, parameters.getFrameRateHint());
        assertEquals(2_000, parameters.getKeyFrameIntervalMs());
    }
}
