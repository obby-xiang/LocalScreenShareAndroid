package com.obby.android.localscreenshare.core.client;

import com.obby.android.localscreenshare.core.model.ClientState;

/**
 * Callback for client media session state transitions.
 *
 * <p>Called synchronously from the session's synchronized transition path, on the thread that
 * caused the transition, and only when the state actually changes; {@code previous} and
 * {@code current} always differ. The session monitor is held during the callback, so an
 * implementation must not block and must not call back into the session or into code that waits
 * for another thread to enter it.</p>
 */
public interface ClientStateListener {

    /**
     * Notifies one transition.
     *
     * @param previous state before the transition; never equal to {@code current}
     * @param current  state after the transition
     */
    void onStateChanged(ClientState previous, ClientState current);
}
