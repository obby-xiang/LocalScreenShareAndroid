package com.obby.android.localscreenshare.discovery.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * How a device admits new viewers.
 *
 * <p>Advertised before connecting so the list can show whether a tap leads straight to a stream,
 * to an approval prompt, or nowhere at all.</p>
 */
public enum JoinPolicy {
    /**
     * Any viewer is admitted without interaction.
     */
    OPEN,

    /**
     * The owner has to approve each viewer.
     */
    APPROVAL,

    /**
     * New viewers are rejected; existing sessions continue.
     */
    CLOSED,

    /**
     * Policy not advertised or not understood.
     */
    UNKNOWN;

    /**
     * Maps a wire value to a policy; null, blank and unknown values return {@link #UNKNOWN}.
     */
    @NonNull
    public static JoinPolicy fromWire(@Nullable final String value) {
        if (value == null) {
            return UNKNOWN;
        }
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "open":
                return OPEN;
            case "approval":
                return APPROVAL;
            case "closed":
                return CLOSED;
            default:
                return UNKNOWN;
        }
    }

    /**
     * Returns the lowercase wire value of this policy.
     */
    @NonNull
    public String toWire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
