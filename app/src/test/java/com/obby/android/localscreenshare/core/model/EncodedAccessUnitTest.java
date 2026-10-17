package com.obby.android.localscreenshare.core.model;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class EncodedAccessUnitTest {

    private static StreamKey streamKey() {
        return new StreamKey("session-instance-1", "stream-1");
    }

    private static EncodedAccessUnit newAccessUnit(int configEpoch, long sequence, long ptsUs,
                                                   long dtsUs, AccessUnitFlags flags,
                                                   JoinInfo joinInfo, Integer temporalLayerId,
                                                   byte[] payload) {
        return new EncodedAccessUnit(streamKey(), configEpoch, sequence, ptsUs, dtsUs,
                flags, joinInfo, temporalLayerId, payload);
    }

    private static EncodedAccessUnit plainAccessUnit() {
        return newAccessUnit(1, 0, 0, EncodedAccessUnit.DTS_UNSET,
                AccessUnitFlags.none(), null, null, new byte[]{0x01, 0x02, 0x03});
    }

    @Test
    public void constructor_rejectsNullPayload() {
        assertThrows(IllegalArgumentException.class, () -> newAccessUnit(1, 0, 0,
                EncodedAccessUnit.DTS_UNSET, AccessUnitFlags.none(), null, null, null));
    }

    @Test
    public void constructor_rejectsEmptyPayload() {
        assertThrows(IllegalArgumentException.class, () -> newAccessUnit(1, 0, 0,
                EncodedAccessUnit.DTS_UNSET, AccessUnitFlags.none(), null, null, new byte[0]));
    }

    @Test
    public void constructor_requiresJoinInfoWhenJoinPoint() {
        AccessUnitFlags joinPointFlags = AccessUnitFlags.of(true, true, false);

        assertThrows(IllegalArgumentException.class, () -> newAccessUnit(1, 0, 0,
                EncodedAccessUnit.DTS_UNSET, joinPointFlags, null, null, new byte[]{0x01}));
    }

    @Test
    public void constructor_rejectsJoinInfoEpochMismatch() {
        AccessUnitFlags joinPointFlags = AccessUnitFlags.of(true, true, false);
        JoinInfo staleJoinInfo = new JoinInfo(2, Collections.singletonList(
                new CodecConfigPart("csd-0", new byte[]{0x01})));

        assertThrows(IllegalArgumentException.class, () -> newAccessUnit(1, 0, 0,
                EncodedAccessUnit.DTS_UNSET, joinPointFlags, staleJoinInfo, null,
                new byte[]{0x01}));
    }

    @Test
    public void constructor_rejectsNegativeSequencePtsOrDts() {
        assertThrows(IllegalArgumentException.class, () -> newAccessUnit(1, -1, 0,
                EncodedAccessUnit.DTS_UNSET, AccessUnitFlags.none(), null, null,
                new byte[]{0x01}));
        assertThrows(IllegalArgumentException.class, () -> newAccessUnit(1, 0, -1,
                EncodedAccessUnit.DTS_UNSET, AccessUnitFlags.none(), null, null,
                new byte[]{0x01}));
        assertThrows(IllegalArgumentException.class, () -> newAccessUnit(1, 0, 0,
                -1, AccessUnitFlags.none(), null, null, new byte[]{0x01}));
    }

    @Test
    public void configEpoch_isAlwaysReadable() {
        EncodedAccessUnit accessUnit = newAccessUnit(3, 7, 1_000, EncodedAccessUnit.DTS_UNSET,
                AccessUnitFlags.none(), null, null, new byte[]{0x01});

        assertEquals(3, accessUnit.getConfigEpoch());
        assertEquals(streamKey(), accessUnit.getStreamKey());
        assertEquals(7, accessUnit.getSequence());
        assertEquals(1_000, accessUnit.getPtsUs());
    }

    @Test
    public void sequence_isNotGloballyUniqueAcrossEpochs() {
        EncodedAccessUnit firstEpochUnit = newAccessUnit(1, 0, 0, EncodedAccessUnit.DTS_UNSET,
                AccessUnitFlags.none(), null, null, new byte[]{0x01});
        EncodedAccessUnit secondEpochUnit = newAccessUnit(2, 0, 0, EncodedAccessUnit.DTS_UNSET,
                AccessUnitFlags.none(), null, null, new byte[]{0x01});

        assertEquals(0, firstEpochUnit.getSequence());
        assertEquals(0, secondEpochUnit.getSequence());
        assertEquals(1, firstEpochUnit.getConfigEpoch());
        assertEquals(2, secondEpochUnit.getConfigEpoch());
        assertNotEquals(firstEpochUnit.getConfigEpoch(), secondEpochUnit.getConfigEpoch());
    }

    @Test
    public void payload_isDefensivelyCopied() {
        byte[] originalPayload = new byte[]{0x0a, 0x0b, 0x0c};
        EncodedAccessUnit accessUnit = newAccessUnit(1, 0, 0, EncodedAccessUnit.DTS_UNSET,
                AccessUnitFlags.none(), null, null, originalPayload);
        assertEquals(3, accessUnit.getPayloadLength());
        assertArrayEquals(new byte[]{0x0a, 0x0b, 0x0c}, accessUnit.getPayload());

        originalPayload[0] = 0x7f;
        assertArrayEquals(new byte[]{0x0a, 0x0b, 0x0c}, accessUnit.getPayload());

        byte[] fetchedPayload = accessUnit.getPayload();
        fetchedPayload[2] = 0x7f;
        assertArrayEquals(new byte[]{0x0a, 0x0b, 0x0c}, accessUnit.getPayload());
    }

    @Test
    public void dtsUnset_isReported() {
        EncodedAccessUnit withoutDts = newAccessUnit(1, 0, 0, EncodedAccessUnit.DTS_UNSET,
                AccessUnitFlags.none(), null, null, new byte[]{0x01});
        assertEquals(EncodedAccessUnit.DTS_UNSET, withoutDts.getDtsUs());
        assertFalse(withoutDts.hasDtsUs());

        EncodedAccessUnit withDts = newAccessUnit(1, 1, 33_000, 0,
                AccessUnitFlags.none(), null, null, new byte[]{0x01});
        assertEquals(0, withDts.getDtsUs());
        assertTrue(withDts.hasDtsUs());
    }

    @Test
    public void clientState_hasExactlySixStates() {
        Set<String> expectedNames = new HashSet<>();
        expectedNames.add("NEGOTIATING");
        expectedNames.add("WAIT_CONFIG");
        expectedNames.add("WAIT_RANDOM_ACCESS");
        expectedNames.add("STREAMING");
        expectedNames.add("RESYNCING");
        expectedNames.add("CLOSED");

        Set<String> actualNames = new HashSet<>();
        for (ClientState state : ClientState.values()) {
            assertNotNull(state);
            actualNames.add(state.name());
        }

        assertEquals(6, ClientState.values().length);
        assertEquals(expectedNames, actualNames);
    }
}
