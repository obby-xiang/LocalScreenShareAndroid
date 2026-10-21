package com.obby.android.localscreenshare.trust;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TofuPolicyTest {

    @Test
    public void matchingPinProceedsWithoutAsking() {
        assertEquals(TofuPolicy.Action.PROCEED, TofuPolicy.decide(PinPolicy.Outcome.MATCH));
    }

    @Test
    public void unknownDeviceAsksOnce() {
        assertEquals(TofuPolicy.Action.CONFIRM_FIRST, TofuPolicy.decide(PinPolicy.Outcome.UNKNOWN));
    }

    @Test
    public void changedFingerprintRequiresAnExplicitAcknowledgement() {
        assertEquals(TofuPolicy.Action.CONFIRM_CHANGE, TofuPolicy.decide(PinPolicy.Outcome.CHANGED));
    }

    @Test
    public void identityMismatchCannotBeConfirmedAway() {
        assertEquals(TofuPolicy.Action.BLOCK,
            TofuPolicy.decide(PinPolicy.Outcome.IDENTITY_MISMATCH));
    }

    @Test
    public void addressChangeIsDetectedOnlyWhenBothIdsAreKnown() {
        assertTrue(TofuPolicy.isAddressChanged("ID-A", "ID-B"));
        assertFalse(TofuPolicy.isAddressChanged("ID-A", "ID-A"));
        assertFalse(TofuPolicy.isAddressChanged(null, "ID-B"));
        assertFalse(TofuPolicy.isAddressChanged("ID-A", null));
        assertFalse(TofuPolicy.isAddressChanged("  ", "ID-B"));
    }
}
