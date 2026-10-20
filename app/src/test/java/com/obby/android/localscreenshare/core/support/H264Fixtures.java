package com.obby.android.localscreenshare.core.support;

import java.io.ByteArrayOutputStream;

/**
 * Synthetic H.264 NAL units and framing helpers. Every byte is generated here;
 * no fixture comes from MediaCodec, a file, or a device.
 *
 * <p>Slice fixtures stop after {@code idr_pic_id}: they carry no macroblock payload, so they
 * exercise header parsing and join-point classification rather than real decoding. Config
 * fixtures are written as RBSP; callers opt into emulation prevention explicitly through
 * {@link #emulationPrevented(byte[])}.</p>
 */
public final class H264Fixtures {

    /**
     * PPS id chosen so its Exp-Golomb code forces {@code 00 00 03} into the slice header.
     */
    public static final int IDR_PPS_ID_WITH_EMULATION_PREVENTION = 0x7FFFFF;

    private static final int NAL_HEADER_SPS = 0x67;
    private static final int NAL_HEADER_PPS = 0x68;
    private static final int NAL_HEADER_IDR = 0x65;
    private static final int NAL_HEADER_IDR_ZERO_REF_IDC = 0x05;
    private static final int NAL_HEADER_NON_IDR_REF = 0x41;
    private static final int NAL_HEADER_NON_IDR_NO_REF = 0x01;

    private static final int SLICE_TYPE_P = 0;
    private static final int SLICE_TYPE_I = 2;
    private static final int SLICE_TYPE_B = 6;
    private static final int SLICE_TYPE_I_ALL = 7;

    private static final byte[] START_CODE = {0, 0, 0, 1};
    private static final byte[] SHORT_START_CODE = {0, 0, 1};

    /**
     * Baseline-profile SPS (profile 66, level 3.0) describing a 320x240 frame.
     */
    public static byte[] sps() {
        return new H264BitWriter()
                .writeBytes(new byte[]{(byte) NAL_HEADER_SPS})
                .writeBits(66, 8)
                .writeBits(0, 8)
                .writeBits(30, 8)
                .writeUnsignedExpGolomb(0)
                .writeUnsignedExpGolomb(0)
                .writeUnsignedExpGolomb(2)
                .writeUnsignedExpGolomb(1)
                .writeBit(0)
                .writeUnsignedExpGolomb(19)
                .writeUnsignedExpGolomb(14)
                .writeBit(1)
                .writeBit(1)
                .writeBit(0)
                .writeBit(0)
                .writeRbspTrailingBits()
                .toByteArray();
    }

    /**
     * PPS 0 with CAVLC entropy coding, referencing SPS 0.
     */
    public static byte[] pps() {
        return new H264BitWriter()
                .writeBytes(new byte[]{(byte) NAL_HEADER_PPS})
                .writeUnsignedExpGolomb(0)
                .writeUnsignedExpGolomb(0)
                .writeBit(0)
                .writeBit(0)
                .writeUnsignedExpGolomb(0)
                .writeUnsignedExpGolomb(0)
                .writeUnsignedExpGolomb(0)
                .writeBit(0)
                .writeBits(0, 2)
                .writeSignedExpGolomb(0)
                .writeSignedExpGolomb(0)
                .writeSignedExpGolomb(0)
                .writeBit(0)
                .writeBit(0)
                .writeBit(0)
                .writeRbspTrailingBits()
                .toByteArray();
    }

    public static byte[] idrSlice() {
        return slice(NAL_HEADER_IDR, 0, SLICE_TYPE_I, 0);
    }

    public static byte[] pSlice() {
        return slice(NAL_HEADER_NON_IDR_REF, 0, SLICE_TYPE_P, 0);
    }

    public static byte[] iSliceTypeOne() {
        return slice(NAL_HEADER_NON_IDR_REF, 0, SLICE_TYPE_I, 0);
    }

    public static byte[] bSlice() {
        return slice(NAL_HEADER_NON_IDR_NO_REF, 0, SLICE_TYPE_B, 0);
    }

    public static byte[] idrWithNonZeroFirstMb() {
        return slice(NAL_HEADER_IDR, 1, SLICE_TYPE_I, 0);
    }

    public static byte[] idrWithBSliceType() {
        return slice(NAL_HEADER_IDR, 0, SLICE_TYPE_B, 0);
    }

    public static byte[] idrWithPSliceType() {
        return slice(NAL_HEADER_IDR, 0, SLICE_TYPE_P, 0);
    }

    public static byte[] idrWithZeroNalRefIdc() {
        return slice(NAL_HEADER_IDR_ZERO_REF_IDC, 0, SLICE_TYPE_I, 0);
    }

    /**
     * IDR whose slice header carries an emulation prevention byte: the large
     * pic_parameter_set_id is structurally valid Exp-Golomb, and forces
     * {@code 00 00 03} inside the parsed fields.
     */
    public static byte[] idrWithEmulationPrevention() {
        byte[] rbsp = new H264BitWriter()
                .writeBytes(new byte[]{(byte) NAL_HEADER_IDR})
                .writeUnsignedExpGolomb(0)
                .writeUnsignedExpGolomb(SLICE_TYPE_I_ALL)
                .writeUnsignedExpGolomb(IDR_PPS_ID_WITH_EMULATION_PREVENTION)
                .writeUnsignedExpGolomb(0)
                .writeRbspTrailingBits()
                .toByteArray();
        return emulationPrevented(rbsp);
    }

    public static byte[] annexB(byte[]... nalUnits) {
        return annexBWith(START_CODE, nalUnits);
    }

    public static byte[] annexBWithThreeByteStartCodes(byte[]... nalUnits) {
        return annexBWith(SHORT_START_CODE, nalUnits);
    }

    public static byte[] avcc(byte[]... nalUnits) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] nalUnit : nalUnits) {
            int length = nalUnit.length;
            out.write((length >>> 24) & 0xFF);
            out.write((length >>> 16) & 0xFF);
            out.write((length >>> 8) & 0xFF);
            out.write(length & 0xFF);
            out.write(nalUnit, 0, nalUnit.length);
        }
        return out.toByteArray();
    }

    public static byte[] inBandConfigAndIdr() {
        return annexB(sps(), pps(), idrSlice());
    }

    public static byte[] inBandConfigPlusIdrPlusP() {
        return annexB(sps(), pps(), idrSlice(), pSlice());
    }

    public static byte[] spsPpsIdrInOneAu() {
        return annexBWithThreeByteStartCodes(sps(), pps(), idrSlice());
    }

    /**
     * Inserts 0x03 wherever two consecutive zero bytes are followed by a byte
     * in {@code 0x00..0x03}, per the H.264 emulation prevention rule.
     */
    public static byte[] emulationPrevented(byte[] rbsp) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int zeroCount = 0;
        for (byte b : rbsp) {
            int value = b & 0xFF;
            if (zeroCount >= 2 && value <= 0x03) {
                out.write(0x03);
                zeroCount = 0;
            }
            out.write(value);
            zeroCount = value == 0 ? zeroCount + 1 : 0;
        }
        return out.toByteArray();
    }

    private static byte[] slice(int header, int firstMbInSlice, int sliceType, int ppsId) {
        return new H264BitWriter()
                .writeBytes(new byte[]{(byte) header})
                .writeUnsignedExpGolomb(firstMbInSlice)
                .writeUnsignedExpGolomb(sliceType)
                .writeUnsignedExpGolomb(ppsId)
                .writeUnsignedExpGolomb(0)
                .writeUnsignedExpGolomb(0)
                .writeRbspTrailingBits()
                .toByteArray();
    }

    private static byte[] annexBWith(byte[] startCode, byte[]... nalUnits) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] nalUnit : nalUnits) {
            out.write(startCode, 0, startCode.length);
            out.write(nalUnit, 0, nalUnit.length);
        }
        return out.toByteArray();
    }

    private H264Fixtures() {
    }
}
