package com.obby.android.localscreenshare.discovery.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Role a discovered device claims for itself.
 *
 * <p>The wire form is the lowercase enum name used by the announcement and TXT records. Unknown or
 * missing values map to {@link #UNKNOWN} instead of failing the whole observation, because a
 * legacy advertisement carries no role field at all.</p>
 */
public enum DeviceRole {
    /** Device only sends screen captures. */
    SERVER,

    /** Device only views screen captures. */
    CLIENT,

    /** Device can both send and view. */
    BOTH,

    /** Role not advertised or not understood. */
    UNKNOWN;

    /** Maps a wire value to a role; null, blank and unknown values return {@link #UNKNOWN}. */
    @NonNull
    public static DeviceRole fromWire(@Nullable final String value) {
        if (value == null) {
            return UNKNOWN;
        }
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "server":
                return SERVER;
            case "client":
                return CLIENT;
            case "both":
                return BOTH;
            default:
                return UNKNOWN;
        }
    }

    /** Returns the lowercase wire value of this role. */
    @NonNull
    public String toWire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
