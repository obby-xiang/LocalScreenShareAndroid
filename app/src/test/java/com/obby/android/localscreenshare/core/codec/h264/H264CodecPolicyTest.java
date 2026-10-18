package com.obby.android.localscreenshare.core.codec.h264;

import com.obby.android.localscreenshare.core.codec.OverflowAction;
import com.obby.android.localscreenshare.core.model.AccessUnitFlags;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.CodecConfigPart;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.JoinInfo;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.support.H264Fixtures;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Policy tests that pin the H.264 join-point gate to a re-validated IDR payload, not to the
 * {@code joinPoint}/{@code syncPoint} flags alone.
 */
public class H264CodecPolicyTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session-5", "stream-5");

    private static final H264CodecPolicy POLICY = new H264CodecPolicy();

    @Test
    public void joinPoint_requiresValidatedIdr() {
        byte[] idr = H264Fixtures.annexB(H264Fixtures.idrSlice());

        assertTrue(POLICY.isJoinPoint(accessUnit(idr, true, true)));
        assertFalse(POLICY.isJoinPoint(accessUnit(idr, true, false)));
        assertFalse(POLICY.isJoinPoint(accessUnit(idr, false, true)));
        assertFalse(POLICY.isJoinPoint(accessUnit(
                H264Fixtures.annexB(H264Fixtures.idrWithNonZeroFirstMb()), true, true)));
    }

    @Test
    public void joinPoint_rejectsFlagOnlyKeyFrame() {
        byte[] pSlice = H264Fixtures.annexB(H264Fixtures.pSlice());

        assertFalse(POLICY.isJoinPoint(accessUnit(pSlice, true, true)));
    }

    @Test
    public void joinPoint_rejectsNonIdrFirstVcl() {
        byte[] pSliceThenIdr = H264Fixtures.annexB(
                H264Fixtures.pSlice(), H264Fixtures.idrSlice());

        assertFalse(POLICY.isJoinPoint(accessUnit(pSliceThenIdr, true, true)));
    }

    @Test
    public void joinPoint_rejectsTypeOneISlice() {
        byte[] iSlice = H264Fixtures.annexB(H264Fixtures.iSliceTypeOne());

        assertFalse(POLICY.isJoinPoint(accessUnit(iSlice, true, true)));
    }

    @Test
    public void joinPoint_rejectsMalformedPayloadWithoutThrowing() {
        assertFalse(POLICY.isJoinPoint(accessUnit(new byte[]{1, 2, 3}, true, true)));
        assertFalse(POLICY.isJoinPoint(accessUnit(new byte[]{0, 0, 0, 1}, true, true)));
    }

    @Test
    public void overflowAction_isDropUntilJoinPoint() {
        assertEquals(OverflowAction.DROP_UNTIL_JOIN_POINT, POLICY.getOverflowAction());
    }

    @Test
    public void requiresReconfigure_usesConfigHash() {
        StreamDescriptor previous = descriptor(1, 640, 480);
        StreamDescriptor nextEpochSameConfig = descriptor(2, 640, 480);
        StreamDescriptor resized = descriptor(1, 1280, 720);

        assertEquals(previous.getConfigHash(), nextEpochSameConfig.getConfigHash());
        assertFalse(POLICY.requiresReconfigure(previous, nextEpochSameConfig));
        assertTrue(POLICY.requiresReconfigure(previous, resized));
        assertTrue(POLICY.requiresReconfigure(null, previous));
        assertTrue(POLICY.requiresReconfigure(previous, null));
    }

    private static StreamDescriptor descriptor(int configEpoch, int width, int height) {
        return StreamDescriptor.builder(STREAM_KEY, configEpoch, "h264", "video/avc",
                        BitstreamFormat.ANNEX_B)
                .profile("baseline")
                .level("3.1")
                .codedSize(width, height)
                .visibleSize(width, height)
                .configParts(Collections.<CodecConfigPart>emptyList())
                .build();
    }

    private static EncodedAccessUnit accessUnit(byte[] payload, boolean joinPoint,
                                                boolean syncPoint) {
        List<CodecConfigPart> parts = Arrays.asList(
                new CodecConfigPart("sps", H264Fixtures.sps()),
                new CodecConfigPart("pps", H264Fixtures.pps()));
        return new EncodedAccessUnit(STREAM_KEY, 1, 0L, 1_000L, 1_000L,
                AccessUnitFlags.of(syncPoint, joinPoint, false), new JoinInfo(1, parts),
                null, payload);
    }
}
