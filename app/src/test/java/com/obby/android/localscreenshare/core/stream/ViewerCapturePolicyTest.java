package com.obby.android.localscreenshare.core.stream;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for {@link ViewerCapturePolicy}.
 *
 * <p>The cases are the ones the service actually reaches: a share nobody watches, the first
 * viewer, a viewer leaving again, a stream whose encoder has not published its configuration yet,
 * and an owner that does not know the viewer count.</p>
 */
public class ViewerCapturePolicyTest {

    @Test
    public void readyStream_withNoViewer_pausesCapture() {
        assertEquals(ViewerCapturePolicy.Action.PAUSE,
                ViewerCapturePolicy.decide(0, false, true));
    }

    @Test
    public void readyStream_withViewer_resumesPausedCapture() {
        assertEquals(ViewerCapturePolicy.Action.RESUME,
                ViewerCapturePolicy.decide(1, true, true));
    }

    @Test
    public void readyStream_withViewer_keepsRunningCapture() {
        assertEquals(ViewerCapturePolicy.Action.NONE,
                ViewerCapturePolicy.decide(2, false, true));
    }

    @Test
    public void readyStream_withNoViewer_keepsPausedCapture() {
        assertEquals(ViewerCapturePolicy.Action.NONE,
                ViewerCapturePolicy.decide(0, true, true));
    }

    @Test
    public void unreadyStream_neverPausesCapture() {
        assertEquals(ViewerCapturePolicy.Action.NONE,
                ViewerCapturePolicy.decide(0, false, false));
        assertEquals(ViewerCapturePolicy.Action.NONE,
                ViewerCapturePolicy.decide(1, false, false));
    }

    @Test
    public void unknownViewerCount_keepsCurrentState() {
        assertEquals(ViewerCapturePolicy.Action.NONE,
                ViewerCapturePolicy.decide(-1, false, true));
        assertEquals(ViewerCapturePolicy.Action.NONE,
                ViewerCapturePolicy.decide(-1, true, true));
    }
}
