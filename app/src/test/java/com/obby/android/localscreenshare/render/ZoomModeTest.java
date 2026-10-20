package com.obby.android.localscreenshare.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/**
 * Unit tests for {@link ZoomMode}.
 *
 * <p>The wire values are a persistence contract: an existing preference written by an older build
 * must keep its meaning, so the literals are asserted rather than only round-tripped.</p>
 */
public class ZoomModeTest {

    @Test
    public void wireValuesAreStable() {
        assertEquals("fit", ZoomMode.FIT.wire());
        assertEquals("one_to_one", ZoomMode.ONE_TO_ONE.wire());
        assertEquals("integer", ZoomMode.INTEGER.wire());
    }

    @Test
    public void fromWire_acceptsEveryWireValue() {
        for (ZoomMode mode : ZoomMode.values()) {
            assertSame(mode, ZoomMode.fromWire(mode.wire()));
        }
    }

    @Test
    public void fromWire_emptyMeansFit() {
        assertSame(ZoomMode.FIT, ZoomMode.fromWire(""));
    }

    @Test
    public void fromWire_nullMeansFit() {
        assertSame(ZoomMode.FIT, ZoomMode.fromWire(null));
    }

    @Test
    public void fromWire_unknownValueFallsBackToFit() {
        assertSame(ZoomMode.FIT, ZoomMode.fromWire("stretched"));
        assertSame(ZoomMode.FIT, ZoomMode.fromWire("ONE_TO_ONE"));
        assertSame(ZoomMode.FIT, ZoomMode.fromWire(" "));
    }
}
