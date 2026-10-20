package com.obby.android.localscreenshare.discovery.legacy;

import androidx.annotation.NonNull;

import java.util.List;

/**
 * Receives the current list of discovered servers.
 */
@FunctionalInterface
public interface LssServiceDiscoveryListener {
    /**
     * Called on the main thread with an unmodifiable, sorted snapshot.
     *
     * <p>Invoked once when the listener subscribes and again whenever the snapshot changes; the
     * list passed in replaces the previous one and is never mutated afterwards.</p>
     *
     * @param services current servers, sorted by host address, port, name and id
     */
    void onServicesChanged(@NonNull List<LssServiceInfo> services);
}
