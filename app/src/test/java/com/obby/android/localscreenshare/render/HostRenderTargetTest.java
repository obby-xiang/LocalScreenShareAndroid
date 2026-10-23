package com.obby.android.localscreenshare.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import android.graphics.Rect;
import android.view.Surface;

import com.obby.android.localscreenshare.core.render.EncodedImageFrame;
import com.obby.android.localscreenshare.core.render.RenderTarget;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for {@link HostRenderTarget}.
 *
 * <p>They pin the dispatch rule the JPEG batch depends on: the surface half owns surface output and
 * buffer geometry, the bitmap half owns encoded-image frames, and neither ever sees the other's
 * calls. They also pin that releasing the composed target releases both halves.</p>
 */
public class HostRenderTargetTest {

    @Test
    public void getSurface_asksOnlyTheSurfaceHalf() {
        RecordingTarget surface = new RecordingTarget();
        RecordingTarget bitmap = new RecordingTarget();

        new HostRenderTarget(surface, bitmap).getSurface();

        assertEquals(1, surface.getSurfaceCalls);
        assertEquals(0, bitmap.getSurfaceCalls);
    }

    @Test
    public void renderEncodedImage_reachesOnlyTheBitmapHalf() {
        RecordingTarget surface = new RecordingTarget();
        RecordingTarget bitmap = new RecordingTarget();
        EncodedImageFrame frame = new EncodedImageFrame(new byte[]{0x01}, 0L, 64, 48);

        new HostRenderTarget(surface, bitmap).renderEncodedImage(frame);

        assertEquals(1, bitmap.renderedImages);
        assertSame(frame, bitmap.lastFrame);
        assertEquals(0, surface.renderedImages);
    }

    @Test
    public void setContentSize_reachesOnlyTheSurfaceHalf() {
        RecordingTarget surface = new RecordingTarget();
        RecordingTarget bitmap = new RecordingTarget();

        new HostRenderTarget(surface, bitmap).setContentSize(1920, 1080);

        assertEquals(1, surface.contentSizeCalls);
        assertEquals(1920, surface.lastContentWidth);
        assertEquals(1080, surface.lastContentHeight);
        assertEquals(0, bitmap.contentSizeCalls);
    }

    @Test
    public void setFrameRate_reachesSurfaceHalfOnly() {
        RecordingTarget surface = new RecordingTarget();
        RecordingTarget bitmap = new RecordingTarget();

        new HostRenderTarget(surface, bitmap).setFrameRate(60);

        assertEquals(1, surface.frameRateCalls);
        assertEquals(60, surface.lastFrameRate);
        assertEquals(0, bitmap.frameRateCalls);
    }

    @Test
    public void release_releasesBothHalves() throws ReflectiveOperationException {
        // The two real halves are the only implementations that own a resource: the surface half
        // caches a Surface and the bitmap half holds a pending bitmap and the frame layer. The
        // texture view cannot exist in a JVM, so the surface half is allocated without its
        // constructor (the same trick ClientMediaSessionSurfaceTest uses for Surface); release()
        // only touches the fields below, so it stays safe.
        SurfaceRenderTarget surface = allocate(SurfaceRenderTarget.class);
        setInt(surface, "appliedWidth", 1920);
        setInt(surface, "appliedHeight", 1080);
        AtomicInteger clearedFrames = new AtomicInteger();
        BitmapRenderTarget bitmap = new BitmapRenderTarget(recordingHost(clearedFrames),
            Runnable::run);

        new HostRenderTarget(surface, bitmap).release();

        assertEquals(0, getInt(surface, "appliedWidth"));
        assertEquals(0, getInt(surface, "appliedHeight"));
        assertEquals(1, clearedFrames.get());
    }

    @Test
    public void constructor_rejectsANullHalf() {
        RecordingTarget target = new RecordingTarget();

        assertThrows(IllegalArgumentException.class, () -> new HostRenderTarget(null, target));
        assertThrows(IllegalArgumentException.class, () -> new HostRenderTarget(target, null));
    }

    /**
     * Returns a {@link ViewerHost} that counts frame-layer clears and ignores everything else.
     */
    private static ViewerHost recordingHost(AtomicInteger clearedFrames) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("clearEncodedFrame".equals(method.getName())) {
                clearedFrames.incrementAndGet();
            }
            if ("getContentBox".equals(method.getName()) && args != null && args.length == 1) {
                ((Rect) args[0]).setEmpty();
            }
            return null;
        };
        return (ViewerHost) Proxy.newProxyInstance(ViewerHost.class.getClassLoader(),
            new Class<?>[]{ViewerHost.class}, handler);
    }

    private static <T> T allocate(Class<T> type) throws ReflectiveOperationException {
        Field field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        Method allocateInstance = unsafe.getClass().getMethod("allocateInstance", Class.class);
        return type.cast(allocateInstance.invoke(unsafe, type));
    }

    private static void setInt(Object target, String name, int value)
        throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static int getInt(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(target);
    }

    /**
     * Render target that counts the calls of each {@link RenderTarget} method.
     */
    private static final class RecordingTarget implements RenderTarget {

        private int getSurfaceCalls;

        private int contentSizeCalls;

        private int lastContentWidth;

        private int lastContentHeight;

        private int frameRateCalls;

        private int lastFrameRate;

        private int renderedImages;

        private EncodedImageFrame lastFrame;

        @Override
        public Surface getSurface() {
            getSurfaceCalls++;
            return null;
        }

        @Override
        public void setContentSize(int width, int height) {
            contentSizeCalls++;
            lastContentWidth = width;
            lastContentHeight = height;
        }

        @Override
        public void setFrameRate(int frameRate) {
            frameRateCalls++;
            lastFrameRate = frameRate;
        }

        @Override
        public void renderEncodedImage(EncodedImageFrame frame) {
            renderedImages++;
            lastFrame = frame;
        }
    }
}
