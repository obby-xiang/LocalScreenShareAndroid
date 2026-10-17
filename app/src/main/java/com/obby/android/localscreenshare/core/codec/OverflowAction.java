package com.obby.android.localscreenshare.core.codec;

/**
 * How a codec's stream behaves when a receiver overflows.
 */
public enum OverflowAction {
    KEEP_LATEST_FRAME,
    DROP_UNTIL_JOIN_POINT
}
