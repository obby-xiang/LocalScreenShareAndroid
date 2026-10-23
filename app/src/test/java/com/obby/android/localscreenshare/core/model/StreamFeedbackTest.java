package com.obby.android.localscreenshare.core.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

/**
 * Unit tests for {@link StreamFeedback}.
 */
public class StreamFeedbackTest {

    @Test
    public void constructor_rejectsNegativeValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new StreamFeedback(-1, 0, 0, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new StreamFeedback(0, -1, 0, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new StreamFeedback(0, 0, -1, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new StreamFeedback(0, 0, 0, -1L));
    }

    @Test
    public void empty_isAllZero() {
        assertEquals(0, StreamFeedback.EMPTY.getQueueDepthPeak());
        assertEquals(0, StreamFeedback.EMPTY.getCatchUpEvents());
        assertEquals(0, StreamFeedback.EMPTY.getQueueWaitPeakMs());
        assertEquals(0L, StreamFeedback.EMPTY.getClientUptimeMs());
    }

    @Test
    public void getters_returnTheConstructedValues() {
        StreamFeedback feedback = new StreamFeedback(7, 2, 350, 123_456L);

        assertEquals(7, feedback.getQueueDepthPeak());
        assertEquals(2, feedback.getCatchUpEvents());
        assertEquals(350, feedback.getQueueWaitPeakMs());
        assertEquals(123_456L, feedback.getClientUptimeMs());
    }

    @Test
    public void value_isImmutableAcrossInstances() {
        StreamFeedback first = new StreamFeedback(1, 0, 10, 1L);
        StreamFeedback second = new StreamFeedback(1, 0, 10, 1L);
        StreamFeedback other = new StreamFeedback(2, 0, 10, 1L);

        assertNotSame(first, second);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(2, other.getQueueDepthPeak());
    }
}
