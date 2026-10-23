package com.obby.android.localscreenshare.core.net;

import com.obby.android.localscreenshare.core.model.CodecConfigPart;
import com.obby.android.localscreenshare.core.util.NanoClock;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds access units from datagram fragments, and refuses to deliver a partial one.
 *
 * <p>Fragments of several units can be in flight at once because datagrams are independent, so the
 * reassembler keeps at most {@value #MAX_IN_FLIGHT} units, each with one buffer of exactly the size
 * the sender declared and a bitmap of the fragments it already holds. A single-fragment unit is
 * delivered on arrival; a multi-fragment unit is delivered the moment its last fragment lands.</p>
 *
 * <p><b>Giving up.</b> An incomplete unit is dropped whole - never handed on half-built - in three
 * cases: it has been silent for {@value #HOLD_UP_MS} milliseconds, a newer unit was delivered and
 * made it useless, or the buffer budget forced the oldest unit out. Silence is measured from the
 * <i>last</i> fragment, not from the first one: a sender paces its fragments, so a unit that keeps
 * arriving is making progress however long its assembly takes, and the hold-up only decides when a
 * stalled unit is hopeless. Every drop is reported with its reason so the caller can count it and
 * decide whether to ask for a key frame. State from a fragment of an already delivered unit is
 * counted as late and ignored.</p>
 *
 * <p><b>Why the hold-up and not "a newer id arrived".</b> A newer unit's first fragment may overtake
 * the previous unit's last one on the wire: datagrams of different units are unrelated. Dropping on
 * the first newer id would therefore throw away a nearly complete unit whenever two packets swapped
 * places. The hold-up bounds the wait instead, and a delivered newer unit is the only event that
 * makes an older pending unit pointless.</p>
 *
 * <p>Memory is bounded twice: by the number of units in flight and by the total bytes of their
 * buffers, so a sender that announces large units cannot grow the receiver without limit.</p>
 *
 * <p>Not thread safe; the receiver thread owns it and drives {@link #onTick()}.</p>
 */
public final class AccessUnitReassembler {

    /**
     * Maximum number of access units assembled at the same time.
     */
    public static final int MAX_IN_FLIGHT = 4;

    /**
     * Maximum total payload bytes held by the units in flight.
     */
    public static final long MAX_IN_FLIGHT_BYTES = 8L * 1024L * 1024L;

    /**
     * How long an unfinished unit may stay silent before it is abandoned, in milliseconds.
     *
     * <p>Silence is measured from the last fragment that arrived, not from the first one. Anchoring
     * the deadline at the first fragment makes the boundary a limit on the whole assembly, which
     * only works for units small enough to land in one burst: a JPEG compatibility frame of a
     * screen is about six times the size of an H.264 one, its fragments are paced over hundreds of
     * milliseconds, and the r2 device run expired every such unit ten milliseconds after it
     * started - the viewer received nothing while the fragments kept arriving and almost no
     * sequence number was actually missing.</p>
     */
    public static final long HOLD_UP_MS = 10L;

    /**
     * Hold-up converted to the clock unit.
     */
    public static final long HOLD_UP_NS = HOLD_UP_MS * 1_000_000L;

    /**
     * Receiver of the reassembled units and of the drops.
     */
    public interface Listener {

        /**
         * One complete access unit, in the order its last fragment arrived.
         *
         * @param frame complete unit; never null
         */
        void onFrame(MediaFrame frame);

        /**
         * One unit was abandoned before it could be delivered.
         *
         * @param auId   identifier of the abandoned unit
         * @param reason {@code timeout}, {@code superseded} or {@code evicted}
         */
        void onAccessUnitDropped(int auId, String reason);
    }

    private final Listener listener;

    private final NanoClock clock;

    // Insertion ordered, so the oldest pending unit is also the first to be evicted.
    private final Map<Integer, Pending> pending = new LinkedHashMap<>();

    private long pendingBytes;

    private long delivered;

    private long dropped;

    private long late;

    private boolean deliveredAny;

    private int highestDeliveredAuId;

    /**
     * Creates a reassembler.
     *
     * @param listener receiver of units and drops; required
     * @param clock    clock used for the hold-up; required
     * @throws IllegalArgumentException if an argument is null
     */
    public AccessUnitReassembler(Listener listener, NanoClock clock) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.listener = listener;
        this.clock = clock;
    }

    /**
     * Feeds one fragment.
     *
     * <p>Parity fragments of a future sender are ignored here: version one never sends one, and
     * treating it as data would corrupt the unit it belongs to.</p>
     *
     * @param packet fragment received; required
     * @throws IllegalArgumentException if {@code packet} is null
     */
    public void onPacket(UdpMediaPacket packet) {
        if (packet == null) {
            throw new IllegalArgumentException("packet must not be null");
        }
        long nowNs = clock.nowNs();
        expire(nowNs);
        if (packet.isParity()) {
            return;
        }
        if (packet.getAuSize() > UdpMediaPacketCodec.MAX_AU_BYTES) {
            dropped++;
            listener.onAccessUnitDropped(packet.getAuId(), "oversized");
            return;
        }
        if (deliveredAny && !UdpMediaPacket.isNewer(packet.getAuId(), highestDeliveredAuId)) {
            late++;
            return;
        }
        Pending unit = pending.get(packet.getAuId());
        if (unit == null) {
            evictForRoom(packet.getAuSize());
            unit = new Pending(packet, nowNs);
            pending.put(packet.getAuId(), unit);
            pendingBytes += unit.bytes.length;
        } else if (unit.bytes.length != packet.getAuSize()) {
            // The same id with a different size is a protocol violation; the unit is unusable.
            drop(packet.getAuId(), "size_mismatch");
            return;
        }
        unit.touch(nowNs);
        if (unit.put(packet)) {
            pending.remove(packet.getAuId());
            pendingBytes -= unit.bytes.length;
            deliver(unit);
        }
    }

    /**
     * Drops the units whose hold-up elapsed.
     *
     * <p>The receiver calls this on every datagram and, if it wants a bound on the silence between
     * datagrams, on its own timer; the method is cheap and does nothing when no unit is pending.</p>
     */
    public void onTick() {
        expire(clock.nowNs());
    }

    /**
     * Returns the number of units currently being assembled.
     */
    public int getInFlightCount() {
        return pending.size();
    }

    /**
     * Returns how many units were delivered whole.
     */
    public long getDeliveredCount() {
        return delivered;
    }

    /**
     * Returns how many units were abandoned.
     */
    public long getDroppedCount() {
        return dropped;
    }

    /**
     * Returns the silence after which an unfinished unit is abandoned, in milliseconds.
     *
     * <p>The receiver publishes it as the hold-up gauge, so a capture records the threshold its
     * readings were taken under. In this version the value is the constant {@link #HOLD_UP_MS}; a
     * later batch replaces the constant with a window calibrated from measured fragment gaps, and
     * this accessor is what keeps the gauge honest across that change.</p>
     */
    public long getHoldUpMs() {
        return HOLD_UP_MS;
    }

    /**
     * Returns how many fragments belonged to an already delivered unit.
     */
    public long getLateCount() {
        return late;
    }

    private void evictForRoom(int auSize) {
        while (!pending.isEmpty()
                && (pending.size() >= MAX_IN_FLIGHT || pendingBytes + auSize > MAX_IN_FLIGHT_BYTES)) {
            Iterator<Map.Entry<Integer, Pending>> iterator = pending.entrySet().iterator();
            Map.Entry<Integer, Pending> eldest = iterator.next();
            pendingBytes -= eldest.getValue().bytes.length;
            iterator.remove();
            dropped++;
            listener.onAccessUnitDropped(eldest.getKey(), "evicted");
        }
    }

    private void expire(long nowNs) {
        List<Integer> expired = null;
        for (Map.Entry<Integer, Pending> entry : pending.entrySet()) {
            Pending unit = entry.getValue();
            if (nowNs - unit.lastArrivalNs >= HOLD_UP_NS) {
                if (expired == null) {
                    expired = new ArrayList<>(2);
                }
                expired.add(entry.getKey());
            }
        }
        if (expired == null) {
            return;
        }
        for (Integer auId : expired) {
            drop(auId, "timeout");
        }
    }

    private void drop(int auId, String reason) {
        Pending removed = pending.remove(auId);
        if (removed != null) {
            pendingBytes -= removed.bytes.length;
        }
        dropped++;
        listener.onAccessUnitDropped(auId, reason);
    }

    private void deliver(Pending unit) {
        delivered++;
        deliveredAny = true;
        highestDeliveredAuId = unit.auId;
        // A unit that is older than the one just delivered can never be shown any more.
        List<Integer> superseded = null;
        for (Integer auId : pending.keySet()) {
            if (!UdpMediaPacket.isNewer(auId, unit.auId)) {
                if (superseded == null) {
                    superseded = new ArrayList<>(2);
                }
                superseded.add(auId);
            }
        }
        if (superseded != null) {
            for (Integer auId : superseded) {
                drop(auId, "superseded");
            }
        }
        listener.onFrame(MediaFrame.accessUnit(unit.ptsUs, MediaFrame.DTS_UNSET, unit.configEpoch,
                unit.config, unit.syncPoint, unit.joinPoint, unit.joinParts, unit.bytes));
    }

    /**
     * One unit being assembled.
     */
    private static final class Pending {

        private final int auId;

        private final int fragmentCount;

        private final byte[] bytes;

        private final long[] received;

        private long lastArrivalNs;

        private final long ptsUs;

        private final int configEpoch;

        private final boolean config;

        private final boolean syncPoint;

        private final boolean joinPoint;

        private List<CodecConfigPart> joinParts = new ArrayList<>(0);

        private int receivedCount;

        private Pending(UdpMediaPacket first, long nowNs) {
            this.auId = first.getAuId();
            this.fragmentCount = first.getFragmentCount();
            this.bytes = new byte[first.getAuSize()];
            this.received = new long[(fragmentCount + 63) / 64];
            this.lastArrivalNs = nowNs;
            this.ptsUs = first.getPtsUs();
            this.configEpoch = first.getConfigEpoch();
            this.config = first.isConfig();
            this.syncPoint = first.isSyncPoint();
            this.joinPoint = first.isJoinPoint();
        }

        /**
         * Records that a fragment of this unit arrived, which restarts the hold-up.
         *
         * @param nowNs arrival time on the reassembler clock
         */
        private void touch(long nowNs) {
            lastArrivalNs = nowNs;
        }

        /**
         * Copies one fragment in.
         *
         * @return true when the unit is now complete
         */
        private boolean put(UdpMediaPacket packet) {
            int index = packet.getFragmentIndex();
            long mask = 1L << (index & 63);
            int word = index >>> 6;
            if ((received[word] & mask) != 0) {
                return false;
            }
            received[word] |= mask;
            receivedCount++;
            byte[] fragment = packet.getFragment();
            System.arraycopy(fragment, 0, bytes, packet.getFragmentOffset(), fragment.length);
            if (packet.isFirstFragment() && !packet.getJoinParts().isEmpty()) {
                joinParts = packet.getJoinParts();
            }
            return receivedCount == fragmentCount;
        }
    }
}
