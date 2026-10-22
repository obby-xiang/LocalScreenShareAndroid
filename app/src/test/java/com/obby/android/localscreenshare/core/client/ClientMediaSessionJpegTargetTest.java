package com.obby.android.localscreenshare.core.client;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.obby.android.localscreenshare.core.codec.CodecDescriptor;
import com.obby.android.localscreenshare.core.codec.CodecIds;
import com.obby.android.localscreenshare.core.codec.CodecPolicy;
import com.obby.android.localscreenshare.core.codec.CodecRegistration;
import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.codec.Decoder;
import com.obby.android.localscreenshare.core.codec.DecoderFactory;
import com.obby.android.localscreenshare.core.codec.Encoder;
import com.obby.android.localscreenshare.core.codec.EncoderFactory;
import com.obby.android.localscreenshare.core.codec.EncoderOutput;
import com.obby.android.localscreenshare.core.codec.OverflowAction;
import com.obby.android.localscreenshare.core.codec.jpeg.JpegCodec;
import com.obby.android.localscreenshare.core.model.AccessUnitFlags;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.model.ClientState;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.JoinInfo;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.model.StreamParameters;
import com.obby.android.localscreenshare.core.render.RenderTarget;
import com.obby.android.localscreenshare.core.stream.ClientCapabilities;
import com.obby.android.localscreenshare.core.support.FakeRenderTarget;
import com.obby.android.localscreenshare.core.support.JpegFixtures;
import com.obby.android.localscreenshare.render.HostRenderTarget;

import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests that drive a {@link ClientMediaSession} through a {@link HostRenderTarget}.
 *
 * <p>They cover the wiring a viewing session gets in production: the composed target is the one
 * stable target of the session, and the descriptor decides which half receives the media. A JPEG
 * stream must land in the bitmap half while the surface half stays untouched; a surface-output codec
 * must never reach the bitmap half, because its pixels go to the surface and never become encoded
 * image frames.</p>
 */
public class ClientMediaSessionJpegTargetTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session-1", "stream-1");

    private static final ClientSessionKey CLIENT_KEY =
        new ClientSessionKey(STREAM_KEY, "client-1");

    private static final String SURFACE_CODEC_ID = "surface-style";

    @Test
    public void jpegFrames_reachOnlyTheBitmapHalf() {
        FakeRenderTarget surfaceHalf = new FakeRenderTarget();
        FakeRenderTarget bitmapHalf = new FakeRenderTarget();
        ClientMediaSession session = new ClientMediaSession(CLIENT_KEY,
            CodecRegistry.withDefaults(),
            new ClientCapabilities(Collections.singleton(CodecIds.JPEG)),
            new HostRenderTarget(surfaceHalf, bitmapHalf), null);

        assertTrue(session.onDescriptor(jpegDescriptor(1)));
        assertTrue(session.onAccessUnit(jpegFrame(1, 0)));

        assertEquals(ClientState.STREAMING, session.getState());
        assertEquals(1, bitmapHalf.getImages().size());
        assertArrayEquals(JpegFixtures.REAL_JPEG, bitmapHalf.getImages().get(0).getPayload());
        assertTrue(surfaceHalf.getImages().isEmpty());
    }

    @Test
    public void surfaceCodec_neverTouchesTheBitmapHalf() {
        AtomicInteger decoded = new AtomicInteger();
        CodecRegistry registry = new CodecRegistry();
        registry.register(surfaceStyleRegistration(decoded));
        FakeRenderTarget surfaceHalf = new FakeRenderTarget();
        FakeRenderTarget bitmapHalf = new FakeRenderTarget();
        ClientMediaSession session = new ClientMediaSession(CLIENT_KEY, registry,
            new ClientCapabilities(Collections.singleton(SURFACE_CODEC_ID)),
            new HostRenderTarget(surfaceHalf, bitmapHalf), null);

        assertTrue(session.onDescriptor(surfaceDescriptor(1)));
        assertTrue(session.onAccessUnit(joinAccessUnit(1, 0)));

        assertEquals(ClientState.STREAMING, session.getState());
        assertEquals(1, decoded.get());
        assertTrue(bitmapHalf.getImages().isEmpty());
    }

    /**
     * Builds a codec whose decoder renders into its output surface and never produces an encoded
     * image, which is how H.264 behaves. It does not require a surface, so the test needs no real
     * one; the decoder deliberately reports its work through {@code decodedCount} instead.
     */
    private static CodecRegistration surfaceStyleRegistration(AtomicInteger decodedCount) {
        CodecDescriptor descriptor = new CodecDescriptor(SURFACE_CODEC_ID, "video/avc",
            BitstreamFormat.ANNEX_B, false, false, false);
        EncoderFactory encoderFactory = new EncoderFactory() {
            @Override
            public Encoder create(StreamKey streamKey, StreamDescriptor streamDescriptor,
                                  StreamParameters parameters, EncoderOutput output) {
                throw new UnsupportedOperationException("this test never creates an encoder");
            }
        };
        DecoderFactory decoderFactory = new DecoderFactory() {
            @Override
            public Decoder create(StreamKey streamKey, StreamDescriptor streamDescriptor,
                                  RenderTarget renderTarget) {
                return new Decoder() {
                    @Override
                    public void configure(StreamDescriptor descriptor) {
                    }

                    @Override
                    public void decode(EncodedAccessUnit accessUnit) {
                        decodedCount.incrementAndGet();
                    }

                    @Override
                    public void flush() {
                    }

                    @Override
                    public void close() {
                    }
                };
            }
        };
        return new CodecRegistration(descriptor, encoderFactory, decoderFactory,
            new CodecPolicy() {
                @Override
                public OverflowAction getOverflowAction() {
                    return OverflowAction.DROP_UNTIL_JOIN_POINT;
                }

                @Override
                public boolean isJoinPoint(EncodedAccessUnit accessUnit) {
                    return accessUnit != null && accessUnit.getFlags().isJoinPoint();
                }

                @Override
                public boolean requiresReconfigure(StreamDescriptor previous,
                                                   StreamDescriptor next) {
                    return previous == null || next == null || !previous.sameDecodeConfig(next);
                }
            }, 0);
    }

    private static StreamDescriptor jpegDescriptor(int configEpoch) {
        return StreamDescriptor.builder(STREAM_KEY, configEpoch, CodecIds.JPEG, JpegCodec.MIME,
                BitstreamFormat.JPEG_FRAME)
            .codedSize(64, 48)
            .visibleSize(48, 36)
            .build();
    }

    private static StreamDescriptor surfaceDescriptor(int configEpoch) {
        return StreamDescriptor.builder(STREAM_KEY, configEpoch, SURFACE_CODEC_ID, "video/avc",
                BitstreamFormat.ANNEX_B)
            .codedSize(64, 48)
            .visibleSize(64, 48)
            .build();
    }

    private static EncodedAccessUnit jpegFrame(int configEpoch, long sequence) {
        return accessUnit(configEpoch, sequence, JpegFixtures.REAL_JPEG);
    }

    private static EncodedAccessUnit joinAccessUnit(int configEpoch, long sequence) {
        return accessUnit(configEpoch, sequence, new byte[]{0x0A, 0x0B});
    }

    private static EncodedAccessUnit accessUnit(int configEpoch, long sequence, byte[] payload) {
        return new EncodedAccessUnit(STREAM_KEY, configEpoch, sequence, sequence * 1000L,
            EncodedAccessUnit.DTS_UNSET, AccessUnitFlags.of(true, true, false),
            new JoinInfo(configEpoch, Collections.emptyList()), null, payload);
    }
}
