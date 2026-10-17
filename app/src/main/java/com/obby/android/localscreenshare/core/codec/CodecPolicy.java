package com.obby.android.localscreenshare.core.codec;

import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;

/**
 * Per-codec policy that guides a stream's overflow and join-point handling.
 */
public interface CodecPolicy {

    OverflowAction getOverflowAction();

    boolean isJoinPoint(EncodedAccessUnit accessUnit);

    boolean requiresReconfigure(StreamDescriptor previous, StreamDescriptor next);
}
