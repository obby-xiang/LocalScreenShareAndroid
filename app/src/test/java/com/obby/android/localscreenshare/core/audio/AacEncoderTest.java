package com.obby.android.localscreenshare.core.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.obby.android.localscreenshare.core.model.AudioAccessUnit;
import com.obby.android.localscreenshare.core.model.AudioDescriptor;
import com.obby.android.localscreenshare.core.support.FakeAudioEncoderBridge;

import org.junit.Test;

import java.util.List;

/**
 * Unit tests for {@link AacEncoder}.
 */
public class AacEncoderTest {

    private static final int SAMPLE_RATE = 48_000;

    @Test
    public void framesSamplesIntoFixedAacFrames() throws Exception {
        FakeAudioEncoderBridge bridge = new FakeAudioEncoderBridge();
        AacEncoder encoder = new AacEncoder(bridge);
        encoder.open(1, SAMPLE_RATE, 2, 128_000, 0L);

        // Two stereo frames: 2 * 1024 * 2 interleaved samples.
        List<AudioAccessUnit> units = encoder.encode(new short[4096], 0, 4096);

        assertEquals(2, units.size());
        assertEquals(0L, units.get(0).getPtsUs());
        assertEquals(21_333L, units.get(1).getPtsUs());
        assertEquals(0L, units.get(0).getSequence());
        assertEquals(1L, units.get(1).getSequence());
        assertEquals(1, units.get(0).getConfigEpoch());
    }

    @Test
    public void partialBlocksAccumulateUntilOneFrame() throws Exception {
        FakeAudioEncoderBridge bridge = new FakeAudioEncoderBridge();
        AacEncoder encoder = new AacEncoder(bridge);
        encoder.open(1, SAMPLE_RATE, 2, 128_000, 0L);

        assertEquals(0, encoder.encode(new short[2048], 0, 2047).size());
        List<AudioAccessUnit> units = encoder.encode(new short[1], 0, 1);

        assertEquals(1, units.size());
        assertEquals(1, bridge.getQueuedPtsUs().size());
    }

    @Test
    public void descriptorCarriesAudioSpecificConfig() throws Exception {
        FakeAudioEncoderBridge bridge = new FakeAudioEncoderBridge();
        AacEncoder encoder = new AacEncoder(bridge);
        encoder.open(3, SAMPLE_RATE, 2, 128_000, 0L);

        AudioDescriptor descriptor = encoder.getDescriptor();

        assertNotNull(descriptor);
        assertEquals(3, descriptor.getConfigEpoch());
        assertEquals(AudioCodecIds.AAC, descriptor.getCodecId());
        assertEquals(AudioCodecIds.AAC_MIME, descriptor.getMime());
        assertEquals(SAMPLE_RATE, descriptor.getSampleRate());
        assertEquals(2, descriptor.getChannels());
        assertEquals(128_000, descriptor.getBitrateBps());
        assertEquals(AudioCodecIds.CONFIG_PART_CSD_0,
                descriptor.getConfigParts().get(0).getId());
        assertEquals(2, descriptor.getConfigParts().get(0).getPayload().length);
    }

    @Test
    public void descriptorAppearsOnlyWhenTheEncoderReportsItsConfig() throws Exception {
        FakeAudioEncoderBridge bridge = new FakeAudioEncoderBridge(false);
        AacEncoder encoder = new AacEncoder(bridge);
        encoder.open(1, SAMPLE_RATE, 2, 128_000, 0L);

        assertNull(encoder.getDescriptor());
        encoder.encode(new short[4096], 0, 4096);

        assertNotNull(encoder.getDescriptor());
    }

    @Test
    public void timestampsFollowTheConfiguredAnchorAndRate() throws Exception {
        FakeAudioEncoderBridge bridge = new FakeAudioEncoderBridge();
        AacEncoder encoder = new AacEncoder(bridge);
        encoder.open(1, 44_100, 1, 64_000, 1_000_000L);

        List<AudioAccessUnit> units = encoder.encode(new short[3072], 0, 3072);

        assertEquals(3, units.size());
        assertEquals(1_000_000L, units.get(0).getPtsUs());
        assertEquals(1_023_219L, units.get(1).getPtsUs());
        assertEquals(1_046_439L, units.get(2).getPtsUs());
        assertTrue(units.get(2).getPtsUs() > units.get(1).getPtsUs());
    }

    @Test
    public void closeStopsEncodingAndClosesTheBridge() throws Exception {
        FakeAudioEncoderBridge bridge = new FakeAudioEncoderBridge();
        AacEncoder encoder = new AacEncoder(bridge);
        encoder.open(1, SAMPLE_RATE, 2, 128_000, 0L);

        encoder.close();
        encoder.close();

        assertTrue(bridge.isClosed());
        assertEquals(1, bridge.getCloseCount());
        assertThrows(IllegalStateException.class, () -> encoder.encode(new short[4096], 0, 4096));
    }

    @Test
    public void encoderFailurePropagatesWithItsReason() throws Exception {
        AudioCaptureException failure = new AudioCaptureException(
                AudioCaptureException.Reason.ENCODER_FAILED, "boom");
        FakeAudioEncoderBridge bridge = new FakeAudioEncoderBridge().failQueue(failure);
        AacEncoder encoder = new AacEncoder(bridge);
        encoder.open(1, SAMPLE_RATE, 2, 128_000, 0L);

        AudioCaptureException thrown = assertThrows(AudioCaptureException.class,
                () -> encoder.encode(new short[4096], 0, 4096));

        assertEquals(AudioCaptureException.Reason.ENCODER_FAILED, thrown.getReason());
    }

    @Test
    public void invalidConfigurationIsRejected() {
        AacEncoder encoder = new AacEncoder(new FakeAudioEncoderBridge());

        assertThrows(IllegalArgumentException.class,
                () -> encoder.open(0, SAMPLE_RATE, 2, 128_000, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> encoder.open(1, 0, 2, 128_000, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> encoder.open(1, SAMPLE_RATE, 3, 128_000, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> encoder.open(1, SAMPLE_RATE, 2, 0, 0L));
    }
}
