package com.obby.android.localscreenshare.core.model;

/**
 * Immutable color-space description carried by a stream's decode configuration.
 *
 * <p>{@link #UNSPECIFIED} means "not reported", not "no color"; the stream runtime substitutes
 * the requested color when an encoder reports this value. {@code fullRange} distinguishes
 * full-range from limited-range luma and chroma. The class has no value-based {@code equals};
 * components that need to compare colors compare the three fields.</p>
 */
public final class ColorInfo {

    /** Placeholder for an unknown or unreported color description. */
    public static final ColorInfo UNSPECIFIED = new ColorInfo(null, null, false);

    private final String colorSpace;
    private final String transferFunction;
    private final boolean fullRange;

    public ColorInfo(String colorSpace, String transferFunction, boolean fullRange) {
        this.colorSpace = colorSpace;
        this.transferFunction = transferFunction;
        this.fullRange = fullRange;
    }

    public String getColorSpace() {
        return colorSpace;
    }

    public String getTransferFunction() {
        return transferFunction;
    }

    public boolean isFullRange() {
        return fullRange;
    }
}
