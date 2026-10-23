package com.obby.android.localscreenshare.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.obby.android.localscreenshare.core.model.StreamFeedback;

import org.junit.Test;

/**
 * Unit tests for {@link BacklogFeedback}.
 */
public class BacklogFeedbackTest {

    @Test
    public void sample_reportsThePeakQueueDepth() {
        BacklogFeedback feedback = new BacklogFeedback();
        feedback.onQueueDepth(3);
        feedback.onQueueDepth(7);
        feedback.onQueueDepth(2);

        assertEquals(7, feedback.sampleAndReset().getQueueDepthPeak());
    }

    @Test
    public void sample_convertsThePeakWaitToMilliseconds() {
        BacklogFeedback feedback = new BacklogFeedback();
        feedback.onQueueWait(4_000_000L);
        feedback.onQueueWait(12_600_000L);
        feedback.onQueueWait(900_000L);

        assertEquals(13, feedback.sampleAndReset().getQueueWaitPeakMs());
    }

    @Test
    public void sample_reportsTheCatchUpCount() {
        BacklogFeedback feedback = new BacklogFeedback();
        feedback.onCatchUp();
        feedback.onCatchUp();
        feedback.onCatchUp();

        assertEquals(3, feedback.sampleAndReset().getCatchUpEvents());
    }

    @Test
    public void sampleAndReset_clearsEveryCounter() {
        BacklogFeedback feedback = new BacklogFeedback();
        feedback.onQueueDepth(5);
        feedback.onQueueWait(20_000_000L);
        feedback.onCatchUp();
        feedback.sampleAndReset();

        StreamFeedback empty = feedback.sampleAndReset();
        assertEquals(0, empty.getQueueDepthPeak());
        assertEquals(0, empty.getCatchUpEvents());
        assertEquals(0, empty.getQueueWaitPeakMs());
    }

    @Test
    public void emptySnapshot_isAllZero() {
        StreamFeedback empty = new BacklogFeedback().sampleAndReset();

        assertEquals(0, empty.getQueueDepthPeak());
        assertEquals(0, empty.getCatchUpEvents());
        assertEquals(0, empty.getQueueWaitPeakMs());
    }

    @Test
    public void hasActivity_requiresDepthOrCatchUp() {
        BacklogFeedback feedback = new BacklogFeedback();
        assertFalse(feedback.hasActivity());

        feedback.onQueueWait(50_000_000L);
        assertFalse(feedback.hasActivity());

        feedback.onQueueDepth(1);
        assertTrue(feedback.hasActivity());

        feedback.sampleAndReset();
        assertFalse(feedback.hasActivity());
        feedback.onCatchUp();
        assertTrue(feedback.hasActivity());
    }
}
