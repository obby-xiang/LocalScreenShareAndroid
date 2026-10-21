package com.obby.android.localscreenshare.core.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * Unit tests for {@link AudioDescriptor}.
 */
public class AudioDescriptorTest {

    private static final CodecConfigPart CSD_0 = new CodecConfigPart("csd-0", new byte[]{0x12, 0x10});

    @Test
    public void buildsAacLcDescriptorFromRequiredFields() {
        AudioDescriptor descriptor = AudioDescriptor.builder(1, "aac", "audio/mp4a-latm",
                        48_000, 2, 128_000)
                .profile("aac-lc")
                .configParts(Collections.singletonList(CSD_0))
                .build();

        assertEquals(1, descriptor.getConfigEpoch());
        assertEquals("aac", descriptor.getCodecId());
        assertEquals("audio/mp4a-latm", descriptor.getMime());
        assertEquals("aac-lc", descriptor.getProfile());
        assertEquals(48_000, descriptor.getSampleRate());
        assertEquals(2, descriptor.getChannels());
        assertEquals(128_000, descriptor.getBitrateBps());
        assertEquals(AudioDescriptor.FRAME_SAMPLES_AAC_LC, descriptor.getFrameSamples());
        assertEquals(1, descriptor.getConfigParts().size());
        assertEquals(64, descriptor.getConfigHash().length());
    }

    @Test
    public void frameDurationFollowsSampleRate() {
        AudioDescriptor at48k = descriptor(48_000);
        AudioDescriptor at441k = descriptor(44_100);

        assertEquals(21_333L, at48k.getFrameDurationUs());
        assertEquals(23_219L, at441k.getFrameDurationUs());
    }

    @Test
    public void configHashIgnoresEpochAndCoversConfiguration() {
        AudioDescriptor first = descriptor(48_000);
        AudioDescriptor republished = first.withConfigEpoch(2);

        assertEquals(first.getConfigHash(), republished.getConfigHash());
        assertTrue(first.sameDecodeConfig(republished));
        assertEquals(2, republished.getConfigEpoch());

        AudioDescriptor otherFormat = AudioDescriptor.builder(1, "aac", "audio/mp4a-latm",
                        44_100, 2, 128_000)
                .configParts(Collections.singletonList(CSD_0))
                .build();
        assertNotEquals(first.getConfigHash(), otherFormat.getConfigHash());
        assertFalse(first.sameDecodeConfig(otherFormat));
        assertFalse(first.sameDecodeConfig(null));
    }

    @Test
    public void configHashChangesWithConfigParts() {
        AudioDescriptor a = AudioDescriptor.builder(1, "aac", "audio/mp4a-latm", 48_000, 2,
                        128_000).configParts(Arrays.asList(
                        new CodecConfigPart("csd-0", new byte[]{0x11, (byte) 0x90}))).build();
        AudioDescriptor b = AudioDescriptor.builder(1, "aac", "audio/mp4a-latm", 48_000, 2,
                        128_000).configParts(Arrays.asList(
                        new CodecConfigPart("csd-0", new byte[]{0x12, 0x10}))).build();

        assertNotEquals(a.getConfigHash(), b.getConfigHash());
    }

    @Test
    public void rejectsNonPositiveSampleRate() {
        assertThrows(IllegalArgumentException.class, () -> AudioDescriptor.builder(1, "aac",
                "audio/mp4a-latm", 0, 2, 128_000)
                .configParts(Collections.singletonList(CSD_0)).build());
    }

    @Test
    public void rejectsChannelCountAboveTwo() {
        assertThrows(IllegalArgumentException.class, () -> AudioDescriptor.builder(1, "aac",
                "audio/mp4a-latm", 48_000, 3, 128_000)
                .configParts(Collections.singletonList(CSD_0)).build());
    }

    @Test
    public void rejectsEmptyConfigParts() {
        assertThrows(IllegalArgumentException.class, () -> AudioDescriptor.builder(1, "aac",
                "audio/mp4a-latm", 48_000, 2, 128_000)
                .configParts(Collections.<CodecConfigPart>emptyList()).build());
        assertThrows(IllegalArgumentException.class, () -> AudioDescriptor.builder(1, "aac",
                "audio/mp4a-latm", 48_000, 2, 128_000).build());
    }

    @Test
    public void rejectsUnsupportedFrameLength() {
        assertThrows(IllegalArgumentException.class, () -> AudioDescriptor.builder(1, "aac",
                "audio/mp4a-latm", 48_000, 2, 128_000)
                .frameSamples(960)
                .configParts(Collections.singletonList(CSD_0)).build());
    }

    @Test
    public void rejectsMissingCodecIdentityAndEpochZero() {
        assertThrows(IllegalArgumentException.class, () -> AudioDescriptor.builder(1, " ",
                "audio/mp4a-latm", 48_000, 2, 128_000)
                .configParts(Collections.singletonList(CSD_0)).build());
        assertThrows(IllegalArgumentException.class, () -> AudioDescriptor.builder(1, "aac",
                "", 48_000, 2, 128_000)
                .configParts(Collections.singletonList(CSD_0)).build());
        assertThrows(IllegalArgumentException.class, () -> AudioDescriptor.builder(0, "aac",
                "audio/mp4a-latm", 48_000, 2, 128_000)
                .configParts(Collections.singletonList(CSD_0)).build());
        assertThrows(IllegalArgumentException.class, () -> AudioDescriptor.builder(1, "aac",
                "audio/mp4a-latm", 48_000, 2, 0)
                .configParts(Collections.singletonList(CSD_0)).build());
    }

    @Test
    public void configPartsAreUnmodifiableAndCopied() {
        AudioDescriptor descriptor = descriptor(48_000);
        assertThrows(UnsupportedOperationException.class,
                () -> descriptor.getConfigParts().clear());
    }

    private static AudioDescriptor descriptor(int sampleRate) {
        return AudioDescriptor.builder(1, "aac", "audio/mp4a-latm", sampleRate, 2, 128_000)
                .configParts(Collections.singletonList(CSD_0))
                .build();
    }
}
