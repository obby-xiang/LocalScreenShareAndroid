package com.obby.android.localscreenshare.core.codec;

import com.obby.android.localscreenshare.core.support.FakeCodec;

import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CodecRegistryTest {

    private final CodecRegistry registry = new CodecRegistry();

    @Test
    public void newRegistry_hasNoCodecs() {
        assertTrue(registry.getCodecIds().isEmpty());
        assertTrue(registry.getRegistrations().isEmpty());
    }

    @Test
    public void register_rejectsDuplicateCodecId() {
        FakeCodec first = new FakeCodec("fake", 0, false, false);
        FakeCodec duplicate = new FakeCodec("fake", 5, false, false);
        registry.register(first.registration());

        assertThrows(IllegalStateException.class, () -> registry.register(duplicate.registration()));
        assertEquals(1, registry.getRegistrations().size());
    }

    @Test
    public void register_rejectsBlankCodecId() {
        assertThrows(IllegalArgumentException.class, () -> {
            FakeCodec blank = new FakeCodec("   ", 0, false, false);
            registry.register(blank.registration());
        });
        assertTrue(registry.getRegistrations().isEmpty());
    }

    @Test
    public void require_unknownCodecThrowsWithCodecId() {
        CodecNotFoundException error = assertThrows(CodecNotFoundException.class,
                () -> registry.require("missing-codec"));

        assertEquals("missing-codec", error.getCodecId());
        assertTrue(error.getMessage() != null && error.getMessage().contains("missing-codec"));
    }

    @Test
    public void getCodecIds_isUnmodifiable() {
        registry.register(new FakeCodec("fake", 0, false, false).registration());

        Set<String> codecIds = registry.getCodecIds();

        assertEquals(1, codecIds.size());
        assertTrue(codecIds.contains("fake"));
        assertThrows(UnsupportedOperationException.class, () -> codecIds.add("other"));
    }

    @Test
    public void getRegistrations_preservesFallbackPriorityMetadata() {
        FakeCodec later = new FakeCodec("zeta", 10, false, false);
        FakeCodec earlier = new FakeCodec("alpha", 0, false, false);
        registry.register(later.registration());
        registry.register(earlier.registration());

        List<CodecRegistration> registrations = registry.getRegistrations();

        assertEquals(2, registrations.size());
        assertTrue(registrations.contains(later.registration()));
        assertTrue(registrations.contains(earlier.registration()));
        assertEquals(10, registry.require("zeta").getFallbackPriority());
        assertEquals(0, registry.require("alpha").getFallbackPriority());
    }

    @Test
    public void concurrentLookups_doNotThrow() throws Exception {
        registry.register(new FakeCodec("fake", 0, false, false).registration());

        int threadCount = 8;
        int lookupsPerThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            for (int i = 0; i < threadCount; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < lookupsPerThread; j++) {
                            registry.require("fake");
                        }
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue("concurrent lookups did not finish",
                    done.await(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        if (failure.get() != null) {
            throw new AssertionError("concurrent require failed", failure.get());
        }
    }
}
