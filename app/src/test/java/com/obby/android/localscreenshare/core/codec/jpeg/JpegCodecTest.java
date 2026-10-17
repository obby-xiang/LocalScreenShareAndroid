package com.obby.android.localscreenshare.core.codec.jpeg;

import com.obby.android.localscreenshare.core.codec.CodecIds;
import com.obby.android.localscreenshare.core.codec.CodecPolicy;
import com.obby.android.localscreenshare.core.codec.CodecRegistration;
import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.codec.DecoderException;
import com.obby.android.localscreenshare.core.codec.EncoderOutput;
import com.obby.android.localscreenshare.core.codec.OverflowAction;
import com.obby.android.localscreenshare.core.model.AccessUnitFlags;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.JoinInfo;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.model.StreamParameters;
import com.obby.android.localscreenshare.core.render.EncodedImageFrame;
import com.obby.android.localscreenshare.core.support.FakeCodec;
import com.obby.android.localscreenshare.core.support.FakeRenderTarget;
import com.obby.android.localscreenshare.core.support.JpegFixtures;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class JpegCodecTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session-1", "stream-1");

    private static final long PTS_US = 1_000L;

    @Test
    public void registration_hasJpegDescriptorAndPolicy() {
        CodecRegistration registration = JpegCodec.registration();

        assertEquals("image/jpeg", JpegCodec.MIME);
        assertEquals(CodecIds.JPEG, registration.getDescriptor().getCodecId());
        assertEquals("image/jpeg", registration.getDescriptor().getMime());
        assertEquals(BitstreamFormat.JPEG_FRAME, registration.getDescriptor().getBitstreamFormat());
        assertFalse(registration.getDescriptor().isSurfaceInputRequired());
        assertFalse(registration.getDescriptor().isSurfaceOutputRequired());
        assertFalse(registration.getDescriptor().hasConfigParts());
        assertEquals(100, registration.getFallbackPriority());
        assertTrue(registration.getPolicy() instanceof JpegCodecPolicy);
        assertTrue(registration.getEncoderFactory() instanceof JpegEncoderFactory);
        assertTrue(registration.getDecoderFactory() instanceof JpegDecoderFactory);
    }

    @Test
    public void registryWithDefaults_registersJpegOnly() {
        CodecRegistry registry = CodecRegistry.withDefaults();

        assertEquals(1, registry.getCodecIds().size());
        assertTrue(registry.contains(CodecIds.JPEG));
        assertEquals("image/jpeg", registry.require(CodecIds.JPEG).getDescriptor().getMime());
        assertTrue(registry.require(CodecIds.JPEG).getPolicy() instanceof JpegCodecPolicy);

        registry.register(new FakeCodec("fake", 0, false, false).registration());
        assertEquals(2, registry.getCodecIds().size());
        assertEquals(1, CodecRegistry.withDefaults().getCodecIds().size());
    }

    @Test
    public void validator_acceptsOnlyCompleteFrames() {
        assertTrue(JpegFrameValidator.isCompleteFrame(JpegFixtures.REAL_JPEG));
        assertTrue(JpegFrameValidator.isCompleteFrame(JpegFixtures.VALID_FRAME));
        assertTrue(JpegFrameValidator.isCompleteFrame(JpegFixtures.validFrameWithPadding()));

        assertFalse(JpegFrameValidator.isCompleteFrame(null));
        assertFalse(JpegFrameValidator.isCompleteFrame(JpegFixtures.EMPTY_FRAME));
        assertFalse(JpegFrameValidator.isCompleteFrame(JpegFixtures.TRUNCATED_FRAME));
        assertFalse(JpegFrameValidator.isCompleteFrame(
                new byte[]{(byte) 0xFF, (byte) 0xD8}));
        assertFalse(JpegFrameValidator.isCompleteFrame(
                new byte[]{(byte) 0xFF, (byte) 0xD9, (byte) 0xFF, (byte) 0xD8}));
        assertFalse(JpegFrameValidator.isCompleteFrame(
                JpegFixtures.frameWithTrailingGarbage()));
    }

    @Test
    public void encoder_emitsJoinPointAuWithEpochAndZeroSequence() {
        RecordingOutput output = new RecordingOutput();
        JpegEncoder encoder = new JpegEncoder(STREAM_KEY, jpegDescriptor(3),
                StreamParameters.DEFAULT, output);
        encoder.start();

        assertTrue(encoder.offerJpegFrame(PTS_US, JpegFixtures.REAL_JPEG));

        assertEquals(1, output.units().size());
        EncodedAccessUnit unit = output.units().get(0);
        assertEquals(STREAM_KEY, unit.getStreamKey());
        assertEquals(3, unit.getConfigEpoch());
        assertEquals(0L, unit.getSequence());
        assertEquals(PTS_US, unit.getPtsUs());
        assertFalse(unit.hasDtsUs());
        assertTrue(unit.getFlags().isJoinPoint());
        assertArrayEquals(JpegFixtures.REAL_JPEG, unit.getPayload());
        assertNotNull(unit.getJoinInfo());
        assertEquals(3, unit.getJoinInfo().getConfigEpoch());
        assertTrue(unit.getJoinInfo().getRequiredConfigParts().isEmpty());
    }

    @Test
    public void encoder_sequenceResetsPerEncoder() {
        RecordingOutput firstOutput = new RecordingOutput();
        JpegEncoder first = new JpegEncoder(STREAM_KEY, jpegDescriptor(3),
                StreamParameters.DEFAULT, firstOutput);
        first.start();
        assertTrue(first.offerJpegFrame(PTS_US, JpegFixtures.REAL_JPEG));
        assertTrue(first.offerJpegFrame(PTS_US + 1, JpegFixtures.VALID_FRAME));

        assertEquals(0L, firstOutput.units().get(0).getSequence());
        assertEquals(1L, firstOutput.units().get(1).getSequence());

        RecordingOutput secondOutput = new RecordingOutput();
        JpegEncoder second = new JpegEncoder(STREAM_KEY, jpegDescriptor(4),
                StreamParameters.DEFAULT, secondOutput);
        second.start();
        assertTrue(second.offerJpegFrame(PTS_US, JpegFixtures.REAL_JPEG));

        assertEquals(0L, secondOutput.units().get(0).getSequence());
        assertEquals(4, secondOutput.units().get(0).getConfigEpoch());
    }

    @Test
    public void encoder_dropsTruncatedAndEmptyFrames() {
        RecordingOutput output = new RecordingOutput();
        JpegEncoder encoder = new JpegEncoder(STREAM_KEY, jpegDescriptor(1),
                StreamParameters.DEFAULT, output);
        encoder.start();

        assertFalse(encoder.offerJpegFrame(PTS_US, null));
        assertFalse(encoder.offerJpegFrame(PTS_US, JpegFixtures.EMPTY_FRAME));
        assertFalse(encoder.offerJpegFrame(PTS_US, JpegFixtures.TRUNCATED_FRAME));
        assertEquals(0, output.units().size());

        assertTrue(encoder.offerJpegFrame(PTS_US, JpegFixtures.REAL_JPEG));
        assertEquals(1, output.units().size());
        assertArrayEquals(JpegFixtures.REAL_JPEG, output.units().get(0).getPayload());
    }

    @Test
    public void encoder_afterStopOrClose_dropsFrames() {
        RecordingOutput output = new RecordingOutput();
        JpegEncoder encoder = new JpegEncoder(STREAM_KEY, jpegDescriptor(1),
                StreamParameters.DEFAULT, output);
        encoder.start();
        assertTrue(encoder.offerJpegFrame(PTS_US, JpegFixtures.REAL_JPEG));
        assertEquals(1, output.units().size());

        encoder.stop();
        assertFalse(encoder.offerJpegFrame(PTS_US + 1, JpegFixtures.REAL_JPEG));
        assertEquals(1, output.units().size());

        encoder.close();
        assertFalse(encoder.offerJpegFrame(PTS_US + 2, JpegFixtures.REAL_JPEG));
        assertEquals(1, output.units().size());
    }

    @Test
    public void encoder_getInputSurfaceIsNull() {
        JpegEncoder encoder = new JpegEncoder(STREAM_KEY, jpegDescriptor(1),
                StreamParameters.DEFAULT, new RecordingOutput());

        assertNull(encoder.getInputSurface());
    }

    @Test
    public void encoderFactory_rejectsNonJpegDescriptor() {
        StreamDescriptor annexB = StreamDescriptor.builder(STREAM_KEY, 1, CodecIds.H264,
                        "video/avc", BitstreamFormat.ANNEX_B)
                .codedSize(64, 48)
                .build();
        RecordingOutput output = new RecordingOutput();

        assertThrows(IllegalArgumentException.class, () -> new JpegEncoderFactory()
                .create(STREAM_KEY, annexB, StreamParameters.DEFAULT, output));
        assertTrue(output.units().isEmpty());
    }

    @Test
    public void decoder_rendersEncodedImageFrameToTarget() {
        FakeRenderTarget target = new FakeRenderTarget();
        StreamDescriptor descriptor = jpegDescriptor(1, 64, 48);
        JpegDecoder decoder = new JpegDecoder(STREAM_KEY, descriptor, target);
        decoder.configure(descriptor);

        decoder.decode(accessUnit(1, 0, JpegFixtures.REAL_JPEG, true));

        assertEquals(1, target.getImages().size());
        EncodedImageFrame frame = target.getImages().get(0);
        assertEquals(64, frame.getWidth());
        assertEquals(48, frame.getHeight());
        assertEquals(PTS_US, frame.getPtsUs());
        assertArrayEquals(JpegFixtures.REAL_JPEG, frame.getPayload());
    }

    @Test
    public void decoder_rejectsMismatchedEpoch() {
        FakeRenderTarget target = new FakeRenderTarget();
        StreamDescriptor descriptor = jpegDescriptor(2);
        JpegDecoder decoder = new JpegDecoder(STREAM_KEY, descriptor, target);
        decoder.configure(descriptor);

        assertThrows(DecoderException.class,
                () -> decoder.decode(accessUnit(1, 0, JpegFixtures.REAL_JPEG, true)));
        assertTrue(target.getImages().isEmpty());
    }

    @Test
    public void decoder_beforeConfigureThrowsDecoderException() {
        JpegDecoder decoder = new JpegDecoder(STREAM_KEY, jpegDescriptor(1),
                new FakeRenderTarget());

        assertThrows(DecoderException.class,
                () -> decoder.decode(accessUnit(1, 0, JpegFixtures.REAL_JPEG, true)));
    }

    @Test
    public void decoder_rejectsIncompleteFrame() {
        FakeRenderTarget target = new FakeRenderTarget();
        StreamDescriptor descriptor = jpegDescriptor(1);
        JpegDecoder decoder = new JpegDecoder(STREAM_KEY, descriptor, target);
        decoder.configure(descriptor);

        assertThrows(DecoderException.class,
                () -> decoder.decode(accessUnit(1, 0, JpegFixtures.TRUNCATED_FRAME, false)));
        assertTrue(target.getImages().isEmpty());
    }

    @Test
    public void policy_keepsLatestFrame() {
        CodecPolicy policy = JpegCodec.registration().getPolicy();

        assertEquals(OverflowAction.KEEP_LATEST_FRAME, policy.getOverflowAction());
    }

    @Test
    public void policy_joinPointRequiresJoinFlagAndCompletePayload() {
        CodecPolicy policy = JpegCodec.registration().getPolicy();

        assertTrue(policy.isJoinPoint(accessUnit(1, 0, JpegFixtures.REAL_JPEG, true)));
        assertFalse(policy.isJoinPoint(accessUnit(1, 1, JpegFixtures.REAL_JPEG, false)));
        assertFalse(policy.isJoinPoint(accessUnit(1, 2, JpegFixtures.TRUNCATED_FRAME, true)));
    }

    @Test
    public void policy_requiresReconfigureTracksDecodeConfig() {
        CodecPolicy policy = JpegCodec.registration().getPolicy();
        StreamDescriptor epochOne = jpegDescriptor(1);

        assertFalse(policy.requiresReconfigure(epochOne, jpegDescriptor(2)));
        assertTrue(policy.requiresReconfigure(epochOne, jpegDescriptor(2, 32, 24)));
    }

    private static StreamDescriptor jpegDescriptor(int configEpoch) {
        return jpegDescriptor(configEpoch, 64, 48);
    }

    private static StreamDescriptor jpegDescriptor(int configEpoch, int width, int height) {
        return StreamDescriptor.builder(STREAM_KEY, configEpoch, CodecIds.JPEG, JpegCodec.MIME,
                        BitstreamFormat.JPEG_FRAME)
                .codedSize(width, height)
                .visibleSize(width, height)
                .build();
    }

    private static EncodedAccessUnit accessUnit(int configEpoch, long sequence, byte[] payload,
                                                boolean joinPoint) {
        return new EncodedAccessUnit(STREAM_KEY, configEpoch, sequence, PTS_US,
                EncodedAccessUnit.DTS_UNSET,
                AccessUnitFlags.of(true, joinPoint, false),
                joinPoint ? new JoinInfo(configEpoch, Collections.emptyList()) : null,
                null, payload);
    }

    private static final class RecordingOutput implements EncoderOutput {

        private final List<EncodedAccessUnit> units = new ArrayList<>();

        @Override
        public void onAccessUnit(EncodedAccessUnit accessUnit) {
            units.add(accessUnit);
        }

        private List<EncodedAccessUnit> units() {
            return units;
        }
    }
}
