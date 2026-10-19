package com.obby.android.localscreenshare.server;

import androidx.annotation.NonNull;

/**
 * Receives server identity updates.
 */
@FunctionalInterface
public interface LssServerInfoListener {
    /**
     * Called with the new identity.
     *
     * <p>The first call happens synchronously inside {@link LssServer#start()} on the thread that
     * called it; later calls are posted to the main thread when NSD re-resolution changes the host
     * address.</p>
     *
     * @param serverInfo current server identity
     */
    void onServerInfoChanged(@NonNull LssServerInfo serverInfo);
}
