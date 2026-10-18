package com.obby.android.localscreenshare.core.codec.h264;

import com.obby.android.localscreenshare.core.model.CodecConfigPart;
import com.obby.android.localscreenshare.core.support.H264Fixtures;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class H264ConfigExtractorTest {

    private final H264ConfigExtractor extractor = new H264ConfigExtractor();

    @Test
    public void extract_fromAnnexBCsdReturnsSpsAndPps() {
        byte[] sps = H264Fixtures.sps();
        byte[] pps = H264Fixtures.pps();

        List<CodecConfigPart> parts = extractor.extract(H264Fixtures.annexB(sps, pps));

        assertEquals(2, parts.size());
        assertArrayEquals(sps, parts.get(0).getPayload());
        assertArrayEquals(pps, parts.get(1).getPayload());
    }

    @Test
    public void extract_fromAvccCsdReturnsSpsAndPps() {
        byte[] sps = H264Fixtures.sps();
        byte[] pps = H264Fixtures.pps();

        List<CodecConfigPart> parts = extractor.extract(H264Fixtures.avcc(sps, pps));

        assertEquals(2, parts.size());
        assertArrayEquals(sps, parts.get(0).getPayload());
        assertArrayEquals(pps, parts.get(1).getPayload());
    }

    @Test
    public void extract_fromInBandPayloadReturnsSpsAndPps() {
        byte[] sps = H264Fixtures.sps();
        byte[] pps = H264Fixtures.pps();

        List<CodecConfigPart> parts = extractor.extract(H264Fixtures.inBandConfigAndIdr());

        assertEquals(2, parts.size());
        assertArrayEquals(sps, parts.get(0).getPayload());
        assertArrayEquals(pps, parts.get(1).getPayload());
    }

    @Test
    public void extract_rejectsMissingSps() {
        byte[] payload = H264Fixtures.annexB(H264Fixtures.pps());

        assertThrows(H264FormatException.class, () -> extractor.extract(payload));
    }

    @Test
    public void extract_rejectsMissingPps() {
        byte[] payload = H264Fixtures.annexB(H264Fixtures.sps());

        assertThrows(H264FormatException.class, () -> extractor.extract(payload));
    }

    @Test
    public void extract_rejectsMultipleSpsInThisBatch() {
        byte[] payload = H264Fixtures.annexB(
                H264Fixtures.sps(), H264Fixtures.sps(), H264Fixtures.pps());

        assertThrows(H264FormatException.class, () -> extractor.extract(payload));
    }

    @Test
    public void configPartIdsAreSpsAndPps() {
        List<CodecConfigPart> parts = extractor.extract(
                H264Fixtures.annexB(H264Fixtures.sps(), H264Fixtures.pps()));

        assertEquals("sps", parts.get(0).getId());
        assertEquals("pps", parts.get(1).getId());
    }

    @Test
    public void configPartPayloadsAreDefensivelyCopied() {
        byte[] sps = H264Fixtures.sps();
        byte[] pps = H264Fixtures.pps();
        byte[] input = H264Fixtures.annexB(sps, pps);

        List<CodecConfigPart> parts = extractor.extract(input);
        Arrays.fill(input, (byte) 0x7F);

        assertArrayEquals(sps, parts.get(0).getPayload());
        assertArrayEquals(pps, parts.get(1).getPayload());

        byte[] returnedPayload = parts.get(0).getPayload();
        Arrays.fill(returnedPayload, (byte) 0x00);
        assertArrayEquals(sps, parts.get(0).getPayload());
    }

    @Test
    public void toAnnexB_prependsFourByteStartCode() {
        byte[] nal = H264Fixtures.sps();

        byte[] annexB = H264ConfigExtractor.toAnnexB(nal);

        assertEquals(nal.length + 4, annexB.length);
        assertArrayEquals(new byte[]{0, 0, 0, 1}, Arrays.copyOfRange(annexB, 0, 4));
        assertArrayEquals(nal, Arrays.copyOfRange(annexB, 4, annexB.length));
    }
}
