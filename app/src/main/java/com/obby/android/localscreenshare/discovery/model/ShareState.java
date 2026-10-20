package com.obby.android.localscreenshare.discovery.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Whether a device is currently streaming.
 *
 * <p>This is activity, not reachability: a device that is heard but never confirmed is still
 * {@link #IDLE}, and reachability is expressed separately by
 * {@link DevicePresence}.</p>
 */
public enum ShareState {
    /**
     * A capture session is running.
     */
    LIVE,

    /**
     * Device is reachable but not streaming.
     */
    IDLE,

    /**
     * State not advertised or not understood.
     */
    UNKNOWN;

    /**
     * Maps a wire value to a state; null, blank and unknown values return {@link #UNKNOWN}.
     */
    @NonNull
    public static ShareState fromWire(@Nullable final String value) {
        if (value == null) {
            return UNKNOWN;
        }
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "live":
                return LIVE;
            case "idle":
                return IDLE;
            default:
                return UNKNOWN;
        }
    }

    /**
     * Returns the lowercase wire value of this state.
     */
    @NonNull
    public String toWire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
