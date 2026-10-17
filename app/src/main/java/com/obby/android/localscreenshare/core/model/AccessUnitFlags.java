package com.obby.android.localscreenshare.core.model;

/**
 * Immutable per-access-unit signaling flags.
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

    public static AccessUnitFlags of(boolean syncPoint, boolean joinPoint, boolean config) {
        return new AccessUnitFlags(syncPoint, joinPoint, config);
    }

    public static AccessUnitFlags none() {
        return new AccessUnitFlags(false, false, false);
    }

    public boolean isSyncPoint() {
        return syncPoint;
    }

    public boolean isJoinPoint() {
        return joinPoint;
    }

    public boolean isConfig() {
        return config;
    }
}
