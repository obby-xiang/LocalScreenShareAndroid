package com.obby.android.localscreenshare.core.model;

/**
 * Immutable encoded media access unit.
 *
 * <p>{@code sequence} increments from zero within a given {@code (streamKey, configEpoch)};
 * a new epoch restarts its encoder sequence at zero, so sequence is not globally unique.</p>
 */
public final class EncodedAccessUnit {

    public static final long DTS_UNSET = Long.MIN_VALUE;

    private final StreamKey streamKey;
    private final int configEpoch;
    private final long sequence;
    private final long ptsUs;
    private final long dtsUs;
    private final AccessUnitFlags flags;
    private final JoinInfo joinInfo;
    private final Integer temporalLayerId;
    private final byte[] payload;

    public EncodedAccessUnit(StreamKey streamKey, int configEpoch, long sequence, long ptsUs,
                             long dtsUs, AccessUnitFlags flags, JoinInfo joinInfo,
                             Integer temporalLayerId, byte[] payload) {
        if (streamKey == null) {
            throw new IllegalArgumentException("streamKey must not be null");
        }
        if (configEpoch < 1) {
            throw new IllegalArgumentException("configEpoch must be >= 1");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must be >= 0");
        }
        if (ptsUs < 0) {
            throw new IllegalArgumentException("ptsUs must be >= 0");
        }
        if (dtsUs != DTS_UNSET && dtsUs < 0) {
            throw new IllegalArgumentException("dtsUs must be DTS_UNSET or >= 0");
        }
        if (flags == null) {
            throw new IllegalArgumentException("flags must not be null");
        }
        if (flags.isJoinPoint()) {
            if (joinInfo == null) {
                throw new IllegalArgumentException("joinInfo is required when joinPoint is set");
            }
            if (joinInfo.getConfigEpoch() != configEpoch) {
                throw new IllegalArgumentException("joinInfo configEpoch must match AU configEpoch");
            }
        }
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("payload must not be null or empty");
        }

        this.streamKey = streamKey;
        this.configEpoch = configEpoch;
        this.sequence = sequence;
        this.ptsUs = ptsUs;
        this.dtsUs = dtsUs;
        this.flags = flags;
        this.joinInfo = joinInfo;
        this.temporalLayerId = temporalLayerId;
        this.payload = payload.clone();
    }

    public StreamKey getStreamKey() {
        return streamKey;
    }

    public int getConfigEpoch() {
        return configEpoch;
    }

    public long getSequence() {
        return sequence;
    }

    public long getPtsUs() {
        return ptsUs;
    }

    public long getDtsUs() {
        return dtsUs;
    }

    public AccessUnitFlags getFlags() {
        return flags;
    }

    public JoinInfo getJoinInfo() {
        return joinInfo;
    }

    public Integer getTemporalLayerId() {
        return temporalLayerId;
    }

    public byte[] getPayload() {
        return payload.clone();
    }

    public int getPayloadLength() {
        return payload.length;
    }

    public boolean hasDtsUs() {
        return dtsUs != DTS_UNSET;
    }
}
