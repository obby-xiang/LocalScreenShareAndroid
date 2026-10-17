package com.obby.android.localscreenshare.core.codec.jpeg;

import com.obby.android.localscreenshare.core.codec.CodecPolicy;
import com.obby.android.localscreenshare.core.codec.OverflowAction;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;

/**
 * Policy for the JPEG codec: every frame is independently decodable, so overflow
 * keeps the latest frame and join points require both the flag and a complete payload.
 */
public final class JpegCodecPolicy implements CodecPolicy {

    @Override
    public OverflowAction getOverflowAction() {
        return OverflowAction.KEEP_LATEST_FRAME;
    }

    @Override
    public boolean isJoinPoint(EncodedAccessUnit accessUnit) {
        if (accessUnit == null) {
            return false;
        }
        return accessUnit.getFlags().isJoinPoint()
                && JpegFrameValidator.isCompleteFrame(accessUnit.getPayload());
    }

    @Override
    public boolean requiresReconfigure(StreamDescriptor previous, StreamDescriptor next) {
        if (previous == null || next == null) {
            return true;
        }
        return !previous.sameDecodeConfig(next);
    }
}
