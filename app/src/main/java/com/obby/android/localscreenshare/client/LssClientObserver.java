package com.obby.android.localscreenshare.client;

/**
 * Receives connection-state callbacks from an {@link LssClient}.
 *
 * <p>All callbacks are delivered on the main thread. {@link #onConnected()} is reported once per
 * stream when the session reaches a usable state; {@link #onDisconnected()} reports a stream error
 * or completion that was not initiated by {@link LssClient#stop()}. Once {@code stop()} has been
 * called, no further callback is delivered. Frame data no longer travels through this observer: the
 * decoded output is rendered directly into the session's render target, and the secure flag is
 * reported through {@link ClientSecureListener}.</p>
 */
public interface LssClientObserver {
    /**
     * Called on the main thread when the session reached a usable state.
     */
    void onConnected();

    /**
     * Called on the main thread when the stream failed or completed on its own.
     *
     * <p>Not called for a local {@link LssClient#stop()}; the receiver is expected to release the
     * client in response.</p>
     */
    void onDisconnected();
}
