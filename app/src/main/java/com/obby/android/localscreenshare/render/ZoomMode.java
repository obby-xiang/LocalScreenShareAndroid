package com.obby.android.localscreenshare.render;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Scaling strategy applied to the floating viewer window.
 *
 * <p>The mode decides how the user's viewing percentage is turned into a window size; it never
 * changes the stream itself. {@link #FIT} keeps the percentage as a fraction of the usable
 * display area, {@link #ONE_TO_ONE} asks for one window pixel per stream pixel, and
 * {@link #INTEGER} quantizes the percentage to the nearest whole-number reduction so text stays
 * legible when the window has to shrink.</p>
 *
 * <p>The geometry rules live in {@link ViewerFitPolicy}; this enum only names the strategy and
 * carries no Android types, so it can be persisted and unit-tested on the JVM.</p>
 */
public enum ZoomMode {

    /**
     * Fits the content into the usable display area at the user's percentage, keeping the aspect
     * ratio. This is the default and the only mode that reproduces the legacy window sizing.
     */
    FIT,

    /**
     * Sizes the window to the content pixels (one window pixel per stream pixel), reduced
     * uniformly and reported as clamped when the content is larger than the usable display area.
     */
    ONE_TO_ONE,

    /**
     * Snaps the percentage to the nearest of {@code 1}, {@code 1/2}, {@code 1/3} and
     * {@code 1/4}, so the sampled ratio stays a whole-number fraction and downscaling stays
     * legible. Ties snap towards the larger window.
     */
    INTEGER;

    private static final String WIRE_FIT = "fit";

    private static final String WIRE_ONE_TO_ONE = "one_to_one";

    private static final String WIRE_INTEGER = "integer";

    /**
     * Returns the stable wire value persisted in the preferences and carried by the UI.
     *
     * <p>The values are spelled out instead of derived from {@link #name()} so renaming a constant
     * cannot silently change what an existing preference means.</p>
     */
    @NonNull
    public String wire() {
        switch (this) {
            case ONE_TO_ONE:
                return WIRE_ONE_TO_ONE;
            case INTEGER:
                return WIRE_INTEGER;
            case FIT:
            default:
                return WIRE_FIT;
        }
    }

    /**
     * Parses a persisted wire value; an empty, null or unknown value means {@link #FIT}.
     *
     * <p>An unknown value falls back to the default instead of throwing: the preference may have
     * been written by a newer or older version, and a viewer that cannot size its own window is
     * worse than one that ignores an unknown preset.</p>
     */
    @NonNull
    public static ZoomMode fromWire(@Nullable final String wire) {
        if (WIRE_ONE_TO_ONE.equals(wire)) {
            return ONE_TO_ONE;
        }
        if (WIRE_INTEGER.equals(wire)) {
            return INTEGER;
        }
        return FIT;
    }
}
