package com.obby.android.localscreenshare.core.audio;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.IOException;

/**
 * Unit tests for {@link AudioCaptureException#classify(Throwable)}.
 *
 * <p>The classification is what the user sees: a revoked recording permission has to read as
 * "permission denied" instead of a generic failure, a device that went away has to read as busy, and
 * an unsupported device has to read as unsupported. The mapping is also the contract the playback
 * capture source relies on after a {@link SecurityException} from {@code AudioRecord.read}.</p>
 */
public class AudioCaptureExceptionTest {

    @Test
    public void securityException_mapsToPermissionDenied() {
        assertEquals(AudioCaptureException.Reason.PERMISSION_DENIED,
            AudioCaptureException.classify(new SecurityException("record denied")));
    }

    @Test
    public void illegalStateException_mapsToDeviceBusy() {
        assertEquals(AudioCaptureException.Reason.DEVICE_BUSY,
            AudioCaptureException.classify(new IllegalStateException("device invalid")));
    }

    @Test
    public void unsupportedFailures_mapToUnsupported() {
        assertEquals(AudioCaptureException.Reason.UNSUPPORTED,
            AudioCaptureException.classify(new UnsupportedOperationException("no playback capture")));
        assertEquals(AudioCaptureException.Reason.UNSUPPORTED,
            AudioCaptureException.classify(new IllegalArgumentException("bad format")));
    }

    @Test
    public void otherFailures_mapToInternal() {
        assertEquals(AudioCaptureException.Reason.INTERNAL,
            AudioCaptureException.classify(new RuntimeException("unexpected")));
        assertEquals(AudioCaptureException.Reason.INTERNAL,
            AudioCaptureException.classify(new IOException("io failure")));
    }

    @Test
    public void null_mapsToInternal() {
        assertEquals(AudioCaptureException.Reason.INTERNAL, AudioCaptureException.classify(null));
    }
}
