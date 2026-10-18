package com.obby.android.localscreenshare.core.codec.h264;

import com.obby.android.localscreenshare.core.codec.CodecPolicy;
import com.obby.android.localscreenshare.core.codec.OverflowAction;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;

/**
 * Codec policy for the H.264 (AVC) codec.
 *
 * <p>A receiver that overflows a live H.264 stream cannot resume at an arbitrary frame, so the
 * overflow action is {@link OverflowAction#DROP_UNTIL_JOIN_POINT}: queued access units are dropped
 * until the next random-access point (a validated IDR access unit) arrives.</p>
 */
public final class H264CodecPolicy implements CodecPolicy {

    @Override
    public OverflowAction getOverflowAction() {
        return OverflowAction.DROP_UNTIL_JOIN_POINT;
    }

    /**
     * An H.264 join point is a decoded IDR access unit, not merely an access unit whose flags are
     * set. The flags are trusted only after re-validating the payload with
     * {@link H264AnnexBParser#isIdrAccessUnit(byte[])}, which enforces the first-VCL-NAL IDR rule
     * and rejects malformed input without throwing.
     */
    @Override
    public boolean isJoinPoint(EncodedAccessUnit accessUnit) {
        if (accessUnit == null) {
            return false;
        }
        if (!accessUnit.getFlags().isJoinPoint() || !accessUnit.getFlags().isSyncPoint()) {
            return false;
        }
        if (accessUnit.getJoinInfo() == null) {
            return false;
        }
        return H264AnnexBParser.isIdrAccessUnit(accessUnit.getPayload());
    }

    /**
     * Reconfiguration is needed when the decode configuration actually changed. The
     * {@code configHash} intentionally excludes the config epoch, so an epoch bump with an
     * identical configuration does not require reconfiguration.
     */
    @Override
    public boolean requiresReconfigure(StreamDescriptor previous, StreamDescriptor next) {
        if (previous == null || next == null) {
            return true;
        }
        return !previous.sameDecodeConfig(next);
    }
}
