package com.obby.android.localscreenshare.render;

import android.view.Surface;

import com.obby.android.localscreenshare.core.render.EncodedImageFrame;
import com.obby.android.localscreenshare.core.render.RenderTarget;
import com.obby.android.localscreenshare.core.support.FakeRenderTarget;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * Behaviour of {@link SwitchableRenderTarget}: forwarding, re-pointing and the null contract.
 *
 * <p>The target exists so the client session can keep one {@link RenderTarget} for its whole life
 * while the viewer moves between a floating window and a full-screen activity. A test can therefore
 * only rely on two properties: everything is forwarded to the active delegate, and switching the
 * delegate is what a later frame uses. Releasing the previous host is deliberately not this class's
 * job, so nothing is closed here.</p>
 */
public class SwitchableRenderTargetTest {

    @Test
    public void forwardsEncodedImagesAndContentSizesToTheInitialDelegate() {
        FakeRenderTarget first = new FakeRenderTarget();
        SwitchableRenderTarget target = new SwitchableRenderTarget(first);

        target.setContentSize(64, 48);
        target.renderEncodedImage(frame(1));

        assertEquals(1, first.getContentSizes().size());
        assertEquals(1, first.getImages().size());
    }

    @Test
    public void getSurfaceReturnsWhateverTheActiveDelegateExposes() {
        RecordingTarget first = new RecordingTarget(emptySurface());
        RecordingTarget second = new RecordingTarget(emptySurface());
        SwitchableRenderTarget target = new SwitchableRenderTarget(first);

        assertSame(first.getSurface(), target.getSurface());

        target.setDelegate(second);

        assertSame(second.getSurface(), target.getSurface());
    }

    @Test
    public void aMissingDelegateSurfaceSurvivesTheSwitch() {
        FakeRenderTarget first = new FakeRenderTarget();
        SwitchableRenderTarget target = new SwitchableRenderTarget(first);

        target.setDelegate(new FakeRenderTarget());

        // A host whose texture view is not ready yet must surface as "no surface", which keeps the
        // session waiting instead of configuring a decoder against nothing.
        assertNull(target.getSurface());
    }

    @Test
    public void framesAfterASwitchReachOnlyTheNewDelegate() {
        FakeRenderTarget first = new FakeRenderTarget();
        FakeRenderTarget second = new FakeRenderTarget();
        SwitchableRenderTarget target = new SwitchableRenderTarget(first);

        target.renderEncodedImage(frame(1));
        target.setDelegate(second);
        target.renderEncodedImage(frame(2));

        assertEquals(1, first.getImages().size());
        assertEquals(1, second.getImages().size());
        assertSame(second, target.getDelegate());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsANullInitialDelegate() {
        new SwitchableRenderTarget(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsANullDelegateSwitch() {
        new SwitchableRenderTarget(new FakeRenderTarget()).setDelegate(null);
    }

    private static EncodedImageFrame frame(long ptsUs) {
        return new EncodedImageFrame(new byte[]{0x01}, ptsUs, 64, 48);
    }

    /**
     * Allocates an uninitialized {@link Surface}; the test only needs a non-null marker, because no
     * code in this class touches the surface beyond returning it.
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
     * Target that returns a fixed surface and records which frames reached it.
     */
    private static final class RecordingTarget implements RenderTarget {

        private final Surface surface;

        private final List<EncodedImageFrame> images = new ArrayList<>();

        RecordingTarget(Surface surface) {
            this.surface = surface;
        }

        @Override
        public Surface getSurface() {
            return surface;
        }

        @Override
        public void setContentSize(int width, int height) {
        }

        @Override
        public void renderEncodedImage(EncodedImageFrame frame) {
            images.add(frame);
        }
    }
}
