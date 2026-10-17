package com.obby.android.localscreenshare.core.model;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class StreamDescriptorTest {

    private static final String CODEC_ID = "avc1";

    private static final String MIME = "video/avc";

    private static StreamKey streamKey() {
        return new StreamKey("session-instance-1", "stream-1");
    }

    @Test
    public void configHash_changesWhenCodecOrMimeChanges() {
        StreamDescriptor baseline = StreamDescriptor
                .builder(streamKey(), 1, CODEC_ID, MIME, BitstreamFormat.ANNEX_B)
                .codedSize(1280, 720)
                .build();
        StreamDescriptor otherCodec = StreamDescriptor
                .builder(streamKey(), 1, "hvc1", MIME, BitstreamFormat.ANNEX_B)
                .codedSize(1280, 720)
                .build();
        StreamDescriptor otherMime = StreamDescriptor
                .builder(streamKey(), 1, CODEC_ID, "video/hevc", BitstreamFormat.ANNEX_B)
                .codedSize(1280, 720)
                .build();

        assertNotEquals(baseline.getConfigHash(), otherCodec.getConfigHash());
        assertNotEquals(baseline.getConfigHash(), otherMime.getConfigHash());
    }

    @Test
    public void configHash_changesWhenConfigPartPayloadChanges() {
        StreamDescriptor baseline = StreamDescriptor
                .builder(streamKey(), 1, CODEC_ID, MIME, BitstreamFormat.ANNEX_B)
                .codedSize(1280, 720)
                .configParts(Collections.singletonList(
                        new CodecConfigPart("csd-0", new byte[]{0x01, 0x02, 0x03})))
                .build();
        StreamDescriptor changedPayload = StreamDescriptor
                .builder(streamKey(), 1, CODEC_ID, MIME, BitstreamFormat.ANNEX_B)
                .codedSize(1280, 720)
                .configParts(Collections.singletonList(
                        new CodecConfigPart("csd-0", new byte[]{0x01, 0x02, 0x04})))
                .build();

        assertNotEquals(baseline.getConfigHash(), changedPayload.getConfigHash());
    }

    @Test
    public void configHash_changesWhenVisibleSizeOrRotationChanges() {
        StreamDescriptor baseline = StreamDescriptor
                .builder(streamKey(), 1, CODEC_ID, MIME, BitstreamFormat.ANNEX_B)
                .codedSize(1280, 720)
                .visibleSize(1280, 720)
                .rotationDegrees(0)
                .build();
        StreamDescriptor otherVisibleSize = StreamDescriptor
                .builder(streamKey(), 1, CODEC_ID, MIME, BitstreamFormat.ANNEX_B)
                .codedSize(1280, 720)
                .visibleSize(1280, 704)
                .rotationDegrees(0)
                .build();
        StreamDescriptor otherRotation = StreamDescriptor
                .builder(streamKey(), 1, CODEC_ID, MIME, BitstreamFormat.ANNEX_B)
                .codedSize(1280, 720)
                .visibleSize(1280, 720)
                .rotationDegrees(90)
                .build();

        assertNotEquals(baseline.getConfigHash(), otherVisibleSize.getConfigHash());
        assertNotEquals(baseline.getConfigHash(), otherRotation.getConfigHash());
        assertFalse(baseline.sameDecodeConfig(otherVisibleSize));
        assertFalse(baseline.sameDecodeConfig(otherRotation));
    }

    @Test
    public void configHash_unchangedWhenOnlyEpochChanges() {
        StreamDescriptor firstEpoch = StreamDescriptor
                .builder(streamKey(), 1, CODEC_ID, MIME, BitstreamFormat.ANNEX_B)
                .codedSize(1280, 720)
                .build();
        StreamDescriptor secondEpoch = StreamDescriptor
                .builder(streamKey(), 2, CODEC_ID, MIME, BitstreamFormat.ANNEX_B)
                .codedSize(1280, 720)
                .build();

        assertEquals(firstEpoch.getConfigHash(), secondEpoch.getConfigHash());
        assertEquals(firstEpoch.getConfigHash(), firstEpoch.withConfigEpoch(2).getConfigHash());
        assertEquals(2, firstEpoch.withConfigEpoch(2).getConfigEpoch());
    }

    @Test
    public void configHash_isNormalizedLowercaseHexString() {
        StreamDescriptor descriptor = StreamDescriptor
                .builder(streamKey(), 1, CODEC_ID, MIME, BitstreamFormat.ANNEX_B)
                .codedSize(1280, 720)
                .build();
        String hash = descriptor.getConfigHash();

        assertNotNull(hash);
        assertTrue("hash must be 64 lowercase hex chars but was: " + hash,
                hash.matches("[0-9a-f]{64}"));
        assertEquals(hash, descriptor.getConfigHash());

        StreamDescriptor rebuilt = StreamDescriptor
                .builder(streamKey(), 1, CODEC_ID, MIME, BitstreamFormat.ANNEX_B)
                .codedSize(1280, 720)
                .build();
        assertEquals(hash, rebuilt.getConfigHash());
    }

    @Test
    public void builder_rejectsConfigEpochBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> StreamDescriptor
                .builder(streamKey(), 0, CODEC_ID, MIME, BitstreamFormat.ANNEX_B)
                .codedSize(1280, 720)
                .build());
        assertThrows(IllegalArgumentException.class, () -> StreamDescriptor
                .builder(streamKey(), -1, CODEC_ID, MIME, BitstreamFormat.ANNEX_B)
                .codedSize(1280, 720)
                .build());
    }

    @Test
    public void builder_rejectsInvalidRotationAndNonPositiveCodedSize() {
        assertThrows(IllegalArgumentException.class, () -> StreamDescriptor
                .builder(streamKey(), 1, CODEC_ID, MIME, BitstreamFormat.ANNEX_B)
                .codedSize(1280, 720)
                .rotationDegrees(45)
                .build());
        assertThrows(IllegalArgumentException.class, () -> StreamDescriptor
                .builder(streamKey(), 1, CODEC_ID, MIME, BitstreamFormat.ANNEX_B)
                .codedSize(0, 720)
                .build());
        assertThrows(IllegalArgumentException.class, () -> StreamDescriptor
                .builder(streamKey(), 1, CODEC_ID, MIME, BitstreamFormat.ANNEX_B)
                .codedSize(1280, 0)
                .build());
    }

    @Test
    public void emptyConfigParts_areAllowedForJpegDescriptor() {
        StreamDescriptor descriptor = StreamDescriptor
                .builder(streamKey(), 1, "jpeg", "image/jpeg", BitstreamFormat.JPEG_FRAME)
                .codedSize(1080, 1920)
                .build();

        assertEquals(BitstreamFormat.JPEG_FRAME, descriptor.getBitstreamFormat());
        assertNotNull(descriptor.getConfigParts());
        assertTrue(descriptor.getConfigParts().isEmpty());
    }

    @Test
    public void configPartsAndPayload_areDefensivelyCopied() {
        byte[] originalPayload = new byte[]{0x11, 0x22, 0x33};
        CodecConfigPart part = new CodecConfigPart("csd-0", originalPayload);
        assertArrayEquals(new byte[]{0x11, 0x22, 0x33}, part.getPayload());

        originalPayload[0] = 0x7f;
        assertArrayEquals(new byte[]{0x11, 0x22, 0x33}, part.getPayload());

        byte[] fetchedPayload = part.getPayload();
        fetchedPayload[1] = 0x7f;
        assertArrayEquals(new byte[]{0x11, 0x22, 0x33}, part.getPayload());

        List<CodecConfigPart> sourceParts = new ArrayList<>();
        sourceParts.add(part);
        StreamDescriptor descriptor = StreamDescriptor
                .builder(streamKey(), 1, CODEC_ID, MIME, BitstreamFormat.ANNEX_B)
                .codedSize(1280, 720)
                .configParts(sourceParts)
                .build();
        sourceParts.clear();

        assertEquals(1, descriptor.getConfigParts().size());
        assertEquals("csd-0", descriptor.getConfigParts().get(0).getId());
        assertArrayEquals(new byte[]{0x11, 0x22, 0x33},
                descriptor.getConfigParts().get(0).getPayload());
    }
}
