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
import java.util.Collections;
import java.util.List;
import java.util.function.IntSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Contract of {@link ClientMediaSession#rebindSurface()}: moving the decoder output to another
 * display host must rebuild only the decoder.
 *
 * <p>The viewer switches between a floating window and a full-screen activity by re-pointing its
 * render target at the other host's surface. The session is not recreated, because the descriptor,
 * the secure flag and the geometry arrive once per connection: a new session would never receive
 * them and the picture would stay black until the user reconnected. These tests pin that the switch
 * closes the old decoder, configures a new one for the same descriptor, reports the coded size
 * again, and resumes on the next join point - while a switch to a host without a surface waits in
 * {@code RESYNCING} instead of failing the session.</p>
 */
public class ClientMediaSessionRebindTest {

    private static final String CODEC_ID = "rebind-codec";

    private static final StreamKey STREAM_KEY = new StreamKey("rebind", "screen");

    private static final ClientSessionKey CLIENT_KEY = new ClientSessionKey(STREAM_KEY, "local");

    private static final int CODED_WIDTH = 64;

    private static final int CODED_HEIGHT = 48;

    @Test
    public void rebindSurface_whileStreaming_rebuildsOnlyTheDecoderAndResumesOnAJoinPoint() {
        FakeCodec codec = new FakeCodec(CODEC_ID, 0, false, true);
        SwitchableTarget target = new SwitchableTarget(true);
        ClientMediaSession session = newSession(codec, target);

        assertTrue(session.onDescriptor(descriptor(1)));
        assertTrue(session.onAccessUnit(joinAu(1, 0)));
        assertEquals(ClientState.STREAMING, session.getState());
        assertEquals(1, codec.getDecoderCreateCount());
        FakeCodec.FakeDecoder firstDecoder = codec.getLastDecoder();
        assertNotNull(firstDecoder);

        // The user expands the floating window into the activity: the target now exposes the
        // activity's surface and the decoder has to be created against it.
        target.surface = emptySurface();
        assertTrue(session.rebindSurface());

        assertTrue("the decoder bound to the previous host must be closed", firstDecoder.isClosed());
        assertEquals(2, codec.getDecoderCreateCount());
        assertEquals(ClientState.WAIT_RANDOM_ACCESS, session.getState());
        assertFalse("the session must not be closed by a host switch", session.isClosed());

        // The new decoder cannot resume on an ordinary frame, only on a join point.
        assertFalse(session.onAccessUnit(nonJoinAu(1, 1)));
        assertEquals(ClientState.WAIT_RANDOM_ACCESS, session.getState());
        assertTrue(session.onAccessUnit(joinAu(1, 2)));
        assertEquals(ClientState.STREAMING, session.getState());
        // One frame before the switch and one after it; the dropped ordinary frame rendered nothing.
        assertEquals(2, target.images.size());
    }

    @Test
    public void rebindSurface_reportsTheCodedSizeBeforeTheNewDecoderExists() {
        FakeCodec codec = new FakeCodec(CODEC_ID, 0, false, true);
        SwitchableTarget target = new SwitchableTarget(true, codec::getDecoderCreateCount);
        ClientMediaSession session = newSession(codec, target);

        assertTrue(session.onDescriptor(descriptor(1)));
        assertEquals(1, target.contentSizes.size());

        assertTrue(session.rebindSurface());

        assertEquals(2, target.contentSizes.size());
        int[] size = target.contentSizes.get(1);
        assertEquals(CODED_WIDTH, size[0]);
        assertEquals(CODED_HEIGHT, size[1]);
        assertEquals("the size must be reported before the new decoder is created",
                Integer.valueOf(1), target.decoderCountsAtContentSize.get(1));
    }

    @Test
    public void rebindSurface_withoutASurfaceYet_waitsForTheNewHostAndKeepsTheSessionOpen() {
        FakeCodec codec = new FakeCodec(CODEC_ID, 0, false, true);
        SwitchableTarget target = new SwitchableTarget(true);
        ClientMediaSession session = newSession(codec, target);

        assertTrue(session.onDescriptor(descriptor(1)));
        assertTrue(session.onAccessUnit(joinAu(1, 0)));
        assertEquals(ClientState.STREAMING, session.getState());

        // The activity is attached before its texture view produced a surface.
        target.surface = null;
        assertFalse(session.rebindSurface());

        assertEquals(ClientState.RESYNCING, session.getState());
        assertEquals(ClientMediaSession.ResyncReason.MISSING_SURFACE,
                session.getLastResyncReason());
        assertEquals("no decoder may be created against a missing surface",
                1, codec.getDecoderCreateCount());
        assertFalse(session.isClosed());

        // The texture view becomes available; retryConfigure is still the recovery entry.
        target.surface = emptySurface();
        assertTrue(session.retryConfigure());
        assertEquals(ClientState.WAIT_RANDOM_ACCESS, session.getState());
        assertEquals(2, codec.getDecoderCreateCount());
    }

    @Test
    public void rebindSurface_beforeADescriptor_reportsFalseWithoutAStateChange() {
        FakeCodec codec = new FakeCodec(CODEC_ID, 0, false, true);
        SwitchableTarget target = new SwitchableTarget(true);
        ClientMediaSession session = newSession(codec, target);

        assertFalse(session.rebindSurface());
        assertEquals(ClientState.NEGOTIATING, session.getState());
        assertNull(session.getLastResyncReason());
        assertEquals(0, codec.getDecoderCreateCount());
    }

    @Test
    public void rebindSurface_onAClosedSession_reportsFalse() {
        FakeCodec codec = new FakeCodec(CODEC_ID, 0, false, true);
        SwitchableTarget target = new SwitchableTarget(true);
        ClientMediaSession session = newSession(codec, target);

        assertTrue(session.onDescriptor(descriptor(1)));
        session.close();

        assertFalse(session.rebindSurface());
        assertEquals(ClientState.CLOSED, session.getState());
        assertEquals(1, codec.getDecoderCreateCount());
    }

    @Test
    public void rebindSurface_keepsTheDescriptorThatOnlyArrivesOncePerConnection() {
        FakeCodec codec = new FakeCodec(CODEC_ID, 0, false, true);
        SwitchableTarget target = new SwitchableTarget(true);
        ClientMediaSession session = newSession(codec, target);

        assertTrue(session.onDescriptor(descriptor(1)));
        assertTrue(session.rebindSurface());

        assertNotNull(session.getCurrentDescriptor());
        assertEquals(1, session.getCurrentDescriptor().getConfigEpoch());
    }

    private static ClientMediaSession newSession(FakeCodec codec, RenderTarget target) {
        CodecRegistry registry = new CodecRegistry();
        registry.register(codec.registration());
        return new ClientMediaSession(CLIENT_KEY, registry,
                new ClientCapabilities(Collections.singleton(CODEC_ID)), target, null);
    }

    private static StreamDescriptor descriptor(int configEpoch) {
        return StreamDescriptor.builder(STREAM_KEY, configEpoch, CODEC_ID, FakeCodec.DEFAULT_MIME,
                        BitstreamFormat.ANNEX_B)
                .codedSize(CODED_WIDTH, CODED_HEIGHT)
                .visibleSize(CODED_WIDTH, CODED_HEIGHT)
                .build();
    }

    private static EncodedAccessUnit joinAu(int configEpoch, long sequence) {
        return accessUnit(configEpoch, sequence, true);
    }

    private static EncodedAccessUnit nonJoinAu(int configEpoch, long sequence) {
        return accessUnit(configEpoch, sequence, false);
    }

    private static EncodedAccessUnit accessUnit(int configEpoch, long sequence,
                                                boolean joinPoint) {
        return new EncodedAccessUnit(STREAM_KEY, configEpoch, sequence, sequence * 1000L,
                EncodedAccessUnit.DTS_UNSET, AccessUnitFlags.of(joinPoint, joinPoint, false),
                joinPoint ? new JoinInfo(configEpoch, Collections.emptyList()) : null, null,
                new byte[]{0x0A, 0x0B});
    }

    /**
     * Allocates an uninitialized {@link Surface}; the test only needs a non-null marker, because the
     * session gate checks for null and the fake decoder never touches the surface.
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
     * Target whose surface comes and goes like a host that is attached before it can render, and
     * which records the coded sizes together with the number of decoders alive at that moment.
     */
    private static final class SwitchableTarget implements RenderTarget {

        private final List<EncodedImageFrame> images = new ArrayList<>();

        private final List<int[]> contentSizes = new ArrayList<>();

        private final List<Integer> decoderCountsAtContentSize = new ArrayList<>();

        private final IntSupplier decoderCounter;

        Surface surface;

        SwitchableTarget(boolean surfaceAvailable) {
            this(surfaceAvailable, () -> -1);
        }

        SwitchableTarget(boolean surfaceAvailable, IntSupplier decoderCounter) {
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
        public void renderEncodedImage(EncodedImageFrame frame) {
            images.add(frame);
        }
    }
}
