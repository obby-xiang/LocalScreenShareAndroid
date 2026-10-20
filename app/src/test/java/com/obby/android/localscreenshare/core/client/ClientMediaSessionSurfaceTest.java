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
     */
    private static final class SwitchableSurfaceTarget implements RenderTarget {

        private final List<EncodedImageFrame> images = new ArrayList<>();

        Surface surface;

        SwitchableSurfaceTarget(boolean surfaceAvailable) {
            surface = surfaceAvailable ? emptySurface() : null;
        }

        @Override
        public Surface getSurface() {
            return surface;
        }

        @Override
        public void renderEncodedImage(EncodedImageFrame frame) {
            images.add(frame);
        }
    }
}
