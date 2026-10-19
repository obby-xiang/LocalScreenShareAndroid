package com.obby.android.localscreenshare.client;

/**
 * Receives the server's secure flag and applies it to the floating viewer window.
 */
public interface ClientSecureListener {

    /**
     * Reports the session-level secure flag.
     *
     * <p>A secure session must mark its viewer window {@code FLAG_SECURE} so the content is
     * excluded from screenshots and screen recordings.</p>
     */
    void onSecureChanged(boolean secure);
}
