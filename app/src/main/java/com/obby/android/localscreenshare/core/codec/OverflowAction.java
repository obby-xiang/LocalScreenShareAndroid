package com.obby.android.localscreenshare.core.codec;

/**
 * How a codec's stream behaves when a receiver overflows.
 */
public enum OverflowAction {
    /** Drop older queued units and keep only the newest one; valid when every frame decodes alone. */
    KEEP_LATEST_FRAME,

    /**
     * Drop queued units and refuse new ones until a join point arrives; required when frames
     * depend on earlier reference frames.
     */
    DROP_UNTIL_JOIN_POINT
}
