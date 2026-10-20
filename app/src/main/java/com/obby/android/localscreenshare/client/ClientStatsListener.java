package com.obby.android.localscreenshare.client;

import androidx.annotation.NonNull;

/**
 * Receives periodic inbound-traffic samples from an {@link ScreenShareClient}.
 */
@FunctionalInterface
public interface ClientStatsListener {
    /**
     * Called on the main thread every 3 seconds, but only when the sampled values changed.
     *
     * @param clientStats inbound size and rate of the last interval
     */
    void onClientStatsChanged(@NonNull ClientStats clientStats);
}
