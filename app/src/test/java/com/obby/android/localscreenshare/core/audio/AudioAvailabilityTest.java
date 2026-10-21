package com.obby.android.localscreenshare.core.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * Unit tests for {@link AudioAvailability}.
 */
public class AudioAvailabilityTest {

    @Test
    public void apiLevelGatesCaptureAtAndroidTen() {
        assertEquals(AudioAvailability.API_UNSUPPORTED, AudioAvailability.forApiLevel(25));
        assertEquals(AudioAvailability.API_UNSUPPORTED, AudioAvailability.forApiLevel(28));
        assertEquals(AudioAvailability.AVAILABLE, AudioAvailability.forApiLevel(29));
        assertEquals(AudioAvailability.AVAILABLE, AudioAvailability.forApiLevel(35));
    }

    @Test
    public void captureFailuresAreClassified() {
        assertEquals(AudioAvailability.PERMISSION_DENIED, AudioAvailability.fromFailure(
                AudioCaptureException.Reason.PERMISSION_DENIED));
        assertEquals(AudioAvailability.API_UNSUPPORTED, AudioAvailability.fromFailure(
                AudioCaptureException.Reason.UNSUPPORTED));
        assertEquals(AudioAvailability.CAPTURE_FAILED, AudioAvailability.fromFailure(
                AudioCaptureException.Reason.DEVICE_BUSY));
        assertEquals(AudioAvailability.CAPTURE_FAILED, AudioAvailability.fromFailure(
                AudioCaptureException.Reason.INTERNAL));
        assertEquals(AudioAvailability.ENCODER_FAILED, AudioAvailability.fromFailure(
                AudioCaptureException.Reason.ENCODER_FAILED));
        assertEquals(AudioAvailability.AVAILABLE, AudioAvailability.fromFailure(null));
    }

    @Test
    public void onlyTheRecoverableReasonsAreRetryable() {
        assertTrue(AudioAvailability.PERMISSION_DENIED.isRetryable());
        assertTrue(AudioAvailability.CAPTURE_FAILED.isRetryable());
        assertTrue(AudioAvailability.ENCODER_FAILED.isRetryable());
        assertFalse(AudioAvailability.API_UNSUPPORTED.isRetryable());
        assertFalse(AudioAvailability.NO_VIEWERS.isRetryable());
        assertFalse(AudioAvailability.CLIENT_UNSUPPORTED.isRetryable());
        assertFalse(AudioAvailability.AVAILABLE.isRetryable());
    }

    @Test
    public void availableHasNoExplanationAndEveryOtherReasonHasOne() {
        assertNull(AudioAvailability.AVAILABLE.getStringKey());
        Set<String> keys = new HashSet<>();
        for (AudioAvailability availability : AudioAvailability.values()) {
            if (availability == AudioAvailability.AVAILABLE) {
                continue;
            }
            assertNotNull(availability.getStringKey());
            assertTrue(keys.add(availability.getStringKey()));
        }
        assertEquals(AudioAvailability.values().length - 1, keys.size());
    }
}
