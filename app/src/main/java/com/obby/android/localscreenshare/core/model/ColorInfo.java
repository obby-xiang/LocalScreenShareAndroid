package com.obby.android.localscreenshare.core.model;

/**
 * Immutable color-space description carried by a stream's decode configuration.
 */
public final class ColorInfo {

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
