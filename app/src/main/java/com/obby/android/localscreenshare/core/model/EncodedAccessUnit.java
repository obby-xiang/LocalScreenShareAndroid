package com.obby.android.localscreenshare.core.model;

/**
 * Immutable encoded media access unit.
 *
 * <p>{@code sequence} increments from zero within a given {@code (streamKey, configEpoch)}; a new
 * epoch restarts its encoder sequence at zero, so sequence is not globally unique. The config
 * epoch binds the unit to the descriptor whose configuration produced it: send sessions and
 * client sessions reject units from another epoch, including ordinary units that are not join
 * points.</p>
 *
 * <p><b>Invariants.</b> The constructor validates every argument: the payload must be non-null and
 * non-empty, the epoch at least one, sequence and presentation time non-negative, and the decode
 * time either {@link #DTS_UNSET} or non-negative. A join point must carry {@link JoinInfo} with the
 * same config epoch, because a late receiver joining there needs exactly that configuration, and an
 * epoch mismatch would describe a different one. The temporal layer id is optional metadata that
 * the model does not interpret.</p>
 *
 * <p>The payload is opaque codec data; the model never parses it. Byte arrays are copied on the
 * way in and out, so an instance is deeply immutable and can be shared between threads.</p>
 */
public final class EncodedAccessUnit {

    /** Sentinel for a stream with no decode timestamps (no frame reordering). */
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

    /**
     * Creates an access unit.
     *
     * @param streamKey       stream identity; required
     * @param configEpoch     configuration epoch the unit was encoded for; must be at least one
     * @param sequence        index within {@code (streamKey, configEpoch)}; must be non-negative
     * @param ptsUs           presentation timestamp in microseconds; must be non-negative
     * @param dtsUs           decode timestamp in microseconds, or {@link #DTS_UNSET}
     * @param flags           signaling flags; required
     * @param joinInfo        configuration required at a join point; required when the join-point
     *                        flag is set and then its epoch must equal {@code configEpoch}
     * @param temporalLayerId optional temporal layer metadata
     * @param payload         encoded bytes; must be non-null and non-empty, and is copied
     * @throws IllegalArgumentException if any invariant above is violated
     */
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

    /** Returns the stream this unit belongs to. */
    public StreamKey getStreamKey() {
        return streamKey;
    }

    /** Returns the config epoch that identifies the descriptor used to encode this unit. */
    public int getConfigEpoch() {
        return configEpoch;
    }

    /** Returns the unit's index within its {@code (streamKey, configEpoch)} sequence. */
    public long getSequence() {
        return sequence;
    }

    public long getPtsUs() {
        return ptsUs;
    }

    public long getDtsUs() {
        return dtsUs;
    }

    /** Returns the signaling flags; codec policies may re-validate the payload. */
    public AccessUnitFlags getFlags() {
        return flags;
    }

    /**
     * Returns the configuration required at a join point, or {@code null}. A non-null value may
     * also be present on units that are not join points; only the join-point flag makes it a
     * required joining configuration.
     */
    public JoinInfo getJoinInfo() {
        return joinInfo;
    }

    /** Returns optional temporal layer metadata, or {@code null} when the codec does not use it. */
    public Integer getTemporalLayerId() {
        return temporalLayerId;
    }

    /** Returns a copy of the encoded payload. */
    public byte[] getPayload() {
        return payload.clone();
    }

    /** Returns the payload length without copying the payload. */
    public int getPayloadLength() {
        return payload.length;
    }

    /** True when a decode timestamp is present rather than {@link #DTS_UNSET}. */
    public boolean hasDtsUs() {
        return dtsUs != DTS_UNSET;
    }
}
