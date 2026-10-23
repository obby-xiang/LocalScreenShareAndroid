package com.obby.android.localscreenshare.render;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * API-level branch of {@link FrameRatePolicy}, pinned without an Android runtime.
 *
 * <p>The platform method exists in two shapes: API 30 has the two-argument variant and API 31 adds
 * the change strategy that makes the request seamless. A pure JVM test passes the API level as an
 * argument, so the branch is decided by the same code the device runs. A missing platform method
 * throws {@link NoSuchMethodError} at run time; these tests are what keep the level gate honest.</p>
 */
public class FrameRatePolicyTest {

    @Test
    public void applyFrameRate_belowApi30DoesNothing() {
        RecordingApplier applier = new RecordingApplier();

        FrameRatePolicy.applyFrameRate(60, 29, applier);

        assertEquals(0, applier.calls);
    }

    @Test
    public void applyFrameRate_api30UsesTwoArgumentVariant() {
        RecordingApplier applier = new RecordingApplier();

        FrameRatePolicy.applyFrameRate(60, 30, applier);

        assertEquals(1, applier.calls);
        assertEquals(60, applier.lastFrameRate);
        assertFalse(applier.lastSeamless);
    }

    @Test
    public void applyFrameRate_api31UsesSeamlessVariant() {
        RecordingApplier applier = new RecordingApplier();

        FrameRatePolicy.applyFrameRate(24, 31, applier);

        assertEquals(1, applier.calls);
        assertEquals(24, applier.lastFrameRate);
        assertTrue(applier.lastSeamless);
    }

    @Test
    public void applyFrameRate_futureApiStaysSeamless() {
        RecordingApplier applier = new RecordingApplier();

        FrameRatePolicy.applyFrameRate(60, 35, applier);

        assertEquals(1, applier.calls);
        assertTrue(applier.lastSeamless);
    }

    @Test
    public void applyFrameRate_nonPositiveIsIgnored() {
        RecordingApplier applier = new RecordingApplier();

        FrameRatePolicy.applyFrameRate(0, 31, applier);
        FrameRatePolicy.applyFrameRate(-1, 31, applier);

        assertEquals(0, applier.calls);
    }

    @Test
    public void applyFrameRate_doesNotSwallowApplierFailures() {
        assertThrows(IllegalStateException.class, () -> FrameRatePolicy.applyFrameRate(60, 31,
                (frameRate, seamless) -> {
                    throw new IllegalStateException("platform rejection");
                }));
    }

    /**
     * Applier that records the last request and how often it was called.
     */
    private static final class RecordingApplier implements FrameRatePolicy.FrameRateApplier {

        private int calls;

        private int lastFrameRate;

        private boolean lastSeamless;

        @Override
        public void apply(int frameRate, boolean seamless) {
            calls++;
            lastFrameRate = frameRate;
            lastSeamless = seamless;
        }
    }
}
