package com.obby.android.localscreenshare.server;

import androidx.annotation.NonNull;

/**
 * Receives periodic outbound-traffic samples from {@link ScreenShareServer}.
 */
@FunctionalInterface
public interface ServerStatsListener {
    /**
     * Called on the main thread every 3 seconds, but only when the sampled values changed.
     *
     * @param serverStats outbound traffic of the last interval
     */
    void onServerStatsChanged(@NonNull ServerStats serverStats);
}
