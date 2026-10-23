package com.obby.android.localscreenshare.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Surface lifecycle of {@link SurfaceRenderTarget}, pinned without a device.
 *
 * <p>A JVM has no platform surface, so the test uses the package-private seam of the target to
 * supply a marker surface and a stub texture view. The regression pinned here is the replay of the
 * content frame-rate hint onto a newly created surface: the target records the value it last
 * asserted, and {@link SurfaceRenderTarget#getSurface()} must clear that record when it creates a
 * surface. Otherwise the equal-value early return in {@code applyFrameRate} would survive into the
 * new surface and the hint would never be replayed. A real platform call still needs a device.</p>
 */
public class SurfaceRenderTargetTest {

    @Test
    public void getSurface_newSurfaceDoesNotInheritThePreviousAppliedValue()
            throws ReflectiveOperationException {
        SurfaceTexture texture = allocate(SurfaceTexture.class);
        StubTextureView view = allocate(StubTextureView.class);
        view.texture = texture;
        Surface surface = allocate(Surface.class);
        SurfaceRenderTarget target = new SurfaceRenderTarget(view, null, ignored -> surface);
        target.setFrameRate(60);

        // Model a recreated surface while the hint is unchanged: the texture is the same, so
        // getSurface() only creates the surface again. The applied value still belongs to the
        // previous surface and must not make applyFrameRate() return early.
        setField(target, "lastTexture", texture);
        setField(target, "appliedFrameRate", 60);

        assertSame(surface, target.getSurface());
        assertEquals("a new surface must not keep the previous applied value", 0,
                getInt(target, "appliedFrameRate"));
    }

    private static void setField(Object target, String name, Object value)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static int getInt(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static <T> T allocate(Class<T> type) throws ReflectiveOperationException {
        Field field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        Method allocateInstance = unsafe.getClass().getMethod("allocateInstance", Class.class);
        return type.cast(allocateInstance.invoke(unsafe, type));
    }

    /**
     * Texture view that returns an in-memory texture and ignores listener registration.
     *
     * <p>Allocated without its constructor: the platform view constructors cannot run on a JVM.</p>
     */
    private static final class StubTextureView extends TextureView {

        private SurfaceTexture texture;

        private StubTextureView() {
            super((Context) null);
        }

        @Override
        public SurfaceTexture getSurfaceTexture() {
            return texture;
        }

        @Override
        public void setSurfaceTextureListener(SurfaceTextureListener listener) {
        }
    }
}
