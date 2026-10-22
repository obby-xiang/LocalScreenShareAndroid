package com.obby.android.localscreenshare.core.net;

import com.obby.android.localscreenshare.core.model.CodecConfigPart;
import com.obby.android.localscreenshare.core.support.MutableNanoClock;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link AccessUnitReassembler}.
 *
 * <p>The reassembler is where a lossy path turns into either a frame or a counted drop, so the
 * tests pin both sides: a complete unit is delivered immediately and exactly once, and an
 * incomplete one is never handed on - it waits for its hold-up, for a newer unit, or for room.</p>
 */
public class AccessUnitReassemblerTest {

    private final MutableNanoClock clock = new MutableNanoClock();

    private final RecordingListener listener = new RecordingListener();

    private final AccessUnitReassembler reassembler =
            new AccessUnitReassembler(listener, clock);

    @Test
    public void singleFragment_isDeliveredImmediately() {
        reassembler.onPacket(packet(1, 0, 0, 60));

        assertEquals(1, listener.frames.size());
        assertEquals(1L, reassembler.getDeliveredCount());
        assertEquals(0, reassembler.getInFlightCount());
        assertEquals(60, listener.frames.get(0).getPayloadLength());
    }

    @Test
    public void multiFragment_isDeliveredWhenTheLastFragmentLands() {
        int size = 2 * UdpMediaPacketCodec.MAX_FRAGMENT_BYTES + 10;
        assertEquals(3, UdpMediaPacketCodec.fragmentCount(size));

        reassembler.onPacket(packet(2, 0, 2, size));
        reassembler.onPacket(packet(2, 1, 0, size));

        assertEquals(0, listener.frames.size());
        assertEquals(1, reassembler.getInFlightCount());

        reassembler.onPacket(packet(2, 2, 1, size));

        assertEquals(1, listener.frames.size());
        assertEquals(size, listener.frames.get(0).getPayloadLength());
        assertEquals(0, reassembler.getInFlightCount());
    }

    @Test
    public void duplicateFragment_doesNotCompleteTheUnitEarly() {
        int size = 2 * UdpMediaPacketCodec.MAX_FRAGMENT_BYTES;

        reassembler.onPacket(packet(3, 0, 0, size));
        reassembler.onPacket(packet(3, 1, 0, size));

        assertEquals(0, listener.frames.size());
        assertEquals(1, reassembler.getInFlightCount());

        reassembler.onPacket(packet(3, 2, 1, size));
        assertEquals(1, listener.frames.size());
    }

    @Test
    public void missingFragment_isDroppedOnTheHoldUp() {
        int size = 2 * UdpMediaPacketCodec.MAX_FRAGMENT_BYTES;
        reassembler.onPacket(packet(4, 0, 0, size));

        clock.advance(AccessUnitReassembler.HOLD_UP_NS - 1L);
        reassembler.onTick();
        assertEquals(1, reassembler.getInFlightCount());

        clock.advance(1L);
        reassembler.onTick();

        assertEquals(0, reassembler.getInFlightCount());
        assertEquals(1L, reassembler.getDroppedCount());
        assertEquals(1, listener.dropped.size());
        assertEquals("timeout", listener.dropped.get(0).reason);
        assertEquals(0, listener.frames.size());
    }

    @Test
    public void newerDelivery_supersedesAnOlderIncompleteUnit() {
        int size = 2 * UdpMediaPacketCodec.MAX_FRAGMENT_BYTES;
        reassembler.onPacket(packet(5, 0, 0, size));

        reassembler.onPacket(packet(6, 1, 0, 10));

        assertEquals(1, listener.frames.size());
        assertEquals(1L, reassembler.getDroppedCount());
        assertEquals("superseded", listener.dropped.get(0).reason);
        assertEquals(5, listener.dropped.get(0).auId);
    }

    @Test
    public void inFlightLimit_evictsTheOldestUnit() {
        int size = 2 * UdpMediaPacketCodec.MAX_FRAGMENT_BYTES;
        for (int i = 0; i < AccessUnitReassembler.MAX_IN_FLIGHT; i++) {
            reassembler.onPacket(packet(i, i, 0, size));
        }
        assertEquals(AccessUnitReassembler.MAX_IN_FLIGHT, reassembler.getInFlightCount());

        reassembler.onPacket(packet(99, 99, 0, size));

        assertEquals(AccessUnitReassembler.MAX_IN_FLIGHT, reassembler.getInFlightCount());
        assertEquals(1L, reassembler.getDroppedCount());
        assertEquals("evicted", listener.dropped.get(0).reason);
        assertEquals(0, listener.dropped.get(0).auId);
    }

    @Test
    public void byteBudget_evictsTheOldestUnit() {
        int size = UdpMediaPacketCodec.MAX_AU_BYTES;
        for (int i = 0; i < 3; i++) {
            reassembler.onPacket(packet(i, i, 0, size));
        }

        // Two 4 MiB units fit the 8 MiB budget; the third pushes the oldest out.
        assertEquals(2, reassembler.getInFlightCount());
        assertEquals(1L, reassembler.getDroppedCount());
        assertEquals(0, listener.dropped.get(0).auId);
        assertEquals("evicted", listener.dropped.get(0).reason);
    }

    @Test
    public void fragmentOfADeliveredUnit_isLate() {
        reassembler.onPacket(packet(7, 0, 0, 10));

        reassembler.onPacket(packet(7, 1, 0, 10));

        assertEquals(1L, reassembler.getLateCount());
        assertEquals(1, listener.frames.size());
    }

    @Test
    public void joinPointOfTheUnit_reachesTheFrame() {
        reassembler.onPacket(packet(8, 0, 0, 8));

        assertTrue(listener.frames.get(0).isJoinPoint());
        assertTrue(listener.frames.get(0).isSyncPoint());
        assertNotNull(listener.frames.get(0).getJoinParts());
        assertTrue(listener.frames.get(0).getJoinParts().isEmpty());
    }

    @Test
    public void parityFragment_isIgnored() {
        UdpMediaPacket parity = packet(9, 0, 0, 8);

        reassembler.onPacket(new UdpMediaPacket(parity.getWireSeq(), parity.getAuId(),
                parity.getFragmentIndex(), parity.getFragmentCount(), parity.getPtsUs(),
                parity.getAuSize(), parity.getConfigEpoch(), parity.isConfig(),
                parity.isSyncPoint(), parity.isJoinPoint(), true, parity.getFragmentOffset(),
                parity.getFragment(), parity.getJoinParts()));

        assertEquals(0, reassembler.getDeliveredCount());
        assertEquals(0, reassembler.getInFlightCount());
        assertEquals(0L, reassembler.getDroppedCount());
    }

    /**
     * Builds a packet of the fragment layout the codec produces, with deterministic bytes.
     */
    private UdpMediaPacket packet(int auId, long wireSeq, int index, int auSize) {
        return packet(auId, wireSeq, index, auSize, null);
    }

    private UdpMediaPacket packet(int auId, long wireSeq, int index, int auSize,
                                  List<CodecConfigPart> joinParts) {
        int count = UdpMediaPacketCodec.fragmentCount(auSize);
        int length = UdpMediaPacketCodec.lengthOf(auSize, index);
        byte[] fragment = new byte[length];
        Arrays.fill(fragment, (byte) (index + 1));
        return new UdpMediaPacket(wireSeq, auId, index, count, index * 33_000L, auSize, auId,
                false, true, true, false, index * UdpMediaPacketCodec.MAX_FRAGMENT_BYTES,
                fragment, joinParts);
    }

    /**
     * Records what the reassembler delivered and dropped.
     */
    private static final class RecordingListener implements AccessUnitReassembler.Listener {

        private final List<MediaFrame> frames = new ArrayList<>();

        private final List<Drop> dropped = new ArrayList<>();

        @Override
        public void onFrame(MediaFrame frame) {
            frames.add(frame);
        }

        @Override
        public void onAccessUnitDropped(int auId, String reason) {
            dropped.add(new Drop(auId, reason));
        }
    }

    /**
     * One drop with its reason.
     */
    private static final class Drop {

        private final int auId;

        private final String reason;

        private Drop(int auId, String reason) {
            this.auId = auId;
            this.reason = reason;
        }
    }
}
