package com.obby.android.localscreenshare.core.net;

import com.obby.android.localscreenshare.core.codec.h264.H264CodecPolicy;
import com.obby.android.localscreenshare.core.model.AccessUnitFlags;
import com.obby.android.localscreenshare.core.model.CodecConfigPart;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.JoinInfo;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.support.H264Fixtures;
import com.obby.android.localscreenshare.transport.ScreenStreamProtoMapper;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests that keep the dedicated channel equivalent to the control-plane carrier.
 *
 * <p>An access unit must mean the same thing on both paths: the join decision of the codec policy
 * reads the flags, the join configuration and the payload, so a frame that lost one of them would
 * silently turn a join point into an ordinary unit - and the viewer would wait for a key frame that
 * never comes.</p>
 */
public class MediaFrameContractTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session-1", "stream-1");

    private final H264CodecPolicy policy = new H264CodecPolicy();

    @Test
    public void joinPointOfAnIdr_isTheSameOnBothPaths() throws Exception {
        EncodedAccessUnit unit = idrUnit();
        assertTrue(policy.isJoinPoint(unit));

        EncodedAccessUnit throughFrame = roundTripThroughFrame(unit);
        EncodedAccessUnit throughProto = roundTripThroughProto(unit);

        assertEquals(policy.isJoinPoint(unit), policy.isJoinPoint(throughFrame));
        assertEquals(policy.isJoinPoint(unit), policy.isJoinPoint(throughProto));
        assertTrue(policy.isJoinPoint(throughFrame));
    }

    @Test
    public void ordinaryUnit_staysAnOrdinaryUnitOnBothPaths() throws Exception {
        EncodedAccessUnit unit = new EncodedAccessUnit(STREAM_KEY, 2, 9L, 297_000L,
                EncodedAccessUnit.DTS_UNSET, AccessUnitFlags.none(), null, null,
                H264Fixtures.annexB(H264Fixtures.pSlice()));

        EncodedAccessUnit throughFrame = roundTripThroughFrame(unit);

        assertEquals(policy.isJoinPoint(unit), policy.isJoinPoint(throughFrame));
        assertEquals(2, throughFrame.getConfigEpoch());
        assertEquals(unit.getPtsUs(), throughFrame.getPtsUs());
    }

    @Test
    public void framePathNeverCarriesADecodeTimestamp() throws Exception {
        EncodedAccessUnit unit = idrUnit();
        assertTrue(!unit.hasDtsUs());

        MediaFrame frame = frameOf(unit);

        assertTrue(!frame.hasDtsUs());
        assertEquals(MediaFrame.DTS_UNSET, frame.getDtsUs());
    }

    private EncodedAccessUnit idrUnit() {
        List<CodecConfigPart> parts = Collections.singletonList(
                new CodecConfigPart("csd-0", H264Fixtures.sps()));
        return new EncodedAccessUnit(STREAM_KEY, 1, 0L, 0L, EncodedAccessUnit.DTS_UNSET,
                AccessUnitFlags.of(true, true, false), new JoinInfo(1, parts), null,
                H264Fixtures.annexB(H264Fixtures.sps(), H264Fixtures.pps(),
                        H264Fixtures.idrSlice()));
    }

    private static EncodedAccessUnit roundTripThroughProto(EncodedAccessUnit unit) {
        return ScreenStreamProtoMapper.toAccessUnit(STREAM_KEY,
                ScreenStreamProtoMapper.toProtoAccessUnit(unit));
    }

    private static EncodedAccessUnit roundTripThroughFrame(EncodedAccessUnit unit) throws Exception {
        MediaFrame frame = frameOf(unit);
        MediaFrame decoded = MediaFrameCodec.decode(MediaFrameCodec.encode(frame));
        AccessUnitFlags flags = AccessUnitFlags.of(decoded.isSyncPoint(), decoded.isJoinPoint(),
                decoded.isConfig());
        JoinInfo joinInfo = (decoded.isJoinPoint() || !decoded.getJoinParts().isEmpty())
                ? new JoinInfo(decoded.getConfigEpoch(), decoded.getJoinParts()) : null;
        return new EncodedAccessUnit(STREAM_KEY, decoded.getConfigEpoch(), unit.getSequence(),
                decoded.getPtsUs(), decoded.hasDtsUs() ? decoded.getDtsUs() : decoded.getPtsUs(),
                flags, joinInfo, null, decoded.getPayload());
    }

    private static MediaFrame frameOf(EncodedAccessUnit unit) {
        return MediaFrame.accessUnit(unit.getPtsUs(),
                unit.hasDtsUs() ? unit.getDtsUs() : MediaFrame.DTS_UNSET, unit.getConfigEpoch(),
                unit.getFlags().isConfig(), unit.getFlags().isSyncPoint(),
                unit.getFlags().isJoinPoint(),
                unit.getJoinInfo() == null ? null : unit.getJoinInfo().getRequiredConfigParts(),
                unit.getPayload());
    }
}
