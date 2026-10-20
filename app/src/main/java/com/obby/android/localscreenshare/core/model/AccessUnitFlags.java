package com.obby.android.localscreenshare.core.model;

/**
 * Immutable per-access-unit signaling flags.
 *
 * <p>The flags describe the encoded unit, not the transport: {@code syncPoint} marks a point the
 * codec can start decoding from, {@code joinPoint} marks a point a receiver may join at, and
 * {@code config} marks a unit that carries codec configuration instead of displayable data.
 * Flags are hints, not guarantees: a codec policy may re-validate the payload, and a join point
 * additionally requires {@link JoinInfo} with the matching config epoch. Current adapters deliver
 * configuration through the stream descriptor, so display units carry {@code config == false}.</p>
 */
public final class AccessUnitFlags {

    private final boolean syncPoint;
    private final boolean joinPoint;
    private final boolean config;

    private AccessUnitFlags(boolean syncPoint, boolean joinPoint, boolean config) {
        this.syncPoint = syncPoint;
        this.joinPoint = joinPoint;
        this.config = config;
    }

    /**
     * Creates a flag set. The booleans are independent and no combination is rejected here; the
     * access unit constructor enforces the join-point/join-info relationship.
     */
    public static AccessUnitFlags of(boolean syncPoint, boolean joinPoint, boolean config) {
        return new AccessUnitFlags(syncPoint, joinPoint, config);
    }

    /**
     * Returns a flag set with every flag cleared.
     */
    public static AccessUnitFlags none() {
        return new AccessUnitFlags(false, false, false);
    }

    /**
     * True when the codec can start decoding at this unit.
     */
    public boolean isSyncPoint() {
        return syncPoint;
    }

    /**
     * True when a late or resynchronizing receiver may join at this unit.
     */
    public boolean isJoinPoint() {
        return joinPoint;
    }

    /**
     * True when the unit carries codec configuration rather than displayable data.
     */
    public boolean isConfig() {
        return config;
    }
}
