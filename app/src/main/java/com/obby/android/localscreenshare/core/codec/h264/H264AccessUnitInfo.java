package com.obby.android.localscreenshare.core.codec.h264;

/**
 * Aggregated result of analyzing one H.264 access unit (or packet) for codec
 * configuration and keyframe policy decisions.
 */
public final class H264AccessUnitInfo {

    private final int spsCount;
    private final int ppsCount;
    private final int vclCount;
    private final boolean hasIdr;
    private final boolean hasBSlice;
    private final boolean allIdrFirstMbZero;
    private final boolean allIdrIntra;
    private final H264SliceHeader firstSliceHeader;

    H264AccessUnitInfo(int spsCount, int ppsCount, int vclCount,
                       boolean hasIdr, boolean hasBSlice,
                       boolean allIdrFirstMbZero, boolean allIdrIntra,
                       H264SliceHeader firstSliceHeader) {
        this.spsCount = spsCount;
        this.ppsCount = ppsCount;
        this.vclCount = vclCount;
        this.hasIdr = hasIdr;
        this.hasBSlice = hasBSlice;
        this.allIdrFirstMbZero = allIdrFirstMbZero;
        this.allIdrIntra = allIdrIntra;
        this.firstSliceHeader = firstSliceHeader;
    }

    public boolean hasSps() {
        return spsCount > 0;
    }

    public boolean hasPps() {
        return ppsCount > 0;
    }

    /** True when a type 5 NAL with nal_ref_idc != 0 is present. */
    public boolean hasIdr() {
        return hasIdr;
    }

    /** True when any VCL NAL carries a B slice_type (1 or 6). */
    public boolean hasBSlice() {
        return hasBSlice;
    }

    public boolean isJoinable() {
        return vclCount >= 1 && hasIdr && !hasBSlice && allIdrFirstMbZero && allIdrIntra;
    }

    public int getSpsCount() {
        return spsCount;
    }

    public int getPpsCount() {
        return ppsCount;
    }

    public int getVclCount() {
        return vclCount;
    }

    /** Slice header of the first VCL NAL in the access unit, or {@code null}. */
    public H264SliceHeader getFirstSliceHeader() {
        return firstSliceHeader;
    }
}
