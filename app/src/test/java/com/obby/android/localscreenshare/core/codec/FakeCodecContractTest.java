package com.obby.android.localscreenshare.core.codec;

import com.obby.android.localscreenshare.core.model.AccessUnitFlags;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.model.StreamParameters;
import com.obby.android.localscreenshare.core.render.EncodedImageFrame;
import com.obby.android.localscreenshare.core.support.FakeCodec;
import com.obby.android.localscreenshare.core.support.FakeRenderTarget;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class FakeCodecContractTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session-instance-1", "stream-1");

    private static StreamDescriptor descriptor() {
        return StreamDescriptor
                .builder(STREAM_KEY, 1, FakeCodec.DEFAULT_CODEC_ID, FakeCodec.DEFAULT_MIME,
                        BitstreamFormat.ANNEX_B)
                .codedSize(320, 240)
                .build();
    }

    private static EncodedAccessUnit nonJoinAccessUnit() {
        return new EncodedAccessUnit(STREAM_KEY, 1, 0L, 0L, EncodedAccessUnit.DTS_UNSET,
                AccessUnitFlags.none(), null, null, new byte[]{1, 2, 3});
    }

    private static FakeCodec.FakeEncoder createEncoder(FakeCodec codec,
                                                       List<EncodedAccessUnit> emitted) {
        Encoder encoder = codec.registration().getEncoderFactory()
                .create(STREAM_KEY, descriptor(), StreamParameters.DEFAULT, emitted::add);
        assertNotNull(codec.getLastEncoder());
        assertSame(encoder, codec.getLastEncoder());
        return codec.getLastEncoder();
    }

    @Test
    public void fakeRenderTarget_surfaceIsNull_andCapturesImages() {
        FakeRenderTarget target = new FakeRenderTarget();

        assertNull(target.getSurface());
        assertTrue(target.getImages().isEmpty());

        target.renderEncodedImage(new EncodedImageFrame(new byte[]{9, 8, 7}, 42L, 32, 16));

        assertEquals(1, target.getImages().size());
        EncodedImageFrame frame = target.getImages().get(0);
        assertEquals(42L, frame.getPtsUs());
        assertEquals(32, frame.getWidth());
        assertEquals(16, frame.getHeight());
        assertEquals(3, frame.getPayloadLength());
        assertArrayEquals(new byte[]{9, 8, 7}, frame.getPayload());
    }

    @Test
    public void fakeEncoder_getInputSurfaceIsNull() {
        FakeCodec codec = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        List<EncodedAccessUnit> emitted = new ArrayList<>();

        FakeCodec.FakeEncoder encoder = createEncoder(codec, emitted);

        assertNull(encoder.getInputSurface());
    }

    @Test
    public void decoderFactory_receivesRenderTarget() {
        FakeCodec codec = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        FakeRenderTarget target = new FakeRenderTarget();
        StreamDescriptor descriptor = descriptor();

        Decoder decoder = codec.registration().getDecoderFactory()
                .create(STREAM_KEY, descriptor, target);
        assertNotNull(codec.getLastDecoder());
        decoder.configure(descriptor);
        decoder.decode(nonJoinAccessUnit());

        assertEquals(1, target.getImages().size());
        EncodedImageFrame frame = target.getImages().get(0);
        assertEquals(3, frame.getPayloadLength());
        assertEquals(0L, frame.getPtsUs());
        assertEquals(320, frame.getWidth());
        assertEquals(240, frame.getHeight());
    }

    @Test
    public void decoder_decodeFailureUsesDecoderException() {
        FakeCodec codec = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        FakeRenderTarget target = new FakeRenderTarget();
        StreamDescriptor descriptor = descriptor();
        Decoder decoder = codec.registration().getDecoderFactory()
                .create(STREAM_KEY, descriptor, target);
        decoder.configure(descriptor);
        codec.getLastDecoder().failNextDecode();

        assertThrows(DecoderException.class, () -> decoder.decode(nonJoinAccessUnit()));
        assertTrue(target.getImages().isEmpty());
    }

    @Test
    public void fallbackPriority_isMetadataOnly() {
        CodecRegistry registry = new CodecRegistry();
        FakeCodec preferred = new FakeCodec("zeta", 0, false, false);
        FakeCodec fallback = new FakeCodec("alpha", 10, false, false);
        registry.register(preferred.registration());
        registry.register(fallback.registration());

        List<CodecRegistration> registrations = registry.getRegistrations();

        assertEquals(2, registrations.size());
        assertTrue(registrations.contains(preferred.registration()));
        assertTrue(registrations.contains(fallback.registration()));
        assertEquals(0, registry.require("zeta").getFallbackPriority());
        assertEquals(10, registry.require("alpha").getFallbackPriority());
        assertEquals(2, registry.getCodecIds().size());
        assertTrue(registry.getCodecIds().contains("zeta"));
        assertTrue(registry.getCodecIds().contains("alpha"));
    }

    @Test
    public void requestSyncPoint_emitsJoinPointAndCountsRequest() {
        FakeCodec codec = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        List<EncodedAccessUnit> emitted = new ArrayList<>();
        FakeCodec.FakeEncoder encoder = createEncoder(codec, emitted);

        encoder.requestSyncPoint();

        assertEquals(1, codec.getRequestCount());
        assertEquals(1, emitted.size());
        EncodedAccessUnit accessUnit = emitted.get(0);
        assertTrue(accessUnit.getFlags().isJoinPoint());
        assertEquals(1, accessUnit.getConfigEpoch());
        assertEquals(0L, accessUnit.getSequence());
        assertNotNull(accessUnit.getJoinInfo());
        assertEquals(1, accessUnit.getJoinInfo().getConfigEpoch());
    }

    @Test
    public void requestSyncPoint_autoEmitCanBeDisabled() {
        FakeCodec codec = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        codec.setAutoEmitJoinOnRequest(false);
        List<EncodedAccessUnit> emitted = new ArrayList<>();
        FakeCodec.FakeEncoder encoder = createEncoder(codec, emitted);

        encoder.requestSyncPoint();

        assertEquals(1, codec.getRequestCount());
        assertTrue(emitted.isEmpty());
    }

    @Test
    public void emit_usesDescriptorEpochAndResetsSequencePerEpoch() {
        FakeCodec codec = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        List<EncodedAccessUnit> emitted = new ArrayList<>();
        createEncoder(codec, emitted);

        codec.emitNonJoin(new byte[]{1});
        codec.emitNonJoin(new byte[]{2});
        codec.emit(2, true, new byte[]{3});
        codec.emit(2, false, new byte[]{4});

        assertEquals(4, emitted.size());
        assertEquals(1, emitted.get(0).getConfigEpoch());
        assertEquals(0L, emitted.get(0).getSequence());
        assertEquals(1L, emitted.get(1).getSequence());
        assertEquals(2, emitted.get(2).getConfigEpoch());
        assertEquals(0L, emitted.get(2).getSequence());
        assertTrue(emitted.get(2).getFlags().isJoinPoint());
        assertEquals(2, emitted.get(3).getConfigEpoch());
        assertEquals(1L, emitted.get(3).getSequence());
    }
}
