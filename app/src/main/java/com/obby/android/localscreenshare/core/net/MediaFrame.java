package com.obby.android.localscreenshare.core.net;

import com.obby.android.localscreenshare.core.model.CodecConfigPart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One frame of a dedicated media stream, independent of the wire format.
 *
 * <p>A frame is either the descriptor of a config epoch or one encoded access unit. Both carry the
 * presentation timestamp, the epoch they belong to and the payload; an access unit additionally
 * carries the flags a decoder needs to resynchronize and the optional configuration parts of a
 * join point. The descriptor payload is the same serialized descriptor the control plane sends, so
 * the two paths decode it with one mapper instead of two.</p>
 *
 * <p>Instances are immutable; the payload and the configuration parts are copied on the way in.</p>
 */
public final class MediaFrame {

    /**
     * Access units have no decode timestamp in this version, so a frame carries one only when the
     * sender explicitly set it.
     */
    public static final long DTS_UNSET = Long.MIN_VALUE;

    /**
     * Kind of frame.
     */
    public enum Type {

        /**
         * Decode configuration of one epoch.
         */
        DESCRIPTOR(1),

        /**
         * One encoded access unit.
         */
        ACCESS_UNIT(2);

        private final int code;

        Type(int code) {
            this.code = code;
        }

        /**
         * Returns the wire code of this type.
         */
        public int getCode() {
            return code;
        }

        /**
         * Resolves a wire code, or null when the value is unknown.
         */
        public static Type fromCode(int code) {
            for (Type type : values()) {
                if (type.code == code) {
                    return type;
                }
            }
            return null;
        }
    }

    private final Type type;
    private final long ptsUs;
    private final long dtsUs;
    private final int configEpoch;
    private final boolean config;
    private final boolean syncPoint;
    private final boolean joinPoint;
    private final List<CodecConfigPart> joinParts;
    private final byte[] payload;

    private MediaFrame(Type type, long ptsUs, long dtsUs, int configEpoch, boolean config,
                       boolean syncPoint, boolean joinPoint, List<CodecConfigPart> joinParts,
                       byte[] payload) {
        this.type = type;
        this.ptsUs = ptsUs;
        this.dtsUs = dtsUs;
        this.configEpoch = configEpoch;
        this.config = config;
        this.syncPoint = syncPoint;
        this.joinPoint = joinPoint;
        this.joinParts = joinParts == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(joinParts));
        this.payload = payload == null ? new byte[0] : payload.clone();
    }

    /**
     * Creates a descriptor frame.
     *
     * @param ptsUs       presentation timestamp in microseconds
     * @param configEpoch config epoch the descriptor describes
     * @param payload     serialized descriptor; may be null
     */
    public static MediaFrame descriptor(long ptsUs, int configEpoch, byte[] payload) {
        return new MediaFrame(Type.DESCRIPTOR, ptsUs, DTS_UNSET, configEpoch, false, false,
                false, null, payload);
    }

    /**
     * Creates an access-unit frame.
     *
     * @param ptsUs       presentation timestamp in microseconds
     * @param dtsUs       decode timestamp, or {@link #DTS_UNSET} when absent
     * @param configEpoch config epoch the unit belongs to
     * @param config      whether the unit carries decoder configuration
     * @param syncPoint   whether the unit is a sync point
     * @param joinPoint   whether the unit is a valid join point
     * @param joinParts   configuration parts of the join point; may be null
     * @param payload     encoded access unit; may be null
     */
    public static MediaFrame accessUnit(long ptsUs, long dtsUs, int configEpoch, boolean config,
                                        boolean syncPoint, boolean joinPoint,
                                        List<CodecConfigPart> joinParts, byte[] payload) {
        return new MediaFrame(Type.ACCESS_UNIT, ptsUs, dtsUs, configEpoch, config, syncPoint,
                joinPoint, joinParts, payload);
    }

    /**
     * Returns the kind of this frame.
     */
    public Type getType() {
        return type;
    }

    /**
     * Returns the presentation timestamp in microseconds.
     */
    public long getPtsUs() {
        return ptsUs;
    }

    /**
     * Returns the decode timestamp, or {@link #DTS_UNSET} when the frame carries none.
     */
    public long getDtsUs() {
        return dtsUs;
    }

    /**
     * Returns whether a decode timestamp is present.
     */
    public boolean hasDtsUs() {
        return dtsUs != DTS_UNSET;
    }

    /**
     * Returns the config epoch of this frame.
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
     * Returns the configuration parts of a join point, possibly empty.
     */
    public List<CodecConfigPart> getJoinParts() {
        return joinParts;
    }

    /**
     * Returns a copy of the payload.
     */
    public byte[] getPayload() {
        return payload.clone();
    }

    /**
     * Returns the payload length in bytes.
     */
    public int getPayloadLength() {
        return payload.length;
    }

    @Override
    public String toString() {
        return "MediaFrame{type=" + type + ", epoch=" + configEpoch + ", ptsUs=" + ptsUs
                + ", join=" + joinPoint + ", payload=" + payload.length + "B}";
    }
}
