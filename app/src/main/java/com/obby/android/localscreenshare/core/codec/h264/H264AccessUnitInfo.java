package com.obby.android.localscreenshare.core.codec.h264;

/**
 * Aggregated result of analyzing one H.264 access unit (or packet) for codec
 * configuration and keyframe policy decisions.
 */
public final class H264AccessUnitInfo {

    private static final int NAL_TYPE_IDR = 5;

    private final int spsCount;
    private final int ppsCount;
    private final int vclCount;
    private final boolean hasIdr;
    private final boolean hasBSlice;
    private final int firstVclType;
    private final int firstVclRefIdc;
    private final H264SliceHeader firstSliceHeader;

    H264AccessUnitInfo(int spsCount, int ppsCount, int vclCount,
                       boolean hasIdr, boolean hasBSlice,
                       int firstVclType, int firstVclRefIdc,
                       H264SliceHeader firstSliceHeader) {
        this.spsCount = spsCount;
        this.ppsCount = ppsCount;
        this.vclCount = vclCount;
        this.hasIdr = hasIdr;
        this.hasBSlice = hasBSlice;
        this.firstVclType = firstVclType;
        this.firstVclRefIdc = firstVclRefIdc;
        this.firstSliceHeader = firstSliceHeader;
    }

    public boolean hasSps() {
        return spsCount > 0;
    }

    public boolean hasPps() {
        return ppsCount > 0;
    }

    /**
     * True when a type 5 NAL with nal_ref_idc != 0 is present.
     */
    public boolean hasIdr() {
        return hasIdr;
    }

    /**
     * True when any VCL NAL carries a B slice_type (1 or 6).
     */
    public boolean hasBSlice() {
        return hasBSlice;
    }

    /**
     * True when the access unit contains at least one VCL NAL.
     */
    public boolean hasVcl() {
        return vclCount > 0;
    }

    /**
     * Whether this access unit is a validated random-access point a new decoder can start from.
     *
     * <p>All of the following must hold:</p>
     * <ul>
     *   <li>the access unit contains at least one VCL unit;</li>
     *   <li>the first VCL unit is a type 5 (IDR) NAL unit;</li>
     *   <li>that unit has {@code nal_ref_idc != 0};</li>
     *   <li>its {@code first_mb_in_slice} is {@code 0}, so the slice starts the picture;</li>
     *   <li>its {@code slice_type % 5} is 2 (I) or 4 (SI).</li>
     * </ul>
     *
     * <p>The per-slice conditions are evaluated on the first VCL unit only, while the B-slice rule
     * covers every VCL unit: an access unit carrying a B slice anywhere is not joinable, because
     * this adapter rejects B slices on both the encode and the decode side.</p>
     *
     * <p>An encoder's keyframe flag is never consulted; the decision comes from the payload alone.
     * This is the predicate behind {@link H264AnnexBParser#isIdrAccessUnit(byte[])}.</p>
     */
    public boolean isJoinable() {
        if (!hasVcl()) {
            return false;
        }
        if (firstVclType != NAL_TYPE_IDR) {
            return false;
        }
        if (firstVclRefIdc == 0) {
            return false;
        }
        if (firstSliceHeader == null || firstSliceHeader.getFirstMbInSlice() != 0) {
            return false;
        }
        int sliceType = firstSliceHeader.getSliceType();
        if (sliceType % 5 != 2 && sliceType % 5 != 4) {
            return false;
        }
        return !hasBSlice;
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

    /**
     * Slice header of the first VCL NAL in the access unit, or {@code null}.
     */
    public H264SliceHeader getFirstSliceHeader() {
        return firstSliceHeader;
    }
}
