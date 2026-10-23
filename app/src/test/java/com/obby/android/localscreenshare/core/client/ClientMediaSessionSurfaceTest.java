package com.obby.android.localscreenshare.core.client;

import android.view.Surface;

import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.model.AccessUnitFlags;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.model.ClientState;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.JoinInfo;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.render.EncodedImageFrame;
import com.obby.android.localscreenshare.core.render.RenderTarget;
import com.obby.android.localscreenshare.core.stream.ClientCapabilities;
import com.obby.android.localscreenshare.core.support.FakeCodec;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.IntSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for the client viewer surface gate that caused the loopback render deadlock.
 *
 * <p>The deadlock was: a surface-output codec (H.264) refuses to create its decoder until
 * {@link RenderTarget#getSurface()} is non-null, which only happens after the viewer's texture view
 * is mounted; the viewer used to be mounted only on {@code STREAMING}, which needs the decoder
 * first. The fix mounts the viewer before connecting, so the surface is available when the
 * descriptor arrives. These tests pin the session contract the fix relies on: a session blocked on
 * a missing surface must create its decoder through {@link ClientMediaSession#retryConfigure()} once
 * the surface appears, and then reach {@code STREAMING} on the next join point.</p>
 */
public class ClientMediaSessionSurfaceTest {

    private static final String CODEC_ID = "surface-codec";

    private static final StreamKey STREAM_KEY = new StreamKey("surface", "screen");

    private static final ClientSessionKey CLIENT_KEY = new ClientSessionKey(STREAM_KEY, "local");

    private static final int CODED_WIDTH = 64;

    private static final int CODED_HEIGHT = 48;

    // Landscape-like geometry used to model a rotation in the middle of a session.
    private static final int ROTATED_WIDTH = 96;

    private static final int ROTATED_HEIGHT = 32;

    @Test
    public void surfaceRequiredCodec_withSurfacePresentAtDescriptorTime_streams() {
        FakeCodec codec = new FakeCodec(CODEC_ID, 0, false, true);
        SwitchableSurfaceTarget target = new SwitchableSurfaceTarget(true);
        ClientMediaSession session = newSession(codec, target);

        assertTrue(session.onDescriptor(descriptor(1)));
        assertEquals(ClientState.WAIT_RANDOM_ACCESS, session.getState());
        assertEquals(1, codec.getDecoderCreateCount());

        assertTrue(session.onAccessUnit(joinAu(1, 0)));
        assertEquals(ClientState.STREAMING, session.getState());
        assertEquals(1, target.images.size());
    }

    @Test
    public void surfaceRequiredCodec_withMissingSurface_recoversAfterSurfaceAppears() {
        FakeCodec codec = new FakeCodec(CODEC_ID, 0, false, true);
        SwitchableSurfaceTarget target = new SwitchableSurfaceTarget(false);
        ClientMediaSession session = newSession(codec, target);

        // The descriptor arrives before the surface; the session records it and waits.
        assertFalse(session.onDescriptor(descriptor(1)));
        assertEquals(ClientState.RESYNCING, session.getState());
        assertEquals(0, codec.getDecoderCreateCount());

        // The viewer is mounted, so the surface becomes available; retryConfigure creates the decoder.
        target.surface = emptySurface();
        assertTrue(session.retryConfigure());
        assertEquals(ClientState.WAIT_RANDOM_ACCESS, session.getState());
        assertEquals(1, codec.getDecoderCreateCount());

        // A join point moves the session to STREAMING and renders the first image.
        assertTrue(session.onAccessUnit(joinAu(1, 0)));
        assertEquals(ClientState.STREAMING, session.getState());
        assertEquals(1, target.images.size());
    }

    @Test
    public void newEpochWithANewSize_reportsTheCodedSizeToTheRenderTarget() {
        FakeCodec codec = new FakeCodec(CODEC_ID, 0, false, true);
        SwitchableSurfaceTarget target = new SwitchableSurfaceTarget(true, codec::getDecoderCreateCount);
        ClientMediaSession session = newSession(codec, target);

        assertTrue(session.onDescriptor(descriptor(1, CODED_WIDTH, CODED_HEIGHT)));
        assertEquals(1, target.contentSizes.size());
        assertSize(target.contentSizes.get(0), CODED_WIDTH, CODED_HEIGHT);

        // The device symptom: the server re-encodes at a new resolution and the viewer keeps the
        // buffer geometry of the previous one, so only a corner of the window is filled until the
        // viewer reconnects. The session has to report the new coded size before the decoder that
        // will write into it exists.
        assertTrue(session.onDescriptor(descriptor(2, ROTATED_WIDTH, ROTATED_HEIGHT)));

        assertEquals(2, target.contentSizes.size());
        assertSize(target.contentSizes.get(1), ROTATED_WIDTH, ROTATED_HEIGHT);
        assertEquals("the size must be reported before the new decoder is created",
                1, (int) target.decoderCountsAtContentSize.get(1));
        assertEquals(ClientState.WAIT_RANDOM_ACCESS, session.getState());
        assertEquals(2, codec.getDecoderCreateCount());
    }

    @Test
    public void sameSizeNewEpoch_reportTheSameContentSizeAgain() {
        FakeCodec codec = new FakeCodec(CODEC_ID, 0, false, true);
        SwitchableSurfaceTarget target = new SwitchableSurfaceTarget(true);
        ClientMediaSession session = newSession(codec, target);

        assertTrue(session.onDescriptor(descriptor(1, CODED_WIDTH, CODED_HEIGHT)));
        assertTrue(session.onDescriptor(descriptor(2, CODED_WIDTH, CODED_HEIGHT)));

        // A switch that only advances the epoch reports the same size; the target ignores repeats.
        assertEquals(2, target.contentSizes.size());
        assertSize(target.contentSizes.get(1), CODED_WIDTH, CODED_HEIGHT);
    }

    @Test
    public void onDescriptor_surfaceCodecAppliesFrameRate() {
        FakeCodec codec = new FakeCodec(CODEC_ID, 0, false, true);
        SwitchableSurfaceTarget target = new SwitchableSurfaceTarget(true);
        ClientMediaSession session = newSession(codec, target);

        assertTrue(session.onDescriptor(descriptor(1, CODED_WIDTH, CODED_HEIGHT, 30)));

        assertEquals(Collections.singletonList(30), target.frameRates);
        assertEquals(ClientState.WAIT_RANDOM_ACCESS, session.getState());
        assertEquals(1, codec.getDecoderCreateCount());
    }

    @Test
    public void onDescriptor_sameHashNewFrameRateAppliesWithoutReconfigure() {
        FakeCodec codec = new FakeCodec(CODEC_ID, 0, false, true);
        SwitchableSurfaceTarget target = new SwitchableSurfaceTarget(true);
        ClientMediaSession session = newSession(codec, target);
        assertTrue(session.onDescriptor(descriptor(1, CODED_WIDTH, CODED_HEIGHT, 30)));
        int configureCount = codec.getLastDecoder().getConfigureCount();

        // The defensive branch: the same decode configuration with a different display hint.
        // It can only be reached by a server that changes the hint without advancing the epoch.
        assertFalse(session.onDescriptor(descriptor(1, CODED_WIDTH, CODED_HEIGHT, 60)));

        assertEquals(Arrays.asList(30, 60), target.frameRates);
        assertEquals(1, codec.getDecoderCreateCount());
        assertEquals(configureCount, codec.getLastDecoder().getConfigureCount());
        assertEquals(60, session.getCurrentDescriptor().getFrameRate());
    }

    private static void assertSize(int[] size, int width, int height) {
        assertEquals(width, size[0]);
        assertEquals(height, size[1]);
    }

    private static ClientMediaSession newSession(FakeCodec codec, RenderTarget target) {
        CodecRegistry registry = new CodecRegistry();
        registry.register(codec.registration());
        return new ClientMediaSession(CLIENT_KEY, registry,
                new ClientCapabilities(Collections.singleton(CODEC_ID)), target, null);
    }

    private static StreamDescriptor descriptor(int configEpoch) {
        return descriptor(configEpoch, CODED_WIDTH, CODED_HEIGHT);
    }

    private static StreamDescriptor descriptor(int configEpoch, int width, int height) {
        return descriptor(configEpoch, width, height, 0);
    }

    private static StreamDescriptor descriptor(int configEpoch, int width, int height,
                                               int frameRate) {
        return StreamDescriptor.builder(STREAM_KEY, configEpoch, CODEC_ID, FakeCodec.DEFAULT_MIME,
                        BitstreamFormat.ANNEX_B)
                .codedSize(width, height)
                .visibleSize(width, height)
                .frameRate(frameRate)
                .build();
    }

    private static EncodedAccessUnit joinAu(int configEpoch, long sequence) {
        return new EncodedAccessUnit(STREAM_KEY, configEpoch, sequence, sequence * 1000L,
                EncodedAccessUnit.DTS_UNSET, AccessUnitFlags.of(true, true, false),
                new JoinInfo(configEpoch, Collections.emptyList()), null, new byte[]{0x0A, 0x0B});
    }

    /**
     * Allocates an uninitialized {@link Surface}. The JVM cannot run the Android constructors
     * (they are native), and this test only needs a non-null marker: the session gate checks for
     * null and the fake decoder never touches the surface.
     */
    private static Surface emptySurface() {
        try {
            Field field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Object unsafe = field.get(null);
            Method allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
            return (Surface) allocate.invoke(unsafe, Surface.class);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("unable to allocate a Surface for the test", e);
        }
    }

    /**
     * Render target whose surface is absent while the viewer is not mounted and present once it is.
     *
     * <p>Also records every reported content size together with the number of decoders that
     * existed at that moment, so a test can show that the size reaches the target before the
     * decoder that will write into it is created.</p>
     */
    private static final class SwitchableSurfaceTarget implements RenderTarget {

        private final List<EncodedImageFrame> images = new ArrayList<>();

        private final List<int[]> contentSizes = new ArrayList<>();

        private final List<Integer> frameRates = new ArrayList<>();

        private final List<Integer> decoderCountsAtContentSize = new ArrayList<>();

        private final IntSupplier decoderCounter;

        Surface surface;

        SwitchableSurfaceTarget(boolean surfaceAvailable) {
            this(surfaceAvailable, () -> -1);
        }

        SwitchableSurfaceTarget(boolean surfaceAvailable, IntSupplier decoderCounter) {
            surface = surfaceAvailable ? emptySurface() : null;
            this.decoderCounter = decoderCounter;
        }

        @Override
        public Surface getSurface() {
            return surface;
        }

        @Override
        public void setContentSize(int width, int height) {
            contentSizes.add(new int[]{width, height});
            decoderCountsAtContentSize.add(decoderCounter.getAsInt());
        }

        @Override
        public void setFrameRate(int frameRate) {
            frameRates.add(frameRate);
        }

        @Override
        public void renderEncodedImage(EncodedImageFrame frame) {
            images.add(frame);
        }
    }
}
