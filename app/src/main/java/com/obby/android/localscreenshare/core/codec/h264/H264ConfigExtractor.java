package com.obby.android.localscreenshare.core.codec.h264;

import com.obby.android.localscreenshare.core.codec.mediacodec.DecoderFormat;
import com.obby.android.localscreenshare.core.model.CodecConfigPart;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Extracts SPS and PPS from an H.264 codec configuration (CSD) or in-band
 * payload, accepting both AVCC (length-prefixed) and Annex B framing.
 *
 * <p><b>Output contract.</b> A successful call returns exactly two {@link CodecConfigPart}s with
 * ids {@code sps} and {@code pps}, in that order. Each payload is a raw NAL unit: header byte
 * included, start code and AVCC length prefix removed. That is the form a stream descriptor stores,
 * and the form {@link #toAnnexB(byte[])} turns back into the framing a platform decoder expects.</p>
 *
 * <p><b>Cardinality.</b> Exactly one SPS and exactly one PPS are supported. Zero, duplicates or any
 * other count fail with {@link H264FormatException}: a stream descriptor cannot express a set of
 * parameter sets, so a multi-SPS/PPS stream is out of scope for this adapter rather than silently
 * reduced to the first of each.</p>
 *
 * <p><b>Framing.</b> Each input blob is classified on its own by
 * {@link H264AnnexBParser#looksLengthPrefixed(byte[])}: length-prefixed blobs are read as AVCC,
 * everything else as Annex B. Splitting, counting and validation are stateless.</p>
 */
public final class H264ConfigExtractor {

    private static final int NAL_TYPE_SPS = 7;
    private static final int NAL_TYPE_PPS = 8;

    public H264ConfigExtractor() {
    }

    /**
     * Extracts SPS/PPS from a single blob, framed as AVCC or Annex B.
     *
     * <p>Non-configuration units (VCL, SEI, AUD) in the same blob are ignored: they neither fail the
     * extraction nor count towards the cardinality check.</p>
     *
     * @param csdOrPayload configuration blob or in-band packet
     * @return exactly one {@code sps} and one {@code pps} part
     * @throws H264FormatException if the input is null or empty, cannot be split, or does not hold
     *                             exactly one SPS and one PPS
     */
    public List<CodecConfigPart> extract(byte[] csdOrPayload) throws H264FormatException {
        return buildParts(splitNalUnits(csdOrPayload));
    }

    /**
     * Builds SPS/PPS config parts from two independent codec-specific-data
     * blobs. Each blob is framed and parsed separately (Annex B or AVCC), then
     * merged before validating exactly one SPS and one PPS.
     *
     * <p>A null or empty blob is skipped, because devices place the parameter sets in
     * {@code csd-0}/{@code csd-1} in either order or put both in a single blob. The merge happens
     * before counting, so a parameter set duplicated across the two blobs is rejected just like one
     * duplicated inside a single blob.</p>
     *
     * @param csd0 first codec-specific-data blob; {@code null} counts as absent
     * @param csd1 second codec-specific-data blob; {@code null} counts as absent
     * @return exactly one {@code sps} and one {@code pps} part
     * @throws H264FormatException if a non-empty blob cannot be split, or the merged units do not
     *                             hold exactly one SPS and one PPS
     */
    public static List<CodecConfigPart> fromCodecSpecificData(byte[] csd0, byte[] csd1)
            throws H264FormatException {
        List<byte[]> nalUnits = new ArrayList<byte[]>();
        nalUnits.addAll(splitCsdOrEmpty(csd0));
        nalUnits.addAll(splitCsdOrEmpty(csd1));
        return buildParts(nalUnits);
    }

    /**
     * Extracts SPS/PPS from an in-band payload, ignoring VCL NAL units.
     *
     * <p>Used when the codec emits the parameter sets inside a codec-config buffer or a display
     * packet instead of exposing them through the output format. A payload without in-band
     * configuration fails; it does not return an empty list.</p>
     *
     * @param payload in-band packet, Annex B or AVCC framed
     * @return exactly one {@code sps} and one {@code pps} part
     * @throws H264FormatException if the payload is invalid or its parameter sets are missing or
     *                             duplicated
     */
    public static List<CodecConfigPart> fromInBandPayload(byte[] payload)
            throws H264FormatException {
        return new H264ConfigExtractor().extract(payload);
    }

    /**
     * Returns the unique SPS raw NAL payload (defensive copy), or throws when missing or duplicated.
     */
    public static byte[] sps(List<CodecConfigPart> parts) throws H264FormatException {
        return findUnique(parts, "sps").getPayload();
    }

    /**
     * Returns the unique PPS raw NAL payload (defensive copy), or throws when missing or duplicated.
     */
    public static byte[] pps(List<CodecConfigPart> parts) throws H264FormatException {
        return findUnique(parts, "pps").getPayload();
    }

    /**
     * Returns {@code 00 00 00 01} + nal, four bytes longer than the input.
     *
     * <p>Config parts are stored without start codes, but platform decoders expect Annex B parameter
     * sets, so the decoder adapter wraps them with this method before building a
     * {@link DecoderFormat}.</p>
     *
     * @param nal raw NAL unit, header byte included; must not be null
     */
    public static byte[] toAnnexB(byte[] nal) {
        byte[] out = new byte[nal.length + 4];
        out[0] = 0;
        out[1] = 0;
        out[2] = 0;
        out[3] = 1;
        System.arraycopy(nal, 0, out, 4, nal.length);
        return out;
    }

    /**
     * Counts SPS and PPS units across the split units and builds the two-part result. Empty units
     * are skipped; units other than type 7 or 8 are ignored.
     */
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
