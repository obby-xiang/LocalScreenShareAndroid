package com.obby.android.localscreenshare.discovery.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Authentication a device requires before a media session is accepted.
 *
 * <p>{@link #MTLS} is reserved for the pinned-TLS work; until then the only advertised value is
 * {@link #NONE}.</p>
 */
public enum AuthMode {
    /** No authentication. */
    NONE,

    /** Pairing PIN entered by the viewer. */
    PIN,

    /** Mutual TLS with a pinned device certificate. */
    MTLS,

    /** Mode not advertised or not understood. */
    UNKNOWN;

    /** Maps a wire value to a mode; null, blank and unknown values return {@link #UNKNOWN}. */
    @NonNull
    public static AuthMode fromWire(@Nullable final String value) {
        if (value == null) {
            return UNKNOWN;
        }
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "none":
                return NONE;
            case "pin":
                return PIN;
            case "mtls":
                return MTLS;
            default:
                return UNKNOWN;
        }
    }

    /** Returns the lowercase wire value of this mode. */
    @NonNull
    public String toWire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
