package com.obby.android.localscreenshare.core.model;

/**
 * Lifecycle state of a single client session.
 */
public enum ClientState {
    NEGOTIATING,
    WAIT_CONFIG,
    WAIT_RANDOM_ACCESS,
    STREAMING,
    RESYNCING,
    CLOSED
}
