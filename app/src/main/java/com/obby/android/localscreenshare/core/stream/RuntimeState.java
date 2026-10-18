package com.obby.android.localscreenshare.core.stream;

/**
 * Lifecycle state of a {@link StreamRuntime}.
 */
public enum RuntimeState {
    IDLE, CONFIGURING, READY, FAILED, CLOSED
}
