package com.obby.android.localscreenshare.core.codec.h264;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pure-Java H.264 Annex B byte-stream parser.
 *
 * <p>This is the only place in the module that knows H.264 framing and NAL-level classification.
 * It allocates no Android objects and performs no I/O: callers hand it byte arrays that came from a
 * codec output buffer or from a stream descriptor's config parts. Nothing here decodes pixels or
 * interprets picture payloads beyond the three slice-header fields used for policy decisions.</p>
 *
 * <p><b>Framing.</b> A NAL unit starts after a three-byte ({@code 00 00 01}) or four-byte
 * ({@code 00 00 00 01}) start code and ends where the next start code begins, or at the end of the
 * input. Emitted units never include a start code, and emulation-prevention bytes are preserved
 * (they are removed later, per unit, by {@link H264BitReader}). Trailing zero bytes are trimmed
 * only for the final unit: zero padding that precedes an interior start code stays at the tail of
 * the preceding unit, so padded streams do not round-trip byte for byte.</p>
 *
 * <p><b>Joinability.</b> {@link #isIdrAccessUnit(byte[])} is the authoritative answer used by both
 * the H.264 encoder and {@link H264CodecPolicy}; an encoder's keyframe flag is never trusted on its
 * own. {@link H264AccessUnitInfo#isJoinable()} holds the exact predicate.</p>
 *
 * <p>All methods are static and stateless, so the class is thread-safe. The public constructor
 * exists only for source compatibility.</p>
 */
public final class H264AnnexBParser {

    private static final int NAL_TYPE_IDR = 5;
    private static final int NAL_TYPE_SPS = 7;
    private static final int NAL_TYPE_PPS = 8;

    public H264AnnexBParser() {
    }

    /**
     * Splits an Annex B byte stream into NAL units.
     *
     * <p>Three-byte and four-byte start codes are both accepted and may be mixed within one buffer.
     * Parsing starts at the first start code, so bytes before it are ignored. A start code that is
     * followed immediately by another start code, or by the end of input, is rejected instead of
     * producing an empty unit.</p>
     *
     * @param annexB Annex B framed bytes; must not be null or empty
     * @return the units in stream order, each holding its NAL header byte plus raw payload
     * @throws H264FormatException if the input is null or empty, contains no start code, or yields
     *                             an empty NAL unit
     */
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

    /**
     * Classifies every NAL unit of one access unit (or codec-config packet).
     *
     * <p>Counts SPS and PPS units, records whether any VCL unit is a reference IDR, and parses the
     * slice header of every VCL unit so a B slice is found wherever it occurs, not only in the first
     * unit. The first-VCL fields in the result are the ones
     * {@link H264AccessUnitInfo#isJoinable()} evaluates: the IDR rule is applied to the first VCL
     * unit only, while the B-slice rule covers all of them.</p>
     *
     * <p>A malformed slice header fails the whole analysis. Callers that must treat malformed input
     * as "not joinable" rather than as an error use {@link #isIdrAccessUnit(byte[])}.</p>
     *
     * @param annexB one access unit, Annex B framed
     * @return the aggregate classification; never null
     * @throws H264FormatException if the framing is invalid or a VCL slice header cannot be parsed
     */
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

    /**
     * Policy-safe joinability check that never throws.
     *
     * <p>Equivalent to {@code analyze(payload).isJoinable()} with every
     * {@link H264FormatException} mapped to {@code false}, so the encoder emit path and
     * {@link H264CodecPolicy} can treat a malformed, truncated or null payload as "not a join
     * point" instead of propagating a parse failure.</p>
     *
     * @param payload candidate display payload; {@code null} counts as not joinable
     * @return whether the payload is a validated IDR access unit without any B slice
     */
    public static boolean isIdrAccessUnit(byte[] payload) {
        try {
            return H264AnnexBParser.analyze(payload).isJoinable();
        } catch (H264FormatException e) {
            return false;
        }
    }

    /**
     * True when the buffer is consumed exactly by a sequence of 4-byte big-endian length prefixes,
     * i.e. AVCC framing.
     *
     * <p>The walk is strict: every prefix must be non-negative, fit in the remaining bytes, and the
     * buffer must end exactly on a unit boundary. Zero-length entries are tolerated. The check reads
     * lengths only, never NAL headers, so it is a framing heuristic: an Annex B stream or a raw NAL
     * unit normally fails the walk, but a buffer whose bytes happen to form a consistent prefix
     * sequence is read as AVCC.</p>
     *
     * @param payload bytes to classify; {@code null} returns {@code false}
     * @return whether the whole buffer parses as length-prefixed units
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

    /**
     * Removes SPS/PPS NAL units, keeps VCL, emits canonical 4-byte start codes.
     *
     * <p>Only NAL types 7 and 8 are dropped; SEI, AUD, VCL and other units survive, so stripping the
     * configuration from a packet that also carries the first IDR does not lose that IDR. Surviving
     * units keep their order and are each preceded by a four-byte start code. A buffer that holds
     * only configuration yields an empty array, which does not mean the original packet had no VCL:
     * callers that need that distinction must inspect {@link H264AccessUnitInfo#hasVcl()} on the
     * original payload instead of measuring the returned array.</p>
     *
     * @param annexB packet to strip; must be Annex B framed
     * @return surviving units re-framed with four-byte start codes, possibly empty
     * @throws H264FormatException if the input is null, empty, or not Annex B framed
     */
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

    /**
     * Returns 4 for a four-byte start code at {@code scStart}, 3 for a three-byte one.
     */
    private static int startCodeLength(byte[] data, int scStart) {
        if (scStart + 4 <= data.length
                && data[scStart] == 0 && data[scStart + 1] == 0
                && data[scStart + 2] == 0 && data[scStart + 3] == 1) {
            return 4;
        }
        return 3;
    }
}
