package com.obby.android.localscreenshare.core.client;

import android.view.Surface;

import com.obby.android.localscreenshare.core.codec.CodecIds;
import com.obby.android.localscreenshare.core.codec.CodecRegistration;
import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.codec.h264.H264Codec;
import com.obby.android.localscreenshare.core.metrics.PipelineMetrics;
import com.obby.android.localscreenshare.core.model.AccessUnitFlags;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.model.ClientState;
import com.obby.android.localscreenshare.core.model.CodecConfigPart;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.JoinInfo;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.render.EncodedImageFrame;
import com.obby.android.localscreenshare.core.render.RenderTarget;
import com.obby.android.localscreenshare.core.stream.ClientCapabilities;
import com.obby.android.localscreenshare.core.support.FakeMediaCodecBridgeFactory;
import com.obby.android.localscreenshare.core.support.FakeMediaCodecDecoderBridge;
import com.obby.android.localscreenshare.core.support.H264Fixtures;
import com.obby.android.localscreenshare.core.support.MutableNanoClock;
import com.obby.android.localscreenshare.core.support.TestH264Registration;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Catch-up tests for H.264 client sessions.
 *
 * <p>A catch-up is the deliberate skip the viewer takes when it fell behind: everything the
 * decoder holds is discarded and decoding resumes on the next validated join point. These tests
 * assert the two properties the media path depends on - the decoder is flushed exactly once and no
 * P frame reaches it before the join point.</p>
 */
public class ClientMediaSessionCatchUpTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session-7", "stream-7");

    private static final ClientSessionKey CLIENT_KEY =
            new ClientSessionKey(STREAM_KEY, "client-7");

    private static final int WIDTH = 640;

    private static final int HEIGHT = 480;

    @Test
    public void onCatchUp_flushesTheDecoderAndWaitsForAJoinPoint() {
        FakeMediaCodecBridgeFactory bridges = new FakeMediaCodecBridgeFactory();
        RecordingStateListener listener = new RecordingStateListener();
        ClientMediaSession session = newSession(bridges, listener);
        assertTrue(session.onDescriptor(h264Descriptor(2)));
        assertTrue(session.onAccessUnit(joinAu(2, 0)));
        assertTrue(session.onAccessUnit(ordinaryAu(2, 1)));
        FakeMediaCodecDecoderBridge decoder = bridges.getLastDecoder();
        assertEquals(2, decoder.getQueuedInputData().size());

        assertTrue(session.onCatchUp());

        assertEquals(ClientState.RESYNCING, session.getState());
        assertEquals(ClientMediaSession.ResyncReason.CATCH_UP, session.getLastResyncReason());
        assertEquals(1, decoder.getFlushCount());
        assertEquals(Arrays.asList("NEGOTIATING->WAIT_CONFIG", "WAIT_CONFIG->WAIT_RANDOM_ACCESS",
                "WAIT_RANDOM_ACCESS->STREAMING", "STREAMING->RESYNCING"),
                listener.getTransitions());

        // A reference frame cannot resume the stream: the policy rejects it before the decoder.
        assertFalse(session.onAccessUnit(ordinaryAu(2, 2)));
        assertEquals(2, decoder.getQueuedInputData().size());
        assertEquals(ClientState.RESYNCING, session.getState());

        // The join point resumes decoding on a picture whose references are complete.
        assertTrue(session.onAccessUnit(joinAu(2, 3)));
        assertEquals(ClientState.STREAMING, session.getState());
        assertEquals(3, decoder.getQueuedInputData().size());
    }

    @Test
    public void onCatchUp_withoutADecoder_reportsFalse() {
        FakeMediaCodecBridgeFactory bridges = new FakeMediaCodecBridgeFactory();
        ClientMediaSession session = newSession(bridges, null);

        assertFalse(session.onCatchUp());
        assertEquals(ClientState.NEGOTIATING, session.getState());
        assertNull(session.getLastResyncReason());
        assertTrue(bridges.getCreatedDecoders().isEmpty());

        session.close();
        assertFalse(session.onCatchUp());
    }

    @Test
    public void onCatchUp_countsAResyncEpisode() {
        MutableNanoClock clock = new MutableNanoClock();
        PipelineMetrics metrics = PipelineMetrics.create(clock, 8);
        FakeMediaCodecBridgeFactory bridges = new FakeMediaCodecBridgeFactory();
        ClientMediaSession session = newSession(bridges, null, metrics);
        assertTrue(session.onDescriptor(h264Descriptor(2)));
        assertTrue(session.onAccessUnit(joinAu(2, 0)));
        assertEquals(0L, metrics.count(PipelineMetrics.Counter.RESYNCS));

        assertTrue(session.onCatchUp());

        assertEquals(1L, metrics.count(PipelineMetrics.Counter.RESYNCS));
    }

    private ClientMediaSession newSession(FakeMediaCodecBridgeFactory bridges,
                                          ClientStateListener listener) {
        return newSession(bridges, listener, PipelineMetrics.NONE);
    }

    private ClientMediaSession newSession(FakeMediaCodecBridgeFactory bridges,
                                          ClientStateListener listener,
                                          PipelineMetrics metrics) {
        CodecRegistration registration = TestH264Registration.withoutSurfaceRequirement(bridges);
        CodecRegistry registry = new CodecRegistry();
        registry.register(registration);
        return new ClientMediaSession(CLIENT_KEY, registry,
                new ClientCapabilities(Collections.singleton(CodecIds.H264)),
                new NoSurfaceRenderTarget(), listener, metrics);
    }

    private static StreamDescriptor h264Descriptor(int configEpoch) {
        return StreamDescriptor.builder(STREAM_KEY, configEpoch, "h264", H264Codec.MIME,
                        BitstreamFormat.ANNEX_B)
                .codedSize(WIDTH, HEIGHT)
                .visibleSize(WIDTH, HEIGHT)
                .configParts(Arrays.asList(
                        new CodecConfigPart(H264Codec.CONFIG_PART_SPS, H264Fixtures.sps()),
                        new CodecConfigPart(H264Codec.CONFIG_PART_PPS, H264Fixtures.pps())))
                .build();
    }

    private static EncodedAccessUnit joinAu(int configEpoch, long sequence) {
        return new EncodedAccessUnit(STREAM_KEY, configEpoch, sequence, sequence * 1000L,
                EncodedAccessUnit.DTS_UNSET, AccessUnitFlags.of(true, true, false),
                new JoinInfo(configEpoch, Collections.emptyList()), null,
                H264Fixtures.annexB(H264Fixtures.idrSlice()));
    }

    private static EncodedAccessUnit ordinaryAu(int configEpoch, long sequence) {
        return new EncodedAccessUnit(STREAM_KEY, configEpoch, sequence, sequence * 1000L,
                EncodedAccessUnit.DTS_UNSET, AccessUnitFlags.none(), null, null,
                H264Fixtures.annexB(H264Fixtures.pSlice()));
    }

    private static final class NoSurfaceRenderTarget implements RenderTarget {

        @Override
        public Surface getSurface() {
            return null;
        }

        @Override
        public void setContentSize(int width, int height) {
        }

        @Override
        public void renderEncodedImage(EncodedImageFrame frame) {
        }
    }

    private static final class RecordingStateListener implements ClientStateListener {

        private final List<String> transitions = new ArrayList<>();

        @Override
        public void onStateChanged(ClientState previous, ClientState current) {
            transitions.add(previous + "->" + current);
        }

        List<String> getTransitions() {
            return Collections.unmodifiableList(transitions);
        }
    }
}
