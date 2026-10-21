package com.obby.android.localscreenshare.client;

import androidx.annotation.NonNull;

/**
 * Receives connection-state callbacks from an {@link ScreenShareClient}.
 *
 * <p>All callbacks are delivered on the main thread. {@link #onConnected()} is reported once per
 * stream when the session reaches a usable state; {@link #onDisconnected(StreamFailure)} reports a
 * stream error or completion that was not initiated by {@link ScreenShareClient#stop()}. Once
 * {@code stop()} has been called, no further callback is delivered. Frame data no longer travels
 * through this observer: the decoded output is rendered directly into the session's render target,
 * and the secure flag is reported through {@link ClientSecureListener}.</p>
 */
public interface ClientObserver {
    /**
     * Called on the main thread when the session reached a usable state.
     */
    void onConnected();

    /**
     * Called on the main thread when the server parked this request for its user to approve.
     *
     * <p>The stream stays open: the server is not refusing yet, it is waiting. A grant shows up as
     * media, and a refusal ends the call through
     * {@link #onDisconnected(StreamFailure)} with an authorization reason. A server that predates
     * this field never calls it, so an implementation must not depend on it to reach
     * {@link #onConnected()}.</p>
     *
     * @param timeoutSeconds how long the server keeps the request open, or 0 when it did not say
     */
    default void onApprovalPending(final int timeoutSeconds) {
    }

    /**
     * Called on the main thread when the stream failed or completed on its own.
     *
     * <p>Not called for a local {@link ScreenShareClient#stop()}; the receiver is expected to release the
     * client in response. {@code failure} carries the structured reason: an ordinary disconnect and
     * a refused peer identity are both reported here, and only {@link StreamFailure#isIdentityRejected()}
     * separates them, so the receiver can explain a security refusal instead of offering a retry
     * that cannot succeed.</p>
     *
     * @param failure parsed reason of the termination; never null
     */
    void onDisconnected(@NonNull StreamFailure failure);
}
