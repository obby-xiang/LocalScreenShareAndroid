package com.obby.android.localscreenshare.core.net;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link DatagramReorderWindow}.
 *
 * <p>The window decides what a receiver spends a decryption on and what it counts as lost, so its
 * three answers - accept, duplicate, stale - are asserted at the boundaries where an off-by-one
 * would misclassify a whole window.</p>
 */
public class DatagramReorderWindowTest {

    private final DatagramReorderWindow window = new DatagramReorderWindow();

    @Test
    public void inOrderDatagrams_areAccepted() {
        for (long seq = 1L; seq <= 10L; seq++) {
            assertEquals(DatagramReorderWindow.Decision.ACCEPT, window.accept(seq));
        }

        assertEquals(10L, window.getHighWater());
        assertEquals(0L, window.getDuplicates());
        assertEquals(0L, window.getReordered());
        assertEquals(0L, window.getLost());
        assertTrue(window.isSeeded());
    }

    @Test
    public void repeatedDatagram_isADuplicate() {
        window.accept(1L);
        window.accept(2L);

        assertEquals(DatagramReorderWindow.Decision.DUPLICATE, window.accept(1L));
        assertEquals(1L, window.getDuplicates());
        assertEquals(2L, window.getHighWater());
    }

    @Test
    public void earlierDatagramInsideTheWindow_isAReorder() {
        window.accept(5L);

        assertEquals(DatagramReorderWindow.Decision.ACCEPT, window.accept(3L));

        assertEquals(1L, window.getReordered());
        assertEquals(5L, window.getHighWater());
        assertEquals(0L, window.getLost());
    }

    @Test
    public void datagramOlderThanTheWindow_isStale() {
        window.accept(100L);

        assertTrue(window.isStale(100L - DatagramReorderWindow.WINDOW_SIZE));
        assertEquals(DatagramReorderWindow.Decision.STALE,
                window.accept(100L - DatagramReorderWindow.WINDOW_SIZE));
        assertFalse(window.isStale(100L - DatagramReorderWindow.WINDOW_SIZE + 1L));
        assertEquals(1L, window.getStale());
    }

    @Test
    public void skippedSequenceNumbers_areCountedAsLost() {
        window.accept(1L);
        window.accept(4L);

        assertEquals(2L, window.getLost());
        assertEquals(4L, window.getHighWater());
    }

    @Test
    public void jumpBeyondTheWindow_clearsItAndCountsTheHoles() {
        window.accept(1L);
        window.accept(2L);

        assertEquals(DatagramReorderWindow.Decision.ACCEPT,
                window.accept(1L + DatagramReorderWindow.WINDOW_SIZE * 2L));

        // Everything between what was seen and the new sequence number is lost, and the old
        // window cannot describe anything any more.
        assertEquals(1L + DatagramReorderWindow.WINDOW_SIZE * 2L - 3L, window.getLost());
        assertTrue(window.isStale(1L));
    }

    @Test
    public void windowBoundary_keepsTheOldestRememberedSequence() {
        long first = 500L;
        window.accept(first);
        window.accept(first + DatagramReorderWindow.WINDOW_SIZE - 1L);

        assertEquals(DatagramReorderWindow.Decision.DUPLICATE, window.accept(first));

        assertEquals(DatagramReorderWindow.Decision.ACCEPT,
                window.accept(first + DatagramReorderWindow.WINDOW_SIZE * 2L));
        assertTrue(window.isStale(first));
    }

    @Test
    public void windowStartsUnseeded() {
        assertFalse(window.isSeeded());
        assertFalse(window.isStale(0L));
        assertEquals(0L, window.getHighWater());
    }
}
