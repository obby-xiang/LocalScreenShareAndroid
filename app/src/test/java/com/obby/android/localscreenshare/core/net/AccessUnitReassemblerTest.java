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

    /**
     * A unit whose fragments keep arriving over a window longer than the hold-up is assembled.
     *
     * <p>The hold-up bounds silence, not the lifetime of an assembly. A paced sender spreads a large
     * unit over hundreds of milliseconds - a JPEG compatibility frame of a screen needs dozens of
     * fragments - so a deadline anchored at the first fragment expires every such unit while its
     * fragments are still on the wire. The r2 device run showed exactly that: the viewer received no
     * frame at all while {@code udp.recv} climbed, {@code udp.lost} stayed low and
     * {@code udp.auIncomplete} grew by several drops per frame.</p>
     */
    @Test
    public void fragmentsSpanningMoreThanTheHoldUp_areStillAssembled() {
        int size = 20 * UdpMediaPacketCodec.MAX_FRAGMENT_BYTES;
        int count = UdpMediaPacketCodec.fragmentCount(size);
        for (int index = 0; index < count; index++) {
            reassembler.onPacket(packet(11, index + 1L, index, size));
            reassembler.onTick();
            clock.advance(AccessUnitReassembler.HOLD_UP_NS / 2L);
        }

        assertEquals("a paced unit must not expire while its fragments still arrive",
                1, listener.frames.size());
        assertEquals(size, listener.frames.get(0).getPayloadLength());
        assertEquals(0, reassembler.getDroppedCount());
        assertEquals(0, reassembler.getInFlightCount());
    }

    /**
     * Silence, by contrast, is still what ends an unfinished unit: the wait restarts with every
     * fragment, so a unit that stops half-way is dropped one hold-up later.
     */
    @Test
    public void silenceAfterProgress_isDroppedOnTheHoldUp() {
        int size = 3 * UdpMediaPacketCodec.MAX_FRAGMENT_BYTES;
        reassembler.onPacket(packet(12, 1, 0, size));
        clock.advance(AccessUnitReassembler.HOLD_UP_NS / 2L);
        reassembler.onPacket(packet(12, 2, 1, size));

        clock.advance(AccessUnitReassembler.HOLD_UP_NS - 1L);
        reassembler.onTick();
        assertEquals("the hold-up restarts with the last fragment",
                1, reassembler.getInFlightCount());

        clock.advance(1L);
        reassembler.onTick();
        assertEquals(0, reassembler.getInFlightCount());
        assertEquals(1, listener.dropped.size());
        assertEquals("timeout", listener.dropped.get(0).reason);
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

    /**
     * A unit abandoned for silence is remembered: its residue is late, not a new unit.
     *
     * <p>The fragments an abandoned unit lost are gone, so no later fragment of that id can ever
     * complete it. Counting the residue as a late datagram is also what keeps the drop count at one
     * per unit: the device report measured 2.4-2.7 timeouts for every unit that really lost a
     * fragment, because the residue rebuilt the unit, its buffer and its countdown.</p>
     */
    @Test
    public void fragmentsAfterTimeout_areOnlyCountedLate() {
        int size = 4 * UdpMediaPacketCodec.MAX_FRAGMENT_BYTES;
        reassembler.onPacket(packet(21, 0, 0, size));

        clock.advance(AccessUnitReassembler.HOLD_UP_NS);
        reassembler.onTick();

        assertEquals(1, listener.dropped.size());
        assertEquals("timeout", listener.dropped.get(0).reason);

        reassembler.onPacket(packet(21, 1, 1, size));
        reassembler.onPacket(packet(21, 2, 2, size));
        reassembler.onPacket(packet(21, 3, 3, size));

        assertEquals("an abandoned unit is counted once", 1, listener.dropped.size());
        assertEquals(1L, reassembler.getDroppedCount());
        assertEquals(3L, reassembler.getLateCount());
        assertEquals("the residue must not rebuild a buffer", 0, reassembler.getInFlightCount());
        assertEquals(0, listener.frames.size());
    }

    /**
     * The residue of an abandoned unit does not start a second countdown of its own.
     */
    @Test
    public void fragmentsAfterTimeout_doNotStartASecondCountdown() {
        int size = 4 * UdpMediaPacketCodec.MAX_FRAGMENT_BYTES;
        reassembler.onPacket(packet(22, 0, 0, size));
        clock.advance(AccessUnitReassembler.HOLD_UP_NS);
        reassembler.onTick();
        assertEquals(1, listener.dropped.size());

        reassembler.onPacket(packet(22, 1, 1, size));
        clock.advance(AccessUnitReassembler.HOLD_UP_NS);
        reassembler.onTick();

        assertEquals("no second timeout of the same unit", 1, listener.dropped.size());
        assertEquals(1L, reassembler.getDroppedCount());
        assertEquals(0, reassembler.getInFlightCount());
    }

    /**
     * A unit evicted for room is abandoned just like a silent one.
     */
    @Test
    public void evictedUnit_residueIsIgnored() {
        int size = 2 * UdpMediaPacketCodec.MAX_FRAGMENT_BYTES;
        for (int i = 0; i < AccessUnitReassembler.MAX_IN_FLIGHT; i++) {
            reassembler.onPacket(packet(i, i, 0, size));
        }
        reassembler.onPacket(packet(99, 99, 0, size));
        assertEquals(1, listener.dropped.size());
        assertEquals("evicted", listener.dropped.get(0).reason);

        reassembler.onPacket(packet(0, 100, 1, size));

        assertEquals("the evicted unit must not be rebuilt", 1, listener.dropped.size());
        assertEquals(1L, reassembler.getLateCount());
        assertEquals(AccessUnitReassembler.MAX_IN_FLIGHT, reassembler.getInFlightCount());
    }

    /**
     * An oversized unit is unusable whatever fragment arrives, so it is counted once.
     */
    @Test
    public void oversizedUnit_isCountedOncePerUnit() {
        reassembler.onPacket(oversizedPacket(31, 0));
        reassembler.onPacket(oversizedPacket(31, 1));

        assertEquals(1, listener.dropped.size());
        assertEquals("oversized", listener.dropped.get(0).reason);
        assertEquals(1L, reassembler.getLateCount());
        assertEquals(0, reassembler.getInFlightCount());
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
     * Builds a packet whose declared unit size no sender may announce.
     *
     * <p>The layout helpers reject such a size, which is the point: only a broken or hostile sender
     * produces one, so the packet is assembled by hand.</p>
     */
    private UdpMediaPacket oversizedPacket(int auId, long wireSeq) {
        return new UdpMediaPacket(wireSeq, auId, 0, 1, 0L,
                UdpMediaPacketCodec.MAX_AU_BYTES + 1, auId, false, true, true, false, 0,
                new byte[8], null);
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
