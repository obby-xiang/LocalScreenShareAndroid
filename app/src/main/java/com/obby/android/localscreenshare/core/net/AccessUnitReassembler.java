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
 * <p><b>Giving up.</b> An incomplete unit is dropped whole - never handed on half-built - in four
 * cases: it has been silent for longer than the hold-up, a newer unit was delivered and made it
 * useless, the buffer budget forced the oldest unit out, or the sender announced a unit this receiver
 * refuses to hold (an oversized one, or the same identifier with a different size). Silence is
 * measured from the <i>last</i> fragment, not from the first one: a sender paces its fragments, so
 * a unit that keeps arriving is making progress however long its assembly takes, and the hold-up
 * only decides when a stalled unit is hopeless. Every drop is reported with its reason so the caller
 * can count it and decide whether to ask for a key frame. State from a fragment of an already
 * delivered or abandoned unit is counted as late and ignored.</p>
 *
 * <p><b>The hold-up is measured, not guessed.</b> A constant threshold has no physical meaning: the
 * device calibration of this bug measured fragment intervals of one unit with a median of 0.1 ms and
 * a p95 of 3.0 ms, but the longest interval of a five second window had a median of 12.6 ms and 76%
 * to 92% of the windows crossed 10 ms - and the control-plane round trip alone measured 12.2 ms in
 * the calibration run and 14.8 ms in the r3 run. A
 * receiver cannot wait forever (memory is bounded, and a unit nobody completed delays nothing but
 * still occupies a slot), so the wait follows what this link actually produced: {@link #GAP_FACTOR}
 * times the longest of the last {@value #GAP_SAMPLES} intervals between two fragments of the same
 * unit, clamped to [{@value #HOLD_UP_MIN_MS}, {@value #HOLD_UP_MAX_MS}] milliseconds. Before the ring
 * holds a full window the wait is {@value #HOLD_UP_WARMUP_MS} milliseconds as a floor, so one quiet
 * burst cannot shrink the window before the tail of the stream has been seen. Only fragments that
 * still belong to a pending unit feed the estimator, and only intervals inside one unit count: the
 * interval across two units contains the frame interval and says nothing about jitter. The numbers
 * are the pending device calibration of TB-MEDIA-008; the shape - bounded, derived from measured
 * intervals of the same unit, decaying after {@value #GAP_SAMPLES} samples - is what the fix rests
 * on.</p>
 *
 * <p><b>Remembering the abandoned.</b> A dropped unit is gone for good - the fragments it lost never
 * arrive - so every later fragment of its identifier is late by definition. Remembering the
 * identifiers is what keeps one lost unit at one drop: without it the residue started a new pending
 * unit, allocated its buffer again, expired again and reached the loss tracker again, which the
 * device run read as 2.4-2.7 timeouts per unit that really lost a fragment. The memory is a fixed
 * ring of {@value #ABANDONED_SLOTS} identifiers, so a sender that keeps reusing an identifier cannot
 * grow the receiver, and the scan is over at most that many ints.</p>
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
     * Smallest hold-up applied to an unfinished unit, in milliseconds.
     *
     * <p>Silence is measured from the last fragment that arrived, not from the first one. Anchoring
     * the deadline at the first fragment makes the boundary a limit on the whole assembly, which
     * only works for units small enough to land in one burst: a JPEG compatibility frame of a
     * screen is about six times the size of an H.264 one, its fragments are paced over hundreds of
     * milliseconds, and the r2 device run expired every such unit ten milliseconds after it
     * started - the viewer received nothing while the fragments kept arriving and almost no
     * sequence number was actually missing.</p>
     */
    public static final long HOLD_UP_MIN_MS = 10L;

    /**
     * Minimum hold-up converted to the clock unit.
     */
    public static final long HOLD_UP_MIN_NS = HOLD_UP_MIN_MS * 1_000_000L;

    /**
     * Hold-up applied while the estimator does not yet hold a full window of intervals, in
     * milliseconds.
     *
     * <p>It is a floor rather than a constant part of the rule: until {@value #GAP_SAMPLES} intervals
     * of one unit have been seen, one quiet burst would otherwise shrink the window straight to the
     * minimum, and the first stall of the stream would cost a frame for no reason other than being
     * early. A receiver that has measured nothing yet still has to be able to deliver.</p>
     */
    public static final long HOLD_UP_WARMUP_MS = 30L;

    /**
     * Warm-up hold-up converted to the clock unit.
     */
    public static final long HOLD_UP_WARMUP_NS = HOLD_UP_WARMUP_MS * 1_000_000L;

    /**
     * Largest hold-up applied to an unfinished unit, in milliseconds.
     *
     * <p>This is the bound of the fix, not its target: a stall longer than this is a capacity or
     * pacing problem of the link, and widening the window further would only hide it while holding
     * buffers and delaying the recovery of every later unit. It is also the ceiling the negotiation
     * announces as {@code hold_ms}.</p>
     */
    public static final long HOLD_UP_MAX_MS = 100L;

    /**
     * Maximum hold-up converted to the clock unit.
     */
    public static final long HOLD_UP_MAX_NS = HOLD_UP_MAX_MS * 1_000_000L;

    /**
     * Number of fragment intervals the estimator keeps.
     *
     * <p>Sixteen is the device calibration's pending value: long enough that one outlier still shows
     * up in the maximum, short enough that the window falls back to its minimum a fraction of a
     * second after the link calms down.</p>
     */
    public static final int GAP_SAMPLES = 16;

    /**
     * Factor applied to the longest interval of the ring.
     *
     * <p>The factor, not the longest interval itself, is the wait: the interval that was measured is
     * exactly the one the receiver just survived, and the next one of that size has to fit with
     * margin. Pending the device calibration, like the ring size.</p>
     */
    public static final int GAP_FACTOR = 2;

    /**
     * Number of abandoned unit identifiers the reassembler remembers.
     *
     * <p>It matches the reorder window: a residue is only refused as stale once its sequence
     * number is older than {@link DatagramReorderWindow#WINDOW_SIZE} datagrams, so a unit
     * abandoned anywhere inside that window can still deliver fragments. Remembering fewer units
     * than the window can carry would let the oldest residue rebuild its buffer, allocate it again
     * and count a second drop for a loss that was already counted - exactly the defect the
     * tombstone prevents. The ring stays fixed, so a sender that keeps reusing an identifier
     * cannot grow the receiver, and the scan is over at most that many ints.</p>
     */
    private static final int ABANDONED_SLOTS = DatagramReorderWindow.WINDOW_SIZE;

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
         * @param reason {@code timeout}, {@code superseded}, {@code evicted}, {@code oversized} or
         *               {@code size_mismatch}
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

    // Identifiers of the units that were abandoned, oldest first while the ring fills.
    private final int[] abandonedAuIds = new int[ABANDONED_SLOTS];

    private int abandonedCursor;

    private int abandonedCount;

    // Intervals between two fragments of the same unit, newest over the oldest.
    private final long[] gapSamples = new long[GAP_SAMPLES];

    private int gapCursor;

    private int gapCount;

    private final long minHoldUpNs;

    private final long maxHoldUpNs;

    // The hold-up currently applied; replaced whenever a new interval enters the ring.
    private long holdUpNs;

    private boolean hasLastFragment;

    private int lastFragmentAuId;

    private long lastFragmentArrivalNs;

    /**
     * Creates a reassembler with the calibrated upper bound.
     *
     * @param listener receiver of units and drops; required
     * @param clock    clock used for the hold-up; required
     * @throws IllegalArgumentException if an argument is null
     */
    public AccessUnitReassembler(Listener listener, NanoClock clock) {
        this(listener, clock, HOLD_UP_MAX_NS);
    }

    /**
     * Creates a reassembler with an explicit upper bound of the hold-up.
     *
     * <p>Package private: a test pins the bound instead of feeding intervals long enough to reach
     * the production ceiling, and the receiver uses the calibrated one.</p>
     *
     * @param listener    receiver of units and drops; required
     * @param clock       clock used for the hold-up; required
     * @param maxHoldUpNs ceiling of the hold-up on the clock unit; at least {@link #HOLD_UP_MIN_NS}
     * @throws IllegalArgumentException if an argument is null or the bound is below the minimum
     */
    AccessUnitReassembler(Listener listener, NanoClock clock, long maxHoldUpNs) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        if (maxHoldUpNs < HOLD_UP_MIN_NS) {
            throw new IllegalArgumentException("maxHoldUpNs must not be below the minimum");
        }
        this.listener = listener;
        this.clock = clock;
        this.minHoldUpNs = HOLD_UP_MIN_NS;
        this.maxHoldUpNs = maxHoldUpNs;
        this.holdUpNs = Math.min(HOLD_UP_WARMUP_NS, maxHoldUpNs);
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
        int auId = packet.getAuId();
        // Only a fragment that still belongs to a unit feeds the estimator: residue of a delivered
        // or abandoned unit measures nothing about how long a fragment of a live unit may wait.
        if (!packet.isParity() && !isAbandoned(auId)
                && !(deliveredAny && !UdpMediaPacket.isNewer(auId, highestDeliveredAuId))) {
            observeGap(auId, nowNs);
        }
        // The interval is recorded before the verdict, so the stall that would expire a unit also
        // widens the window that judges it - an interval is only evidence once a fragment follows it.
        expire(nowNs);
        if (packet.isParity()) {
            return;
        }
        if (isAbandoned(auId)) {
            // The unit already lost a fragment for good, so nothing that still arrives for it can
            // complete a frame. It is a late datagram, not a new unit: rebuilding it would allocate
            // the buffer again and count a second drop for one loss.
            late++;
            return;
        }
        if (packet.getAuSize() > UdpMediaPacketCodec.MAX_AU_BYTES) {
            abandon(auId);
            dropped++;
            listener.onAccessUnitDropped(auId, "oversized");
            return;
        }
        if (deliveredAny && !UdpMediaPacket.isNewer(auId, highestDeliveredAuId)) {
            late++;
            return;
        }
        Pending unit = pending.get(auId);
        if (unit == null) {
            evictForRoom(packet.getAuSize());
            unit = new Pending(packet, nowNs);
            pending.put(auId, unit);
            pendingBytes += unit.bytes.length;
        } else if (unit.bytes.length != packet.getAuSize()) {
            // The same id with a different size is a protocol violation; the unit is unusable.
            drop(auId, "size_mismatch");
            return;
        }
        unit.touch(nowNs);
        if (unit.put(packet)) {
            pending.remove(auId);
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
     * Returns the silence after which an unfinished unit is currently abandoned, in milliseconds.
     *
     * <p>The receiver publishes it as the hold-up gauge, so a capture records the threshold its
     * readings were taken under; it is the value of the moment, not the ceiling of the window.</p>
     */
    public long getHoldUpMs() {
        return holdUpNs / 1_000_000L;
    }

    /**
     * Records the interval since the previous fragment of the same unit, and re-derives the window.
     *
     * <p>An interval across two units contains the frame interval and says nothing about how long a
     * fragment of one unit may be held back, so only a fragment of the same unit contributes.</p>
     *
     * @param auId      unit the fragment belongs to
     * @param arrivalNs arrival time on this reassembler's clock
     */
    private void observeGap(int auId, long arrivalNs) {
        if (hasLastFragment && auId == lastFragmentAuId) {
            gapSamples[gapCursor] = arrivalNs - lastFragmentArrivalNs;
            gapCursor = (gapCursor + 1) % gapSamples.length;
            if (gapCount < gapSamples.length) {
                gapCount++;
            }
            updateHoldUp();
        }
        hasLastFragment = true;
        lastFragmentAuId = auId;
        lastFragmentArrivalNs = arrivalNs;
    }

    /**
     * Re-derives the window from the ring of intervals.
     */
    private void updateHoldUp() {
        long longest = 0L;
        for (int index = 0; index < gapCount; index++) {
            if (gapSamples[index] > longest) {
                longest = gapSamples[index];
            }
        }
        long estimate = Math.max(minHoldUpNs, Math.min(maxHoldUpNs, GAP_FACTOR * longest));
        long floor = gapCount < gapSamples.length ? Math.min(HOLD_UP_WARMUP_NS, maxHoldUpNs)
                : minHoldUpNs;
        holdUpNs = Math.max(floor, estimate);
    }

    /**
     * Returns how many fragments belonged to a unit that was already delivered or abandoned.
     */
    public long getLateCount() {
        return late;
    }

    /**
     * Remembers that no fragment of this unit can ever produce a frame.
     *
     * @param auId identifier of the abandoned unit
     */
    private void abandon(int auId) {
        for (int index = 0; index < abandonedCount; index++) {
            if (abandonedAuIds[index] == auId) {
                return;
            }
        }
        abandonedAuIds[abandonedCursor] = auId;
        abandonedCursor = (abandonedCursor + 1) % abandonedAuIds.length;
        if (abandonedCount < abandonedAuIds.length) {
            abandonedCount++;
        }
    }

    /**
     * Returns whether the unit was already abandoned.
     *
     * @param auId identifier to look up
     */
    private boolean isAbandoned(int auId) {
        for (int index = 0; index < abandonedCount; index++) {
            if (abandonedAuIds[index] == auId) {
                return true;
            }
        }
        return false;
    }

    private void evictForRoom(int auSize) {
        while (!pending.isEmpty()
                && (pending.size() >= MAX_IN_FLIGHT || pendingBytes + auSize > MAX_IN_FLIGHT_BYTES)) {
            Iterator<Map.Entry<Integer, Pending>> iterator = pending.entrySet().iterator();
            Map.Entry<Integer, Pending> eldest = iterator.next();
            pendingBytes -= eldest.getValue().bytes.length;
            iterator.remove();
            abandon(eldest.getKey());
            dropped++;
            listener.onAccessUnitDropped(eldest.getKey(), "evicted");
        }
    }

    private void expire(long nowNs) {
        List<Integer> expired = null;
        for (Map.Entry<Integer, Pending> entry : pending.entrySet()) {
            Pending unit = entry.getValue();
            if (nowNs - unit.lastArrivalNs >= holdUpNs) {
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
        abandon(auId);
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
