package com.obby.android.localscreenshare.render;

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
    INTEGER
}
