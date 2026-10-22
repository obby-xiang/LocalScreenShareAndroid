package com.obby.android.localscreenshare.core.net;

import com.obby.android.localscreenshare.core.model.CodecConfigPart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One received datagram of the media channel, after authentication and parsing.
 *
 * <p>A datagram is one fragment of one access unit. Every fragment repeats the unit's identity -
 * its id, presentation timestamp, size, epoch and flags - so a fragment can be interpreted without
 * the ones before it, which is what makes an out-of-order or partially lost unit recoverable at all.
 * The join configuration travels once, in the first fragment, exactly like the stream channel
 * carries it in the frame body.</p>
 *
 * <p>Instances are immutable; the fragment bytes and the join parts are copied on the way in.</p>
 */
public final class UdpMediaPacket {

    private final long wireSeq;
    private final int auId;
    private final int fragmentIndex;
    private final int fragmentCount;
    private final long ptsUs;
    private final int auSize;
    private final int configEpoch;
    private final boolean config;
    private final boolean syncPoint;
    private final boolean joinPoint;
    private final boolean parity;
    private final int fragmentOffset;
    private final byte[] fragment;
    private final List<CodecConfigPart> joinParts;

    UdpMediaPacket(long wireSeq, int auId, int fragmentIndex, int fragmentCount, long ptsUs,
                   int auSize, int configEpoch, boolean config, boolean syncPoint,
                   boolean joinPoint, boolean parity, int fragmentOffset, byte[] fragment,
                   List<CodecConfigPart> joinParts) {
        this.wireSeq = wireSeq;
        this.auId = auId;
        this.fragmentIndex = fragmentIndex;
        this.fragmentCount = fragmentCount;
        this.ptsUs = ptsUs;
        this.auSize = auSize;
        this.configEpoch = configEpoch;
        this.config = config;
        this.syncPoint = syncPoint;
        this.joinPoint = joinPoint;
        this.parity = parity;
        this.fragmentOffset = fragmentOffset;
        this.fragment = fragment == null ? new byte[0] : fragment.clone();
        this.joinParts = joinParts == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(joinParts));
    }

    /**
     * Returns the sequence number of this datagram, unique per direction of the session.
     */
    public long getWireSeq() {
        return wireSeq;
    }

    /**
     * Returns the id of the access unit this fragment belongs to.
     *
     * <p>Ids are compared with serial-number arithmetic, so a wrap is not an ordering error.</p>
     */
    public int getAuId() {
        return auId;
    }

    /**
     * Returns the index of this fragment within its access unit, from zero.
     */
    public int getFragmentIndex() {
        return fragmentIndex;
    }

    /**
     * Returns how many fragments the access unit has.
     */
    public int getFragmentCount() {
        return fragmentCount;
    }

    /**
     * Returns the presentation timestamp of the unit, in microseconds.
     */
    public long getPtsUs() {
        return ptsUs;
    }

    /**
     * Returns the size of the whole access unit payload, in bytes.
     */
    public int getAuSize() {
        return auSize;
    }

    /**
     * Returns the config epoch of the unit.
     */
    public int getConfigEpoch() {
        return configEpoch;
    }

    /**
     * Returns whether the unit carries decoder configuration.
     */
    public boolean isConfig() {
        return config;
    }

    /**
     * Returns whether the unit is a sync point.
     */
    public boolean isSyncPoint() {
        return syncPoint;
    }

    /**
     * Returns whether the unit is a valid join point.
     */
    public boolean isJoinPoint() {
        return joinPoint;
    }

    /**
     * Returns whether this datagram is a forward-error-correction parity fragment.
     *
     * <p>Version one never sends one; the flag exists so a receiver can ignore parity datagrams of
     * a future sender instead of mistaking them for data.</p>
     */
    public boolean isParity() {
        return parity;
    }

    /**
     * Returns the offset of this fragment inside the access unit payload, in bytes.
     */
    public int getFragmentOffset() {
        return fragmentOffset;
    }

    /**
     * Returns a copy of the fragment bytes.
     */
    public byte[] getFragment() {
        return fragment.clone();
    }

    /**
     * Returns the length of the fragment, in bytes.
     */
    public int getFragmentLength() {
        return fragment.length;
    }

    /**
     * Returns the join configuration parts carried by the first fragment, possibly empty.
     */
    public List<CodecConfigPart> getJoinParts() {
        return joinParts;
    }

    /**
     * Returns whether this is the first fragment of its unit.
     */
    public boolean isFirstFragment() {
        return fragmentIndex == 0;
    }

    /**
     * Returns whether this is the last fragment of its unit.
     */
    public boolean isLastFragment() {
        return fragmentIndex == fragmentCount - 1;
    }

    /**
     * Returns whether {@code first} is a newer unit id than {@code second}, using the wrap-safe
     * comparison of a serial number.
     */
    public static boolean isNewer(int first, int second) {
        return first - second > 0;
    }

    @Override
    public String toString() {
        return "UdpMediaPacket{wireSeq=" + wireSeq + ", auId=" + (auId & 0xffffffffL)
                + ", fragment=" + fragmentIndex + "/" + fragmentCount + ", auSize=" + auSize
                + ", join=" + joinPoint + '}';
    }
}
