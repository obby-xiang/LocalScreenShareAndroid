package com.obby.android.localscreenshare.core.client;

import com.obby.android.localscreenshare.core.codec.CodecIds;
import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.codec.jpeg.JpegCodec;
import com.obby.android.localscreenshare.core.model.AccessUnitFlags;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.model.ClientState;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.JoinInfo;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.render.EncodedImageFrame;
import com.obby.android.localscreenshare.core.stream.ClientCapabilities;
import com.obby.android.localscreenshare.core.support.FakeCodec;
import com.obby.android.localscreenshare.core.support.FakeRenderTarget;
import com.obby.android.localscreenshare.core.support.JpegFixtures;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ClientMediaSessionTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session-1", "stream-1");

    private static final ClientSessionKey CLIENT_KEY =
            new ClientSessionKey(STREAM_KEY, "client-1");

    private static final int CODED_WIDTH = 64;

    private static final int CODED_HEIGHT = 48;

    private FakeCodec codec;

    private CodecRegistry registry;

    private ClientCapabilities capabilities;

    private FakeRenderTarget renderTarget;

    private List<String> transitions;

    @Before
    public void setUp() {
        codec = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        registry = new CodecRegistry();
        registry.register(codec.registration());
        capabilities = new ClientCapabilities(Collections.singleton(FakeCodec.DEFAULT_CODEC_ID));
        renderTarget = new FakeRenderTarget();
        transitions = new ArrayList<>();
    }

    @Test
    public void newSession_isNegotiating() {
        ClientMediaSession session = newSession();

        assertEquals(ClientState.NEGOTIATING, session.getState());
        assertNull(session.getCurrentDescriptor());
        assertFalse(session.isClosed());
        assertTrue(transitions.isEmpty());
        assertEquals(0, codec.getDecoderCreateCount());
        assertTrue(renderTarget.getImages().isEmpty());
    }

    @Test
    public void onDescriptor_entersWaitConfigThenWaitRandomAccess() {
        ClientMediaSession session = newSession();
        StreamDescriptor descriptor = fakeDescriptor(1, CODED_WIDTH, CODED_HEIGHT);

        assertTrue(session.onDescriptor(descriptor));

        assertEquals(ClientState.WAIT_RANDOM_ACCESS, session.getState());
        assertDescriptor(descriptor, session.getCurrentDescriptor());
        FakeCodec.FakeDecoder decoder = codec.getLastDecoder();
        assertNotNull(decoder);
        assertEquals(1, codec.getDecoderCreateCount());
        assertEquals(1, decoder.getConfigureCount());
        assertDescriptor(descriptor, decoder.getDescriptor());
        assertSame(renderTarget, decoder.getRenderTarget());
        assertEquals(Arrays.asList(
                "NEGOTIATING->WAIT_CONFIG",
                "WAIT_CONFIG->WAIT_RANDOM_ACCESS"), transitions);
    }

    @Test
    public void emptyConfigJpegDescriptor_isAccepted() {
        ClientMediaSession session = newSession(
                CodecRegistry.withDefaults(),
                new ClientCapabilities(Collections.singleton(CodecIds.JPEG)));
        StreamDescriptor descriptor = jpegDescriptor(1);
        assertTrue(descriptor.getConfigParts().isEmpty());

        assertTrue(session.onDescriptor(descriptor));
        assertEquals(ClientState.WAIT_RANDOM_ACCESS, session.getState());

        assertTrue(session.onAccessUnit(au(1, 0, true, JpegFixtures.REAL_JPEG)));
        assertEquals(ClientState.STREAMING, session.getState());
        assertEquals(1, renderTarget.getImages().size());
        EncodedImageFrame image = renderTarget.getImages().get(0);
        assertArrayEquals(JpegFixtures.REAL_JPEG, image.getPayload());
        assertEquals(48, image.getWidth());
        assertEquals(36, image.getHeight());
    }

    @Test
    public void unsupportedCodec_closesSession() {
        ClientCapabilities otherCapabilities =
                new ClientCapabilities(Collections.singleton("other-codec"));
        ClientMediaSession session = newSession(registry, otherCapabilities);

        session.onDescriptor(fakeDescriptor(1, CODED_WIDTH, CODED_HEIGHT));

        assertEquals(ClientState.CLOSED, session.getState());
        assertTrue(session.isClosed());
        assertEquals(0, codec.getDecoderCreateCount());
        assertNull(codec.getLastDecoder());
        assertEquals(Collections.singletonList("NEGOTIATING->CLOSED"), transitions);
    }

    @Test
    public void surfaceRequiredButRenderTargetHasNoSurface_entersResyncing() {
        FakeCodec surfaceCodec = new FakeCodec("surface-codec", 0, false, true);
        CodecRegistry surfaceRegistry = new CodecRegistry();
        surfaceRegistry.register(surfaceCodec.registration());
        ClientCapabilities surfaceCapabilities =
                new ClientCapabilities(Collections.singleton("surface-codec"));
        assertNull(renderTarget.getSurface());
        ClientMediaSession session = newSession(surfaceRegistry, surfaceCapabilities);

        session.onDescriptor(descriptor("surface-codec", 1, CODED_WIDTH, CODED_HEIGHT));

        assertEquals(ClientState.RESYNCING, session.getState());
        assertFalse(session.isClosed());
        assertEquals(0, surfaceCodec.getDecoderCreateCount());
        assertNull(surfaceCodec.getLastDecoder());
        // Blocked before applyDescriptor, so the display hint is not dispatched either.
        assertTrue(renderTarget.getFrameRates().isEmpty());
    }

    @Test
    public void nonJoinFrameInWaitRandomAccessIsDropped() {
        ClientMediaSession session = newSession();
        session.onDescriptor(fakeDescriptor(1, CODED_WIDTH, CODED_HEIGHT));
        FakeCodec.FakeDecoder decoder = codec.getLastDecoder();

        assertFalse(session.onAccessUnit(au(1, 0, false, new byte[]{0x01})));

        assertEquals(ClientState.WAIT_RANDOM_ACCESS, session.getState());
        assertTrue(decoder.getDecoded().isEmpty());
        assertTrue(renderTarget.getImages().isEmpty());
    }

    @Test
    public void matchingJoinPoint_transitionsToStreamingAndRendersImage() {
        ClientMediaSession session = newSession();
        session.onDescriptor(fakeDescriptor(1, CODED_WIDTH, CODED_HEIGHT));
        FakeCodec.FakeDecoder decoder = codec.getLastDecoder();

        EncodedAccessUnit join = au(1, 0, true, new byte[]{0x0A, 0x0B});
        assertTrue(session.onAccessUnit(join));

        assertEquals(ClientState.STREAMING, session.getState());
        assertEquals(1, decoder.getDecoded().size());
        assertEquals(1, renderTarget.getImages().size());
        EncodedImageFrame image = renderTarget.getImages().get(0);
        assertArrayEquals(join.getPayload(), image.getPayload());
        assertEquals(join.getPtsUs(), image.getPtsUs());
        assertEquals(CODED_WIDTH, image.getWidth());
        assertEquals(CODED_HEIGHT, image.getHeight());

        assertTrue(session.onAccessUnit(au(1, 1, false, new byte[]{0x0C})));
        assertEquals(ClientState.STREAMING, session.getState());
        assertEquals(2, renderTarget.getImages().size());
    }

    @Test
    public void staleEpochOrdinaryAu_isDroppedWithoutStateChange() {
        ClientMediaSession session = newSession();
        session.onDescriptor(fakeDescriptor(2, CODED_WIDTH, CODED_HEIGHT));
        assertTrue(session.onAccessUnit(au(2, 0, true, new byte[]{0x01})));
        assertEquals(ClientState.STREAMING, session.getState());
        FakeCodec.FakeDecoder decoder = codec.getLastDecoder();

        assertFalse(session.onAccessUnit(au(1, 0, false, new byte[]{0x02})));

        assertEquals(ClientState.STREAMING, session.getState());
        assertEquals(1, decoder.getDecoded().size());
        assertEquals(1, renderTarget.getImages().size());
    }

    @Test
    public void futureEpochOrdinaryAu_entersResyncing() {
        ClientMediaSession session = newSession();
        session.onDescriptor(fakeDescriptor(1, CODED_WIDTH, CODED_HEIGHT));
        assertTrue(session.onAccessUnit(au(1, 0, true, new byte[]{0x01})));
        assertEquals(ClientState.STREAMING, session.getState());

        assertFalse(session.onAccessUnit(au(2, 0, false, new byte[]{0x02})));

        assertEquals(ClientState.RESYNCING, session.getState());
        assertEquals(1, renderTarget.getImages().size());

        assertFalse(session.onAccessUnit(au(2, 1, true, new byte[]{0x03})));
        assertEquals(ClientState.RESYNCING, session.getState());
        assertEquals(1, renderTarget.getImages().size());
    }

    @Test
    public void duplicateDescriptor_isIgnored() {
        ClientMediaSession session = newSession();
        assertTrue(session.onDescriptor(fakeDescriptor(1, CODED_WIDTH, CODED_HEIGHT)));
        FakeCodec.FakeDecoder decoder = codec.getLastDecoder();
        int transitionCount = transitions.size();

        assertFalse(session.onDescriptor(fakeDescriptor(1, CODED_WIDTH, CODED_HEIGHT)));

        assertEquals(ClientState.WAIT_RANDOM_ACCESS, session.getState());
        assertEquals(transitionCount, transitions.size());
        assertEquals(1, codec.getDecoderCreateCount());
        assertSame(decoder, codec.getLastDecoder());
    }

    @Test
    public void onDescriptor_jpegDoesNotSetFrameRate() {
        ClientMediaSession session = newSession(
                CodecRegistry.withDefaults(),
                new ClientCapabilities(Collections.singleton(CodecIds.JPEG)));
        StreamDescriptor descriptor = StreamDescriptor.builder(STREAM_KEY, 1, CodecIds.JPEG,
                        JpegCodec.MIME, BitstreamFormat.JPEG_FRAME)
                .codedSize(CODED_WIDTH, CODED_HEIGHT)
                .visibleSize(48, 36)
                .frameRate(60)
                .build();

        assertTrue(session.onDescriptor(descriptor));

        assertEquals(ClientState.WAIT_RANDOM_ACCESS, session.getState());
        assertTrue(renderTarget.getFrameRates().isEmpty());
    }

    @Test
    public void sameEpochDifferentConfigThrows() {
        ClientMediaSession session = newSession();
        assertTrue(session.onDescriptor(fakeDescriptor(1, CODED_WIDTH, CODED_HEIGHT)));
        FakeCodec.FakeDecoder decoder = codec.getLastDecoder();
        StreamDescriptor conflicting = fakeDescriptor(1, 32, 24);

        assertThrows(IllegalArgumentException.class, () -> session.onDescriptor(conflicting));

        assertEquals(ClientState.WAIT_RANDOM_ACCESS, session.getState());
        assertEquals(1, codec.getDecoderCreateCount());
        assertSame(decoder, codec.getLastDecoder());
    }

    @Test
    public void newDescriptor_recreatesDecoderAndWaitsForNewJoinPoint() {
        ClientMediaSession session = newSession();
        assertTrue(session.onDescriptor(fakeDescriptor(1, CODED_WIDTH, CODED_HEIGHT)));
        FakeCodec.FakeDecoder firstDecoder = codec.getLastDecoder();
        assertTrue(session.onAccessUnit(au(1, 0, true, new byte[]{0x01})));
        assertEquals(ClientState.STREAMING, session.getState());
        assertEquals(1, renderTarget.getImages().size());

        StreamDescriptor next = fakeDescriptor(2, 32, 24);
        assertTrue(session.onDescriptor(next));

        FakeCodec.FakeDecoder secondDecoder = codec.getLastDecoder();
        assertNotSame(firstDecoder, secondDecoder);
        assertTrue(firstDecoder.isClosed());
        assertEquals(1, firstDecoder.getCloseCount());
        assertEquals(2, codec.getDecoderCreateCount());
        assertEquals(1, secondDecoder.getConfigureCount());
        assertDescriptor(next, secondDecoder.getDescriptor());
        assertEquals(ClientState.WAIT_RANDOM_ACCESS, session.getState());
        assertDescriptor(next, session.getCurrentDescriptor());
        assertEquals(1, renderTarget.getImages().size());

        assertFalse(session.onAccessUnit(au(2, 0, false, new byte[]{0x02})));
        assertEquals(ClientState.WAIT_RANDOM_ACCESS, session.getState());
        assertEquals(1, renderTarget.getImages().size());

        assertTrue(session.onAccessUnit(au(2, 1, true, new byte[]{0x03})));
        assertEquals(ClientState.STREAMING, session.getState());
        assertEquals(2, renderTarget.getImages().size());
    }

    @Test
    public void decoderFailure_entersResyncingAndRecoversAtJoinPoint() {
        ClientMediaSession session = newSession();
        session.onDescriptor(fakeDescriptor(1, CODED_WIDTH, CODED_HEIGHT));
        assertTrue(session.onAccessUnit(au(1, 0, true, new byte[]{0x01})));
        assertEquals(ClientState.STREAMING, session.getState());
        FakeCodec.FakeDecoder decoder = codec.getLastDecoder();
        decoder.failNextDecode();

        assertFalse(session.onAccessUnit(au(1, 1, false, new byte[]{0x02})));

        assertEquals(ClientState.RESYNCING, session.getState());
        assertEquals(1, renderTarget.getImages().size());
        assertEquals(1, decoder.getDecoded().size());

        assertFalse(session.onAccessUnit(au(1, 2, false, new byte[]{0x03})));
        assertEquals(ClientState.RESYNCING, session.getState());
        assertEquals(1, renderTarget.getImages().size());

        assertTrue(session.onAccessUnit(au(1, 3, true, new byte[]{0x04})));
        assertEquals(ClientState.STREAMING, session.getState());
        assertEquals(2, renderTarget.getImages().size());
        assertEquals(2, decoder.getDecoded().size());
        assertSame(decoder, codec.getLastDecoder());
    }

    @Test
    public void close_isIdempotentAndClosesDecoderButNotRenderTarget() {
        ClientMediaSession session = newSession();
        session.onDescriptor(fakeDescriptor(1, CODED_WIDTH, CODED_HEIGHT));
        FakeCodec.FakeDecoder decoder = codec.getLastDecoder();

        session.close();

        assertEquals(ClientState.CLOSED, session.getState());
        assertTrue(session.isClosed());
        assertTrue(decoder.isClosed());
        assertEquals(1, decoder.getCloseCount());
        assertEquals(0, renderTarget.getCloseCount());
        int transitionCount = transitions.size();

        session.close();

        assertEquals(ClientState.CLOSED, session.getState());
        assertEquals(1, decoder.getCloseCount());
        assertEquals(0, renderTarget.getCloseCount());
        assertEquals(transitionCount, transitions.size());
    }

    @Test
    public void stateListener_receivesOnlyDistinctTransitions() {
        ClientMediaSession session = newSession();
        session.onDescriptor(fakeDescriptor(1, CODED_WIDTH, CODED_HEIGHT));
        session.onDescriptor(fakeDescriptor(1, CODED_WIDTH, CODED_HEIGHT));
        session.onAccessUnit(au(1, 0, true, new byte[]{0x01}));
        session.onAccessUnit(au(2, 0, false, new byte[]{0x02}));
        session.onAccessUnit(au(1, 1, true, new byte[]{0x03}));
        session.close();
        session.close();

        assertEquals(Arrays.asList(
                "NEGOTIATING->WAIT_CONFIG",
                "WAIT_CONFIG->WAIT_RANDOM_ACCESS",
                "WAIT_RANDOM_ACCESS->STREAMING",
                "STREAMING->RESYNCING",
                "RESYNCING->STREAMING",
                "STREAMING->CLOSED"), transitions);
        for (String transition : transitions) {
            String[] states = transition.split("->");
            assertNotEquals(states[0], states[1]);
        }
    }

    private ClientMediaSession newSession() {
        return newSession(registry, capabilities);
    }

    private ClientMediaSession newSession(CodecRegistry sessionRegistry,
                                          ClientCapabilities sessionCapabilities) {
        return new ClientMediaSession(CLIENT_KEY, sessionRegistry, sessionCapabilities,
                renderTarget, new ClientStateListener() {
                    @Override
                    public void onStateChanged(ClientState previous, ClientState current) {
                        transitions.add(previous.name() + "->" + current.name());
                    }
                });
    }

    private static StreamDescriptor fakeDescriptor(int configEpoch, int visibleWidth,
                                                   int visibleHeight) {
        return descriptor(FakeCodec.DEFAULT_CODEC_ID, configEpoch, visibleWidth, visibleHeight);
    }

    private static StreamDescriptor descriptor(String codecId, int configEpoch, int visibleWidth,
                                               int visibleHeight) {
        return StreamDescriptor.builder(STREAM_KEY, configEpoch, codecId,
                        FakeCodec.DEFAULT_MIME, BitstreamFormat.ANNEX_B)
                .codedSize(CODED_WIDTH, CODED_HEIGHT)
                .visibleSize(visibleWidth, visibleHeight)
                .build();
    }

    private static StreamDescriptor jpegDescriptor(int configEpoch) {
        return StreamDescriptor.builder(STREAM_KEY, configEpoch, CodecIds.JPEG, JpegCodec.MIME,
                        BitstreamFormat.JPEG_FRAME)
                .codedSize(CODED_WIDTH, CODED_HEIGHT)
                .visibleSize(48, 36)
                .build();
    }

    private static EncodedAccessUnit au(int configEpoch, long sequence, boolean joinPoint,
                                        byte[] payload) {
        AccessUnitFlags flags = joinPoint
                ? AccessUnitFlags.of(true, true, false)
                : AccessUnitFlags.none();
        JoinInfo joinInfo = joinPoint ? new JoinInfo(configEpoch, Collections.emptyList()) : null;
        return new EncodedAccessUnit(STREAM_KEY, configEpoch, sequence, sequence * 1000L,
                EncodedAccessUnit.DTS_UNSET, flags, joinInfo, null, payload);
    }

    private static void assertDescriptor(StreamDescriptor expected, StreamDescriptor actual) {
        assertNotNull(actual);
        assertEquals(expected.getConfigEpoch(), actual.getConfigEpoch());
        assertEquals(expected.getCodecId(), actual.getCodecId());
        assertEquals(expected.getMime(), actual.getMime());
        assertEquals(expected.getCodedWidth(), actual.getCodedWidth());
        assertEquals(expected.getCodedHeight(), actual.getCodedHeight());
        assertEquals(expected.getVisibleWidth(), actual.getVisibleWidth());
        assertEquals(expected.getVisibleHeight(), actual.getVisibleHeight());
        assertEquals(expected.getConfigHash(), actual.getConfigHash());
    }
}
