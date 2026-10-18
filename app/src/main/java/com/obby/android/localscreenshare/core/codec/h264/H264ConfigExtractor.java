package com.obby.android.localscreenshare.core.codec.h264;

import com.obby.android.localscreenshare.core.model.CodecConfigPart;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Extracts SPS and PPS from an H.264 codec configuration (CSD) or in-band
 * payload, accepting both AVCC (length-prefixed) and Annex B framing.
 */
public final class H264ConfigExtractor {

    private static final int NAL_TYPE_SPS = 7;
    private static final int NAL_TYPE_PPS = 8;

    public H264ConfigExtractor() {
    }

    public List<CodecConfigPart> extract(byte[] csdOrPayload) throws H264FormatException {
        return buildParts(splitNalUnits(csdOrPayload));
    }

    /**
     * Builds SPS/PPS config parts from two independent codec-specific-data
     * blobs. Each blob is framed and parsed separately (Annex B or AVCC), then
     * merged before validating exactly one SPS and one PPS.
     */
    public static List<CodecConfigPart> fromCodecSpecificData(byte[] csd0, byte[] csd1)
            throws H264FormatException {
        List<byte[]> nalUnits = new ArrayList<byte[]>();
        nalUnits.addAll(splitCsdOrEmpty(csd0));
        nalUnits.addAll(splitCsdOrEmpty(csd1));
        return buildParts(nalUnits);
    }

    /** Extracts SPS/PPS from an in-band payload, ignoring VCL NAL units. */
    public static List<CodecConfigPart> fromInBandPayload(byte[] payload)
            throws H264FormatException {
        return new H264ConfigExtractor().extract(payload);
    }

    /** Returns the unique SPS raw NAL payload (defensive copy), or throws when missing or duplicated. */
    public static byte[] sps(List<CodecConfigPart> parts) throws H264FormatException {
        return findUnique(parts, "sps").getPayload();
    }

    /** Returns the unique PPS raw NAL payload (defensive copy), or throws when missing or duplicated. */
    public static byte[] pps(List<CodecConfigPart> parts) throws H264FormatException {
        return findUnique(parts, "pps").getPayload();
    }

    /** Returns {@code 00 00 00 01} + nal, four bytes longer than the input. */
    public static byte[] toAnnexB(byte[] nal) {
        byte[] out = new byte[nal.length + 4];
        out[0] = 0;
        out[1] = 0;
        out[2] = 0;
        out[3] = 1;
        System.arraycopy(nal, 0, out, 4, nal.length);
        return out;
    }

    private static List<CodecConfigPart> buildParts(List<byte[]> nalUnits)
            throws H264FormatException {
        byte[] sps = null;
        byte[] pps = null;
        int spsCount = 0;
        int ppsCount = 0;
        for (byte[] nal : nalUnits) {
            if (nal.length == 0) {
                continue;
            }
            int type = nal[0] & 0x1F;
            if (type == NAL_TYPE_SPS) {
                spsCount++;
                if (sps == null) {
                    sps = nal;
                }
            } else if (type == NAL_TYPE_PPS) {
                ppsCount++;
                if (pps == null) {
                    pps = nal;
                }
            }
        }

        if (spsCount != 1) {
            throw new H264FormatException("expected exactly one SPS, found " + spsCount);
        }
        if (ppsCount != 1) {
            throw new H264FormatException("expected exactly one PPS, found " + ppsCount);
        }

        List<CodecConfigPart> parts = new ArrayList<CodecConfigPart>();
        parts.add(new CodecConfigPart("sps", sps));
        parts.add(new CodecConfigPart("pps", pps));
        return parts;
    }

    private static CodecConfigPart findUnique(List<CodecConfigPart> parts, String id)
            throws H264FormatException {
        CodecConfigPart found = null;
        if (parts != null) {
            for (CodecConfigPart part : parts) {
                if (part != null && id.equals(part.getId())) {
                    if (found != null) {
                        throw new H264FormatException("duplicate " + id);
                    }
                    found = part;
                }
            }
        }
        if (found == null) {
            throw new H264FormatException("missing " + id);
        }
        return found;
    }

    private static List<byte[]> splitCsdOrEmpty(byte[] data) throws H264FormatException {
        if (data == null || data.length == 0) {
            return new ArrayList<byte[]>();
        }
        return splitNalUnits(data);
    }

    private static List<byte[]> splitNalUnits(byte[] data) throws H264FormatException {
        if (data == null || data.length == 0) {
            throw new H264FormatException("empty or null input");
        }
        if (H264AnnexBParser.looksLengthPrefixed(data)) {
            return parseAvcc(data);
        }
        List<H264NalUnit> units = H264AnnexBParser.parse(data);
        List<byte[]> result = new ArrayList<byte[]>();
        for (H264NalUnit unit : units) {
            result.add(unit.getBytes());
        }
        return result;
    }

    private static List<byte[]> parseAvcc(byte[] data) {
        List<byte[]> units = new ArrayList<byte[]>();
        int pos = 0;
        while (pos < data.length) {
            int length = ((data[pos] & 0xFF) << 24)
                    | ((data[pos + 1] & 0xFF) << 16)
                    | ((data[pos + 2] & 0xFF) << 8)
                    | (data[pos + 3] & 0xFF);
            pos += 4;
            units.add(Arrays.copyOfRange(data, pos, pos + length));
            pos += length;
        }
        return units;
    }
}
