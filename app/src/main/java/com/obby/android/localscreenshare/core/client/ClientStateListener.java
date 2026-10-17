package com.obby.android.localscreenshare.core.client;

import com.obby.android.localscreenshare.core.model.ClientState;

/**
 * Callback for client media session state transitions.
 */
public interface ClientStateListener {

    void onStateChanged(ClientState previous, ClientState current);
}
