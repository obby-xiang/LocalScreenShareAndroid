package com.obby.android.localscreenshare.core.client;

import android.view.Surface;

import com.obby.android.localscreenshare.core.codec.CodecIds;
import com.obby.android.localscreenshare.core.codec.CodecRegistration;
import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.codec.Decoder;
import com.obby.android.localscreenshare.core.codec.DecoderFactory;
import com.obby.android.localscreenshare.core.codec.h264.H264Codec;
import com.obby.android.localscreenshare.core.codec.h264.MediaCodecBridgeFactory;
import com.obby.android.localscreenshare.core.codec.h264.MediaCodecDecoderBridge;
import com.obby.android.localscreenshare.core.codec.h264.MediaCodecEncoderBridge;
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
import com.obby.android.localscreenshare.core.support.FakeMediaCodecDecoderBridge;
import com.obby.android.localscreenshare.core.support.H264Fixtures;
import com.obby.android.localscreenshare.core.support.TestH264Registration;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Recovery-path tests for H.264 client sessions.
 *
 * <p>The JVM cannot create a {@code Surface}. Tests that must create a decoder use
 * {@link TestH264Registration}, whose two surface flags are both false; tests about a missing
 * Surface use the real registration and a render target that returns {@code null}. Neither
 * proves real Surface availability or availability changes.</p>
 */
public class ClientMediaSessionRecoveryTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session-6", "stream-6");

    private static final ClientSessionKey CLIENT_KEY =
            new ClientSessionKey(STREAM_KEY, "client-6");

    private static final int WIDTH = 640;

    private static final int HEIGHT = 480;

    @Test
    public void surfaceRequiredButMissing_entersResyncingAndCreatesNoDecoder() {
        ScriptedBridgeFactory bridges = new ScriptedBridgeFactory();
        CountingRenderTarget target = new CountingRenderTarget();
        ClientMediaSession session = newSession(H264Codec.registration(bridges), target);

        assertFalse(session.onDescriptor(h264Descriptor(2, WIDTH, HEIGHT)));

        assertEquals(ClientState.RESYNCING, session.getState());
        assertTrue(bridges.getDecoders().isEmpty());
        assertEquals(1, target.getSurfaceReads());
    }

    @Test
    public void surfaceRequiredButMissing_recordsLatestDescriptorAndPolicy() {
        ScriptedBridgeFactory bridges = new ScriptedBridgeFactory();
        CountingRenderTarget target = new CountingRenderTarget();
        ClientMediaSession session = newSession(H264Codec.registration(bridges), target);
        StreamDescriptor descriptor = h264Descriptor(2, WIDTH, HEIGHT);

        assertFalse(session.onDescriptor(descriptor));

        assertEquals(ClientState.RESYNCING, session.getState());
        assertSameDescriptor(descriptor, session.getCurrentDescriptor());
        assertFalse(session.onAccessUnit(joinAu(2, 0)));
        assertEquals(ClientState.RESYNCING, session.getState());
        assertTrue(bridges.getDecoders().isEmpty());
        assertEquals(1, target.getSurfaceReads());
    }

    @Test
    public void retryConfigure_withMissingSurface_staysResyncing() {
        ScriptedBridgeFactory bridges = new ScriptedBridgeFactory();
        CountingRenderTarget target = new CountingRenderTarget();
        ClientMediaSession session = newSession(H264Codec.registration(bridges), target);
        session.onDescriptor(h264Descriptor(2, WIDTH, HEIGHT));

        assertFalse(session.retryConfigure());

        assertEquals(ClientState.RESYNCING, session.getState());
        assertTrue(bridges.getDecoders().isEmpty());
    }

    @Test
    public void surfaceMissingAcrossEpochs_keepsLatestDescriptorAndRetryStaysResyncing() {
        ScriptedBridgeFactory bridges = new ScriptedBridgeFactory();
        CountingRenderTarget target = new CountingRenderTarget();
        ClientMediaSession session = newSession(H264Codec.registration(bridges), target);
        StreamDescriptor first = h264Descriptor(2, WIDTH, HEIGHT);
        StreamDescriptor next = h264Descriptor(3, WIDTH, HEIGHT);

        assertFalse(session.onDescriptor(first));
        assertEquals(ClientState.RESYNCING, session.getState());
        assertEquals(2, session.getCurrentDescriptor().getConfigEpoch());

        assertFalse(session.onDescriptor(next));

        assertEquals(ClientState.RESYNCING, session.getState());
        assertSameDescriptor(next, session.getCurrentDescriptor());
        assertEquals(3, session.getCurrentDescriptor().getConfigEpoch());
        assertFalse(session.retryConfigure());
        assertEquals(ClientState.RESYNCING, session.getState());
        assertTrue(bridges.getDecoders().isEmpty());
        assertTrue(session.getCurrentDescriptor().getConfigHash()
                .equals(next.getConfigHash()));
    }

    @Test
    public void configureFailureAcrossEpochs_retryUsesLatestEpoch() {
        ScriptedBridgeFactory bridges = new ScriptedBridgeFactory();
        bridges.failNextConfigure(new RuntimeException("configure E2 failed"));
        bridges.failNextConfigure(new RuntimeException("configure E3 failed"));
        CodecRegistration base = TestH264Registration.withoutSurfaceRequirement(bridges);
        RecordingDecoderFactory recorder = new RecordingDecoderFactory(
                base.getDecoderFactory());
        CodecRegistration registration = new CodecRegistration(base.getDescriptor(),
                base.getEncoderFactory(), recorder, base.getPolicy(),
                base.getFallbackPriority());
        CountingRenderTarget target = new CountingRenderTarget();
        ClientMediaSession session = newSession(registration, target);
        StreamDescriptor first = h264Descriptor(2, WIDTH, HEIGHT);
        StreamDescriptor next = h264Descriptor(3, WIDTH, HEIGHT);

        assertFalse(registration.getDescriptor().isSurfaceInputRequired());
        assertFalse(registration.getDescriptor().isSurfaceOutputRequired());
        assertTrue(registration.getDescriptor().hasConfigParts());

        assertFalse(session.onDescriptor(first));
        assertEquals(ClientState.RESYNCING, session.getState());
        assertFalse(session.onDescriptor(next));
        assertEquals(ClientState.RESYNCING, session.getState());

        assertTrue(session.retryConfigure());

        assertEquals(ClientState.WAIT_RANDOM_ACCESS, session.getState());
        assertEquals(3, recorder.getDescriptors().size());
        assertEquals(3, recorder.getDescriptors().get(2).getConfigEpoch());
        assertSameDescriptor(next, recorder.getDescriptors().get(2));
        assertEquals(3, bridges.getDecoders().size());
        assertEquals(1, bridges.getDecoders().get(2).getConfigureCount());
        assertEquals(1, bridges.getDecoders().get(2).getStartCount());
        assertEquals(1, bridges.getDecoders().get(0).getReleaseCount());
        assertEquals(1, bridges.getDecoders().get(1).getReleaseCount());
    }

    @Test
    public void configureFailure_thenRetry_succeedsAndWaitsRandomAccess() {
        ScriptedBridgeFactory bridges = new ScriptedBridgeFactory();
        bridges.failNextConfigure(new RuntimeException("configure failed"));
        CodecRegistration base = TestH264Registration.withoutSurfaceRequirement(bridges);
        RecordingDecoderFactory recorder = new RecordingDecoderFactory(
                base.getDecoderFactory());
        CodecRegistration registration = new CodecRegistration(base.getDescriptor(),
                base.getEncoderFactory(), recorder, base.getPolicy(),
                base.getFallbackPriority());
        ClientMediaSession session = newSession(registration, new CountingRenderTarget());
        StreamDescriptor descriptor = h264Descriptor(2, WIDTH, HEIGHT);

        assertFalse(session.onDescriptor(descriptor));
        assertEquals(ClientState.RESYNCING, session.getState());
        assertEquals(1, bridges.getDecoders().size());
        assertEquals(1, bridges.getDecoders().get(0).getReleaseCount());

        assertTrue(session.retryConfigure());

        assertEquals(ClientState.WAIT_RANDOM_ACCESS, session.getState());
        assertEquals(2, bridges.getDecoders().size());
        assertEquals(1, bridges.getDecoders().get(1).getConfigureCount());
        assertEquals(1, bridges.getDecoders().get(1).getStartCount());
        assertEquals(2, recorder.getDescriptors().size());
        assertSameDescriptor(descriptor, recorder.getDescriptors().get(1));
    }

    @Test
    public void retryConfigure_closesFailedDecoderOnce() {
        ScriptedBridgeFactory bridges = new ScriptedBridgeFactory();
        CodecRegistration registration = TestH264Registration.withoutSurfaceRequirement(bridges);
        ClientMediaSession session = newSession(registration, new CountingRenderTarget());
        assertTrue(session.onDescriptor(h264Descriptor(2, WIDTH, HEIGHT)));
        assertTrue(session.onAccessUnit(joinAu(2, 0)));
        FakeMediaCodecDecoderBridge failed = bridges.getDecoders().get(0);
        failed.failNextDequeue(new RuntimeException("dequeue failed"));
        assertFalse(session.onAccessUnit(ordinaryAu(2, 1)));
        assertEquals(ClientState.RESYNCING, session.getState());

        assertTrue(session.retryConfigure());

        assertEquals(ClientState.WAIT_RANDOM_ACCESS, session.getState());
        assertEquals(1, failed.getStopCount());
        assertEquals(1, failed.getReleaseCount());
        assertEquals(2, bridges.getDecoders().size());
        assertEquals(1, bridges.getDecoders().get(1).getConfigureCount());
        assertEquals(1, bridges.getDecoders().get(1).getStartCount());
    }

    @Test
    public void retryConfigure_outsideResyncingReturnsFalse() {
        ScriptedBridgeFactory bridges = new ScriptedBridgeFactory();
        CodecRegistration registration = TestH264Registration.withoutSurfaceRequirement(bridges);
        ClientMediaSession session = newSession(registration, new CountingRenderTarget());

        assertFalse(session.retryConfigure());
        assertTrue(bridges.getDecoders().isEmpty());

        assertTrue(session.onDescriptor(h264Descriptor(2, WIDTH, HEIGHT)));
        assertFalse(session.retryConfigure());
        assertEquals(ClientState.WAIT_RANDOM_ACCESS, session.getState());
        assertEquals(1, bridges.getDecoders().size());

        session.close();
        assertFalse(session.retryConfigure());
        assertEquals(ClientState.CLOSED, session.getState());
    }

    @Test
    public void staleEpochDescriptor_doesNotReadSurfaceOrCloseDecoder() {
        ScriptedBridgeFactory bridges = new ScriptedBridgeFactory();
        CountingRenderTarget target = new CountingRenderTarget();
        CodecRegistration registration = TestH264Registration.withoutSurfaceRequirement(bridges);
        ClientMediaSession session = newSession(registration, target);
        assertTrue(session.onDescriptor(h264Descriptor(2, WIDTH, HEIGHT)));
        assertTrue(session.onAccessUnit(joinAu(2, 0)));
        assertEquals(ClientState.STREAMING, session.getState());
        FakeMediaCodecDecoderBridge decoder = bridges.getDecoders().get(0);
        int surfaceReads = target.getSurfaceReads();

        assertFalse(session.onDescriptor(h264Descriptor(1, WIDTH, HEIGHT)));

        assertEquals(ClientState.STREAMING, session.getState());
        assertEquals(2, session.getCurrentDescriptor().getConfigEpoch());
        assertEquals(surfaceReads, target.getSurfaceReads());
        assertEquals(0, decoder.getReleaseCount());
        assertEquals(1, bridges.getDecoders().size());
    }

    @Test
    public void sameEpochDifferentHash_throwsBeforeSurfaceOrDecoderSideEffects() {
        ScriptedBridgeFactory bridges = new ScriptedBridgeFactory();
        CountingRenderTarget target = new CountingRenderTarget();
        CodecRegistration registration = TestH264Registration.withoutSurfaceRequirement(bridges);
        ClientMediaSession session = newSession(registration, target);
        StreamDescriptor descriptor = h264Descriptor(2, WIDTH, HEIGHT);
        assertTrue(session.onDescriptor(descriptor));
        assertTrue(session.onAccessUnit(joinAu(2, 0)));
        FakeMediaCodecDecoderBridge decoder = bridges.getDecoders().get(0);
        int surfaceReads = target.getSurfaceReads();

        assertThrows(IllegalArgumentException.class,
                () -> session.onDescriptor(h264Descriptor(2, 320, 240)));

        assertEquals(ClientState.STREAMING, session.getState());
        assertEquals(descriptor.getConfigHash(),
                session.getCurrentDescriptor().getConfigHash());
        assertEquals(surfaceReads, target.getSurfaceReads());
        assertEquals(0, decoder.getReleaseCount());
        assertEquals(1, bridges.getDecoders().size());
    }

    /**
     * Discriminates epoch/hash ordering from the surface gate using the real H.264 registration
     * (surface output required) and a null-surface render target. Under the BASE order, where the
     * surface gate ran before epoch/hash validation, the stale descriptor would be read and
     * recorded, the duplicate would read the surface again, and the same-epoch/different-hash
     * descriptor would return false instead of throwing. The assertions below therefore fail on
     * that old ordering and pass only when epoch/hash validation precedes all surface reads.
     */
    @Test
    public void epochHashValidation_precedesSurfaceGateForSurfaceRequiredCodec() {
        ScriptedBridgeFactory bridges = new ScriptedBridgeFactory();
        CountingRenderTarget target = new CountingRenderTarget();
        ClientMediaSession session = newSession(H264Codec.registration(bridges), target);
        StreamDescriptor first = h264Descriptor(2, WIDTH, HEIGHT);

        assertFalse(session.onDescriptor(first));
        assertEquals(ClientState.RESYNCING, session.getState());
        assertSameDescriptor(first, session.getCurrentDescriptor());
        assertEquals(1, target.getSurfaceReads());

        assertFalse(session.onDescriptor(h264Descriptor(1, WIDTH, HEIGHT)));
        assertEquals(ClientState.RESYNCING, session.getState());
        assertSameDescriptor(first, session.getCurrentDescriptor());
        assertEquals(1, target.getSurfaceReads());

        assertFalse(session.onDescriptor(h264Descriptor(2, WIDTH, HEIGHT)));
        assertEquals(1, target.getSurfaceReads());
        assertSameDescriptor(first, session.getCurrentDescriptor());

        assertThrows(IllegalArgumentException.class,
                () -> session.onDescriptor(h264Descriptor(2, 320, 240)));
        assertEquals(1, target.getSurfaceReads());
        assertSameDescriptor(first, session.getCurrentDescriptor());
        assertEquals(ClientState.RESYNCING, session.getState());
        assertTrue(bridges.getDecoders().isEmpty());
    }

    @Test
    public void firstNonIdrInWaitRandomAccess_isDropped() {
        ScriptedBridgeFactory bridges = new ScriptedBridgeFactory();
        CodecRegistration registration = TestH264Registration.withoutSurfaceRequirement(bridges);
        ClientMediaSession session = newSession(registration, new CountingRenderTarget());
        assertTrue(session.onDescriptor(h264Descriptor(2, WIDTH, HEIGHT)));

        assertFalse(session.onAccessUnit(ordinaryAu(2, 0)));

        assertEquals(ClientState.WAIT_RANDOM_ACCESS, session.getState());
        assertTrue(bridges.getDecoders().get(0).getQueuedInputData().isEmpty());
    }

    @Test
    public void validIdr_transitionsToStreamingAndDecodes() {
        ScriptedBridgeFactory bridges = new ScriptedBridgeFactory();
        CodecRegistration registration = TestH264Registration.withoutSurfaceRequirement(bridges);
        ClientMediaSession session = newSession(registration, new CountingRenderTarget());
        assertTrue(session.onDescriptor(h264Descriptor(2, WIDTH, HEIGHT)));

        assertTrue(session.onAccessUnit(joinAu(2, 0)));

        assertEquals(ClientState.STREAMING, session.getState());
        assertEquals(1, bridges.getDecoders().get(0).getQueuedInputData().size());

        assertTrue(session.onAccessUnit(ordinaryAu(2, 1)));

        assertEquals(ClientState.STREAMING, session.getState());
        assertEquals(2, bridges.getDecoders().get(0).getQueuedInputData().size());
    }

    @Test
    public void decodeFailure_entersResyncingAndRecoversOnlyOnIdr() {
        ScriptedBridgeFactory bridges = new ScriptedBridgeFactory();
        CodecRegistration registration = TestH264Registration.withoutSurfaceRequirement(bridges);
        ClientMediaSession session = newSession(registration, new CountingRenderTarget());
        assertTrue(session.onDescriptor(h264Descriptor(2, WIDTH, HEIGHT)));
        assertTrue(session.onAccessUnit(joinAu(2, 0)));
        FakeMediaCodecDecoderBridge decoder = bridges.getDecoders().get(0);
        decoder.failNextDequeue(new RuntimeException("dequeue failed"));

        assertFalse(session.onAccessUnit(ordinaryAu(2, 1)));
        assertEquals(ClientState.RESYNCING, session.getState());
        // The failing access unit is queued before dequeueOutput throws, so it remains in
        // the bridge alongside the initial IDR.
        assertEquals(2, decoder.getQueuedInputData().size());

        assertFalse(session.onAccessUnit(ordinaryAu(2, 2)));
        assertEquals(ClientState.RESYNCING, session.getState());
        // The policy rejects this non-join unit before decode, so the queue is unchanged.
        assertEquals(2, decoder.getQueuedInputData().size());

        assertTrue(session.onAccessUnit(joinAu(2, 3)));
        assertEquals(ClientState.STREAMING, session.getState());
        assertEquals(3, decoder.getQueuedInputData().size());
    }

    private ClientMediaSession newSession(CodecRegistration registration, RenderTarget target) {
        CodecRegistry registry = new CodecRegistry();
        registry.register(registration);
        return new ClientMediaSession(CLIENT_KEY, registry,
                new ClientCapabilities(Collections.singleton(CodecIds.H264)),
                target, null);
    }

    private static StreamDescriptor h264Descriptor(int configEpoch, int codedWidth,
                                                   int codedHeight) {
        return StreamDescriptor.builder(STREAM_KEY, configEpoch, "h264", H264Codec.MIME,
                        BitstreamFormat.ANNEX_B)
                .codedSize(codedWidth, codedHeight)
                .visibleSize(codedWidth, codedHeight)
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

    private static void assertSameDescriptor(StreamDescriptor expected,
                                             StreamDescriptor actual) {
        assertNotNull(actual);
        assertEquals(expected.getConfigEpoch(), actual.getConfigEpoch());
        assertEquals(expected.getCodecId(), actual.getCodecId());
        assertEquals(expected.getMime(), actual.getMime());
        assertEquals(expected.getCodedWidth(), actual.getCodedWidth());
        assertEquals(expected.getCodedHeight(), actual.getCodedHeight());
        assertEquals(expected.getConfigHash(), actual.getConfigHash());
    }

    private static final class CountingRenderTarget implements RenderTarget {

        private int surfaceReads;

        @Override
        public Surface getSurface() {
            surfaceReads++;
            return null;
        }

        @Override
        public void renderEncodedImage(EncodedImageFrame frame) {
        }

        int getSurfaceReads() {
            return surfaceReads;
        }
    }

    /**
     * Bridge factory that services one decoder bridge per create call. A queued configure failure
     * is attached to the next created bridge, so consecutive decoder configure attempts can fail
     * without touching the shared bridge fake.
     */
    private static final class ScriptedBridgeFactory implements MediaCodecBridgeFactory {

        private final Deque<RuntimeException> configureFailures = new ArrayDeque<>();

        private final List<FakeMediaCodecDecoderBridge> decoders = new ArrayList<>();

        @Override
        public MediaCodecEncoderBridge createEncoder(String mime) {
            throw new UnsupportedOperationException("encoder is not used by recovery tests");
        }

        @Override
        public MediaCodecDecoderBridge createDecoder(String mime) {
            FakeMediaCodecDecoderBridge decoder = new FakeMediaCodecDecoderBridge();
            RuntimeException failure = configureFailures.poll();
            if (failure != null) {
                decoder.failNextConfigure(failure);
            }
            decoders.add(decoder);
            return decoder;
        }

        void failNextConfigure(RuntimeException failure) {
            configureFailures.add(failure);
        }

        List<FakeMediaCodecDecoderBridge> getDecoders() {
            return decoders;
        }
    }

    private static final class RecordingDecoderFactory implements DecoderFactory {

        private final DecoderFactory delegate;

        private final List<StreamDescriptor> descriptors = new ArrayList<>();

        RecordingDecoderFactory(DecoderFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public Decoder create(StreamKey streamKey, StreamDescriptor descriptor,
                              RenderTarget renderTarget) {
            descriptors.add(descriptor);
            return delegate.create(streamKey, descriptor, renderTarget);
        }

        List<StreamDescriptor> getDescriptors() {
            return descriptors;
        }
    }
}
