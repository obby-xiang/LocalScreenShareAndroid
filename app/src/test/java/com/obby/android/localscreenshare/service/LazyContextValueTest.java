package com.obby.android.localscreenshare.service;

import android.content.Context;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * JVM tests for the deferred creation contract.
 *
 * <p>The device report that motivated this class: both services created their device sampler in a
 * field initialiser, which runs before {@code attachBaseContext}, so the sampler constructor read a
 * null base context and the framework failed to instantiate the service — every launch of the app
 * ended in {@code FATAL EXCEPTION}. The framework path cannot be exercised on the JVM, so the
 * contract enforced here is the part that made it fail: constructing the holder must not read the
 * context or create the value, and the first {@link LazyContextValue#get()} must do both exactly
 * once.</p>
 */
public class LazyContextValueTest {

    @Test
    public void construction_touchesNeitherTheContextNorTheFactory() {
        final List<String> calls = new ArrayList<>();

        new LazyContextValue<>(countingContextSupplier(calls), countingFactory(calls));

        assertEquals(Collections.emptyList(), calls);
    }

    @Test
    public void firstGet_resolvesTheContextBeforeCreatingTheValue() {
        final List<String> calls = new ArrayList<>();
        final LazyContextValue<Object> holder =
            new LazyContextValue<>(countingContextSupplier(calls), countingFactory(calls));

        final Object value = holder.get();

        assertEquals(Arrays.asList("context", "create"), calls);
        assertSame(holder.get(), value);
    }

    @Test
    public void repeatedGet_reusesTheContextAndTheValue() {
        final List<String> calls = new ArrayList<>();
        final LazyContextValue<Object> holder =
            new LazyContextValue<>(countingContextSupplier(calls), countingFactory(calls));

        final Object first = holder.get();
        final Object second = holder.get();
        final Object third = holder.get();

        assertEquals(Arrays.asList("context", "create"), calls);
        assertSame(first, second);
        assertSame(first, third);
    }

    /**
     * Returns a supplier that records the call; the returned context is null because no JVM test
     * can build a real one, and the holder never dereferences it.
     */
    private static LazyContextValue.ContextSupplier countingContextSupplier(
        final List<String> calls) {
        return () -> {
            calls.add("context");
            return null;
        };
    }

    /**
     * Returns a factory that records the call and hands back a fresh object.
     */
    private static LazyContextValue.Factory<Object> countingFactory(final List<String> calls) {
        return new LazyContextValue.Factory<Object>() {
            @Override
            public Object create(final Context context) {
                calls.add("create");
                return new Object();
            }
        };
    }
}
