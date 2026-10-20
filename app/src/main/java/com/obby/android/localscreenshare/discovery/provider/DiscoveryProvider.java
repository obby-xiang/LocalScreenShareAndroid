package com.obby.android.localscreenshare.discovery.provider;

import androidx.annotation.NonNull;

/**
 * One discovery source: NSD, multicast, scan or manual entry.
 *
 * <p>A provider only runs the source and reports observations. It does not decide reachability,
 * does not write trust state and does not hold a gRPC channel, so providers can be started and
 * stopped independently of the store and of each other.</p>
 */
public interface DiscoveryProvider {

    /**
     * Starts the source and delivers observations to {@code listener} on the main thread.
     *
     * <p>Calling this while already started only replaces the listener; the underlying source is
     * not restarted, because framework discovery calls are not idempotent.</p>
     */
    void start(@NonNull DiscoveryProviderListener listener);

    /**
     * Stops the source; pending callbacks become no-ops and no observation is delivered afterwards.
     */
    void stop();

    /**
     * Returns the stable provider id used in logs and diagnostics.
     */
    @NonNull
    String id();
}
