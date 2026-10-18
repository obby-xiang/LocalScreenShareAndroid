package com.obby.android.localscreenshare.core.codec.h264;

import com.obby.android.localscreenshare.core.support.H264Fixtures;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class H264AnnexBParserTest {

    private static final int NAL_TYPE_NON_IDR = 1;
    private static final int NAL_TYPE_IDR = 5;
    private static final int NAL_TYPE_SPS = 7;
    private static final int NAL_TYPE_PPS = 8;

    private static final int SLICE_TYPE_P = 0;
    private static final int SLICE_TYPE_I = 2;
    private static final int SLICE_TYPE_B = 6;
    private static final int SLICE_TYPE_I_ALL = 7;

    private final H264AnnexBParser parser = new H264AnnexBParser();

    @Test
    public void parse_acceptsThreeAndFourByteStartCodes() {
        byte[] payload = concatenate(
                startCode(4), H264Fixtures.sps(),
                startCode(3), H264Fixtures.pps(),
                startCode(4), H264Fixtures.idrSlice());

        List<H264NalUnit> units = parser.parse(payload);

        assertEquals(3, units.size());
        assertEquals(NAL_TYPE_SPS, units.get(0).getType());
        assertEquals(3, units.get(0).getRefIdc());
        assertFalse(units.get(0).isVcl());
        assertArrayEquals(H264Fixtures.sps(), units.get(0).getBytes());
        assertEquals(NAL_TYPE_PPS, units.get(1).getType());
        assertArrayEquals(H264Fixtures.pps(), units.get(1).getBytes());
        assertEquals(NAL_TYPE_IDR, units.get(2).getType());
        assertTrue(units.get(2).isVcl());
        assertArrayEquals(H264Fixtures.idrSlice(), units.get(2).getBytes());
    }

    @Test
    public void parse_splitsNalUnitsAndSkipsTrailingZeros() {
        byte[] payload = concatenate(
                H264Fixtures.annexB(H264Fixtures.sps(), H264Fixtures.pps()),
                new byte[]{0, 0, 0, 0});

        List<H264NalUnit> units = parser.parse(payload);

        assertEquals(2, units.size());
        assertEquals(NAL_TYPE_SPS, units.get(0).getType());
        assertEquals(3, units.get(0).getRefIdc());
        assertArrayEquals(H264Fixtures.sps(), units.get(0).getBytes());
        assertEquals(NAL_TYPE_PPS, units.get(1).getType());
        assertArrayEquals(H264Fixtures.pps(), units.get(1).getBytes());
    }

    @Test
    public void parse_rejectsEmptyAndTruncatedInput() {
        assertThrows(H264FormatException.class, () -> parser.parse(null));
        assertThrows(H264FormatException.class, () -> parser.parse(new byte[0]));
        assertThrows(H264FormatException.class, () -> parser.parse(new byte[]{0, 0, 0, 1}));
        assertThrows(H264FormatException.class, () -> parser.parse(new byte[]{0, 0, 1}));
        assertThrows(H264FormatException.class, () -> parser.parse(new byte[]{0, 0, 0, 0}));
        assertThrows(H264FormatException.class,
                () -> parser.parse(new byte[]{0x65, (byte) 0x80, 0x00}));
    }

    @Test
    public void analyze_detectsSpsPpsAndIdr() {
        H264AccessUnitInfo info = parser.analyze(H264Fixtures.inBandConfigAndIdr());

        assertTrue(info.hasSps());
        assertTrue(info.hasPps());
        assertTrue(info.hasIdr());
        assertFalse(info.hasBSlice());
        assertTrue(info.isJoinable());
        assertEquals(1, info.getSpsCount());
        assertEquals(1, info.getPpsCount());
        assertEquals(1, info.getVclCount());

        H264SliceHeader header = info.getFirstSliceHeader();
        assertNotNull(header);
        assertEquals(0, header.getFirstMbInSlice());
        assertEquals(SLICE_TYPE_I, header.getSliceType());
        assertEquals(0, header.getPicParameterSetId());
        assertTrue(parser.isIdrAccessUnit(H264Fixtures.inBandConfigAndIdr()));
    }

    @Test
    public void analyze_idrRequiresNalTypeFive() {
        byte[] payload = H264Fixtures.annexB(
                H264Fixtures.sps(), H264Fixtures.pps(), H264Fixtures.iSliceTypeOne());

        H264AccessUnitInfo info = parser.analyze(payload);

        assertEquals(1, info.getVclCount());
        assertEquals(NAL_TYPE_NON_IDR, parser.parse(payload).get(2).getType());
        assertEquals(SLICE_TYPE_I, info.getFirstSliceHeader().getSliceType());
        assertFalse(info.hasIdr());
        assertFalse(info.isJoinable());
        assertFalse(parser.isIdrAccessUnit(payload));
    }

    @Test
    public void analyze_rejectsTypeOneISliceAsJoinable() {
        byte[] payload = H264Fixtures.annexB(
                H264Fixtures.sps(), H264Fixtures.pps(), H264Fixtures.iSliceTypeOne());

        H264AccessUnitInfo info = parser.analyze(payload);

        assertFalse(info.hasBSlice());
        assertFalse(info.isJoinable());
        assertFalse(parser.isIdrAccessUnit(payload));
    }

    @Test
    public void analyze_rejectsIdrWithNonZeroFirstMb() {
        byte[] payload = H264Fixtures.annexB(
                H264Fixtures.sps(), H264Fixtures.pps(), H264Fixtures.idrWithNonZeroFirstMb());

        H264AccessUnitInfo info = parser.analyze(payload);

        assertTrue(info.hasIdr());
        assertEquals(NAL_TYPE_IDR, parser.parse(payload).get(2).getType());
        assertEquals(1, info.getFirstSliceHeader().getFirstMbInSlice());
        assertFalse(info.hasBSlice());
        assertFalse(info.isJoinable());
        assertFalse(parser.isIdrAccessUnit(payload));
    }

    @Test
    public void analyze_rejectsIdrWithBSliceType() {
        byte[] payload = H264Fixtures.annexB(
                H264Fixtures.sps(), H264Fixtures.pps(), H264Fixtures.idrWithBSliceType());

        H264AccessUnitInfo info = parser.analyze(payload);

        assertEquals(NAL_TYPE_IDR, parser.parse(payload).get(2).getType());
        assertEquals(SLICE_TYPE_B, info.getFirstSliceHeader().getSliceType());
        assertTrue(info.hasBSlice());
        assertFalse(info.isJoinable());
        assertFalse(parser.isIdrAccessUnit(payload));
    }

    @Test
    public void analyze_rejectsIdrWithPSliceType() {
        byte[] payload = H264Fixtures.annexB(
                H264Fixtures.sps(), H264Fixtures.pps(), H264Fixtures.idrWithPSliceType());

        H264AccessUnitInfo info = parser.analyze(payload);

        assertEquals(NAL_TYPE_IDR, parser.parse(payload).get(2).getType());
        assertEquals(SLICE_TYPE_P, info.getFirstSliceHeader().getSliceType());
        assertFalse(info.hasBSlice());
        assertFalse(info.isJoinable());
        assertFalse(parser.isIdrAccessUnit(payload));
    }

    @Test
    public void analyze_rejectsIdrWithZeroNalRefIdc() {
        byte[] payload = H264Fixtures.annexB(
                H264Fixtures.sps(), H264Fixtures.pps(), H264Fixtures.idrWithZeroNalRefIdc());

        H264AccessUnitInfo info = parser.analyze(payload);

        assertEquals(NAL_TYPE_IDR, parser.parse(payload).get(2).getType());
        assertEquals(0, parser.parse(payload).get(2).getRefIdc());
        assertFalse(info.hasIdr());
        assertFalse(info.isJoinable());
        assertFalse(parser.isIdrAccessUnit(payload));
    }

    @Test
    public void analyze_rejectsAuWhereLaterVclIsBSlice() {
        byte[] payload = H264Fixtures.annexB(H264Fixtures.sps(), H264Fixtures.pps(),
                H264Fixtures.idrSlice(), H264Fixtures.pSlice(), H264Fixtures.bSlice());

        H264AccessUnitInfo info = parser.analyze(payload);

        assertEquals(3, info.getVclCount());
        assertTrue(info.hasIdr());
        assertTrue(info.hasBSlice());
        assertEquals(SLICE_TYPE_I, info.getFirstSliceHeader().getSliceType());
        assertFalse(info.isJoinable());
        assertFalse(parser.isIdrAccessUnit(payload));
    }

    @Test
    public void analyze_rejectsAuWhoseFirstVclIsNotIdr() {
        byte[] payload = H264Fixtures.annexB(
                H264Fixtures.pSlice(), H264Fixtures.idrSlice());

        H264AccessUnitInfo info = parser.analyze(payload);

        assertEquals(2, info.getVclCount());
        assertEquals(SLICE_TYPE_P, info.getFirstSliceHeader().getSliceType());
        assertFalse(info.isJoinable());
        assertFalse(parser.isIdrAccessUnit(payload));
    }

    @Test
    public void analyze_rejectsFirstIdrWithZeroRefIdcEvenIfLaterIdrIsValid() {
        byte[] payload = H264Fixtures.annexB(
                H264Fixtures.idrWithZeroNalRefIdc(), H264Fixtures.idrSlice());

        H264AccessUnitInfo info = parser.analyze(payload);

        assertEquals(2, info.getVclCount());
        assertEquals(0, parser.parse(payload).get(0).getRefIdc());
        assertFalse(info.isJoinable());
        assertFalse(parser.isIdrAccessUnit(payload));
    }

    @Test
    public void analyze_acceptsMultiSliceIdr() {
        byte[] payload = H264Fixtures.annexB(
                H264Fixtures.idrSlice(), H264Fixtures.idrWithNonZeroFirstMb());

        H264AccessUnitInfo info = parser.analyze(payload);

        assertEquals(2, info.getVclCount());
        assertEquals(0, info.getFirstSliceHeader().getFirstMbInSlice());
        assertTrue(info.isJoinable());
        assertTrue(parser.isIdrAccessUnit(payload));
    }

    @Test
    public void analyze_reportsHasVcl() {
        H264AccessUnitInfo withVcl = parser.analyze(H264Fixtures.inBandConfigAndIdr());
        H264AccessUnitInfo configOnly = parser.analyze(
                H264Fixtures.annexB(H264Fixtures.sps(), H264Fixtures.pps()));

        assertTrue(withVcl.hasVcl());
        assertFalse(configOnly.hasVcl());
    }

    @Test
    public void analyze_removesEmulationPreventionBytesForSliceHeader() {
        byte[] rawIdr = H264Fixtures.idrWithEmulationPrevention();
        assertTrue(containsSequence(rawIdr, new byte[]{0, 0, 3}));
        byte[] payload = H264Fixtures.annexB(
                H264Fixtures.sps(), H264Fixtures.pps(), rawIdr);

        List<H264NalUnit> units = parser.parse(payload);
        assertArrayEquals(rawIdr, units.get(2).getBytes());

        H264AccessUnitInfo info = parser.analyze(payload);
        assertTrue(info.hasIdr());
        assertFalse(info.hasBSlice());
        assertTrue(info.isJoinable());

        H264SliceHeader header = info.getFirstSliceHeader();
        assertNotNull(header);
        assertEquals(0, header.getFirstMbInSlice());
        assertEquals(SLICE_TYPE_I_ALL, header.getSliceType());
        assertEquals(H264Fixtures.IDR_PPS_ID_WITH_EMULATION_PREVENTION,
                header.getPicParameterSetId());
    }

    @Test
    public void analyze_configOnlyPayloadIsNotJoinable() {
        byte[] payload = H264Fixtures.annexB(H264Fixtures.sps(), H264Fixtures.pps());

        H264AccessUnitInfo info = parser.analyze(payload);

        assertTrue(info.hasSps());
        assertTrue(info.hasPps());
        assertEquals(0, info.getVclCount());
        assertFalse(info.hasIdr());
        assertFalse(info.hasBSlice());
        assertFalse(info.isJoinable());
        assertNull(info.getFirstSliceHeader());
        assertFalse(parser.isIdrAccessUnit(payload));
    }

    @Test
    public void analyze_malformedPayloadDoesNotThrowForPolicy() {
        assertThrows(H264FormatException.class, () -> parser.analyze(null));
        assertThrows(H264FormatException.class, () -> parser.analyze(new byte[0]));
        assertThrows(H264FormatException.class,
                () -> parser.analyze(new byte[]{0, 0, 0, 1}));

        assertFalse(parser.isIdrAccessUnit(null));
        assertFalse(parser.isIdrAccessUnit(new byte[0]));
        assertFalse(parser.isIdrAccessUnit(new byte[]{0, 0, 0, 1}));
        assertFalse(parser.isIdrAccessUnit(
                concatenate(startCode(4), new byte[]{0x65})));
    }

    @Test
    public void looksLengthPrefixed_detectsAvccConfig() {
        assertTrue(parser.looksLengthPrefixed(
                H264Fixtures.avcc(H264Fixtures.sps(), H264Fixtures.pps())));
        assertFalse(parser.looksLengthPrefixed(
                H264Fixtures.annexB(H264Fixtures.sps(), H264Fixtures.pps())));
        assertFalse(parser.looksLengthPrefixed(null));
        assertFalse(parser.looksLengthPrefixed(new byte[0]));
        assertFalse(parser.looksLengthPrefixed(new byte[]{0, 0, 0, 8}));
    }

    @Test
    public void stripSpsPps_removesConfigButKeepsVcl() {
        byte[] stripped = parser.stripSpsPps(H264Fixtures.inBandConfigPlusIdrPlusP());

        assertArrayEquals(
                H264Fixtures.annexB(H264Fixtures.idrSlice(), H264Fixtures.pSlice()),
                stripped);
        List<H264NalUnit> units = parser.parse(stripped);
        assertEquals(2, units.size());
        assertEquals(NAL_TYPE_IDR, units.get(0).getType());
        assertEquals(NAL_TYPE_NON_IDR, units.get(1).getType());
    }

    @Test
    public void stripSpsPps_keepsFirstIdrInConfigPlusVclPacket() {
        byte[] stripped = parser.stripSpsPps(H264Fixtures.spsPpsIdrInOneAu());

        assertArrayEquals(H264Fixtures.annexB(H264Fixtures.idrSlice()), stripped);
        List<H264NalUnit> units = parser.parse(stripped);
        assertEquals(1, units.size());
        assertEquals(NAL_TYPE_IDR, units.get(0).getType());
    }

    @Test
    public void parserMethods_arePublicStatic() throws Exception {
        assertPublicStatic("parse", byte[].class);
        assertPublicStatic("analyze", byte[].class);
        assertPublicStatic("isIdrAccessUnit", byte[].class);
        assertPublicStatic("stripSpsPps", byte[].class);
        assertPublicStatic("looksLengthPrefixed", byte[].class);
    }

    private static void assertPublicStatic(String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        java.lang.reflect.Method method =
                H264AnnexBParser.class.getDeclaredMethod(name, parameterTypes);
        assertTrue(name + " must be public",
                java.lang.reflect.Modifier.isPublic(method.getModifiers()));
        assertTrue(name + " must be static",
                java.lang.reflect.Modifier.isStatic(method.getModifiers()));
    }

    private static byte[] startCode(int length) {
        return length == 3 ? new byte[]{0, 0, 1} : new byte[]{0, 0, 0, 1};
    }

    private static byte[] concatenate(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }

    private static boolean containsSequence(byte[] data, byte[] sequence) {
        for (int i = 0; i + sequence.length <= data.length; i++) {
            boolean match = true;
            for (int j = 0; j < sequence.length; j++) {
                if (data[i + j] != sequence[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }
}
