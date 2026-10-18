package com.obby.android.localscreenshare.core.codec.h264;

/**
 * The subset of an H.264 slice header needed for codec policy decisions:
 * {@code first_mb_in_slice}, {@code slice_type}, and {@code pic_parameter_set_id}.
 * The full slice header is intentionally not parsed.
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

    public int getFirstMbInSlice() {
        return firstMbInSlice;
    }

    public int getSliceType() {
        return sliceType;
    }

    public int getPicParameterSetId() {
        return picParameterSetId;
    }

    static H264SliceHeader parse(H264NalUnit unit) {
        H264BitReader reader = new H264BitReader(unit.getRbsp());
        int firstMb = reader.readUnsignedExpGolomb();
        int sliceType = reader.readUnsignedExpGolomb();
        int ppsId = reader.readUnsignedExpGolomb();
        return new H264SliceHeader(firstMb, sliceType, ppsId);
    }
}
