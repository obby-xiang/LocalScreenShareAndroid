package com.obby.android.localscreenshare.core.codec.h264;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pure-Java H.264 Annex B byte-stream parser.
 */
public final class H264AnnexBParser {

    private static final int NAL_TYPE_IDR = 5;
    private static final int NAL_TYPE_SPS = 7;
    private static final int NAL_TYPE_PPS = 8;

    public H264AnnexBParser() {
    }

    public static List<H264NalUnit> parse(byte[] annexB) throws H264FormatException {
        if (annexB == null || annexB.length == 0) {
            throw new H264FormatException("empty or null Annex B input");
        }

        List<Integer> startPositions = new ArrayList<Integer>();
        for (int i = 0; i + 2 < annexB.length; i++) {
            if (annexB[i] == 0 && annexB[i + 1] == 0 && annexB[i + 2] == 1) {
                int scStart = (i > 0 && annexB[i - 1] == 0) ? i - 1 : i;
                startPositions.add(Integer.valueOf(scStart));
            }
        }

        if (startPositions.isEmpty()) {
            throw new H264FormatException("no start code found");
        }

        List<H264NalUnit> units = new ArrayList<H264NalUnit>();
        for (int s = 0; s < startPositions.size(); s++) {
            int scStart = startPositions.get(s).intValue();
            int scLength = startCodeLength(annexB, scStart);
            int nalStart = scStart + scLength;
            int nalEnd = (s + 1 < startPositions.size())
                    ? startPositions.get(s + 1).intValue() : annexB.length;
            if (s + 1 == startPositions.size()) {
                while (nalEnd > nalStart && annexB[nalEnd - 1] == 0) {
                    nalEnd--;
                }
            }
            if (nalEnd <= nalStart) {
                throw new H264FormatException("empty NAL unit");
            }
            units.add(new H264NalUnit(Arrays.copyOfRange(annexB, nalStart, nalEnd)));
        }
        return units;
    }

    public static H264AccessUnitInfo analyze(byte[] annexB) throws H264FormatException {
        List<H264NalUnit> units = H264AnnexBParser.parse(annexB);

        int spsCount = 0;
        int ppsCount = 0;
        int vclCount = 0;
        boolean hasIdr = false;
        boolean hasBSlice = false;
        int firstVclType = -1;
        int firstVclRefIdc = -1;
        H264SliceHeader firstSliceHeader = null;

        for (H264NalUnit unit : units) {
            int type = unit.getType();
            if (type == NAL_TYPE_SPS) {
                spsCount++;
            } else if (type == NAL_TYPE_PPS) {
                ppsCount++;
            }

            if (unit.isVcl()) {
                vclCount++;
                H264SliceHeader header = H264SliceHeader.parse(unit);
                if (firstSliceHeader == null) {
                    firstSliceHeader = header;
                    firstVclType = type;
                    firstVclRefIdc = unit.getRefIdc();
                }
                int sliceType = header.getSliceType();
                if (sliceType == 1 || sliceType == 6) {
                    hasBSlice = true;
                }
                if (type == NAL_TYPE_IDR && unit.getRefIdc() != 0) {
                    hasIdr = true;
                }
            }
        }

        return new H264AccessUnitInfo(spsCount, ppsCount, vclCount, hasIdr,
                hasBSlice, firstVclType, firstVclRefIdc, firstSliceHeader);
    }

    /** Policy-safe entry point: never throws, returns false on any malformed input. */
    public static boolean isIdrAccessUnit(byte[] payload) {
        try {
            return H264AnnexBParser.analyze(payload).isJoinable();
        } catch (H264FormatException e) {
            return false;
        }
    }

    /**
     * True when the buffer is consumed exactly by a sequence of 4-byte
     * big-endian length prefixes, i.e. AVCC framing.
     */
    public static boolean looksLengthPrefixed(byte[] payload) {
        if (payload == null || payload.length < 4) {
            return false;
        }
        int pos = 0;
        while (pos < payload.length) {
            if (pos + 4 > payload.length) {
                return false;
            }
            int length = ((payload[pos] & 0xFF) << 24)
                    | ((payload[pos + 1] & 0xFF) << 16)
                    | ((payload[pos + 2] & 0xFF) << 8)
                    | (payload[pos + 3] & 0xFF);
            pos += 4;
            if (length < 0 || length > payload.length - pos) {
                return false;
            }
            pos += length;
        }
        return true;
    }

    /** Removes SPS/PPS NAL units, keeps VCL, emits canonical 4-byte start codes. */
    public static byte[] stripSpsPps(byte[] annexB) throws H264FormatException {
        List<H264NalUnit> units = H264AnnexBParser.parse(annexB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (H264NalUnit unit : units) {
            int type = unit.getType();
            if (type == NAL_TYPE_SPS || type == NAL_TYPE_PPS) {
                continue;
            }
            out.write(0);
            out.write(0);
            out.write(0);
            out.write(1);
            byte[] bytes = unit.getBytes();
            out.write(bytes, 0, bytes.length);
        }
        return out.toByteArray();
    }

    private static int startCodeLength(byte[] data, int scStart) {
        if (scStart + 4 <= data.length
                && data[scStart] == 0 && data[scStart + 1] == 0
                && data[scStart + 2] == 0 && data[scStart + 3] == 1) {
            return 4;
        }
        return 3;
    }
}
