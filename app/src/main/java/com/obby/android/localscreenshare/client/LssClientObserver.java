package com.obby.android.localscreenshare.client;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;

import com.obby.android.localscreenshare.support.Reference;

/**
 * Receives connection-state and frame callbacks from an {@link LssClient}.
 *
 * <p>All callbacks are delivered on the main thread. {@link #onConnected()} is reported once per
 * stream, before the first frame callback; {@link #onScreenFrameReceived(Reference, boolean)}
 * follows for every accepted frame until the stream terminates. {@link #onDisconnected()} reports a
 * stream error or completion that was not initiated by {@link LssClient#stop()}; once {@code stop()}
 * has been called, no further callback is delivered.</p>
 */
public interface LssClientObserver {
    /**
     * Called on the main thread when the stream delivered its first accepted frame.
     */
    void onConnected();

    /**
     * Called on the main thread when the stream failed or completed on its own.
     *
     * <p>Not called for a local {@link LssClient#stop()}; the receiver is expected to release the
     * client in response.</p>
     */
    void onDisconnected();

    /**
     * Delivers one decoded frame on the main thread.
     *
     * <p>The caller transfers ownership of {@code frame} to the receiver: the receiver must call
     * {@link Reference#clear()} when the bitmap is no longer shown, which recycles it or returns it
     * to the client's reuse pool. Frames whose timestamp is not newer than the previously delivered
     * frame never reach this callback.</p>
     *
     * @param frame owned frame reference; clear it when done
     * @param isSecure whether the sender marked the captured screen as secure
     */
    void onScreenFrameReceived(@NonNull Reference<Bitmap> frame, boolean isSecure);
}
