package com.obby.android.localscreenshare.core.codec.h264;

/**
 * The subset of an H.264 slice header needed for codec policy decisions:
 * {@code first_mb_in_slice}, {@code slice_type}, and {@code pic_parameter_set_id}.
 * The full slice header is intentionally not parsed.
 *
 * <p><b>Why only three fields.</b> Joinability needs {@code first_mb_in_slice == 0} (the slice
 * starts the picture) plus an I or SI slice type; B-slice detection needs {@code slice_type}. Every
 * further field depends on the active PPS and SPS (frame numbering, reference lists, deblocking
 * parameters), so parsing on would mean implementing parameter-set interpretation for no policy
 * benefit. {@code pic_parameter_set_id} is retained so a caller can relate the slice to the
 * parameter sets it was parsed with; this adapter records it but does not resolve it against a PPS
 * table.</p>
 *
 * <p><b>slice_type.</b> The raw code is kept rather than folded to its base value: 0 = P, 1 = B,
 * 2 = I, 3 = SP, 4 = SI, and 5..9 repeat those values for pictures whose slices all share the type.
 * Callers that care about the underlying type must use {@code sliceType % 5}, as
 * {@link H264AccessUnitInfo#isJoinable()} does.</p>
 *
 * <p>Instances are immutable. Parsing reads only the first three Exp-Golomb codes of the NAL's RBSP
 * and fails when the payload is too short.</p>
 */
public final class H264SliceHeader {

    private final int firstMbInSlice;
    private final int sliceType;
    private final int picParameterSetId;

    private H264SliceHeader(int firstMbInSlice, int sliceType, int picParameterSetId) {
        this.firstMbInSlice = firstMbInSlice;
        this.sliceType = sliceType;
        this.picParameterSetId = picParameterSetId;
    }

    /** Zero-based index of the first macroblock covered by this slice; 0 starts the picture. */
    public int getFirstMbInSlice() {
        return firstMbInSlice;
    }

    /** Raw {@code slice_type} code (0..9); use {@code % 5} to get P/B/I/SP/SI. */
    public int getSliceType() {
        return sliceType;
    }

    /** Id of the picture parameter set this slice refers to, as written in the bitstream. */
    public int getPicParameterSetId() {
        return picParameterSetId;
    }

    /**
     * Parses the three leading Exp-Golomb fields of a VCL unit's RBSP.
     *
     * @param unit a VCL NAL unit; its header byte is skipped
     * @throws H264FormatException when the RBSP ends before the third field
     */
    static H264SliceHeader parse(H264NalUnit unit) {
        H264BitReader reader = new H264BitReader(unit.getRbsp());
        int firstMb = reader.readUnsignedExpGolomb();
        int sliceType = reader.readUnsignedExpGolomb();
        int ppsId = reader.readUnsignedExpGolomb();
        return new H264SliceHeader(firstMb, sliceType, ppsId);
    }
}
