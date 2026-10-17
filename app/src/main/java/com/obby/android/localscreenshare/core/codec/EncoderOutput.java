package com.obby.android.localscreenshare.core.codec;

import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;

/**
 * Sink for encoded access units produced by an {@link Encoder}.
 */
@FunctionalInterface
public interface EncoderOutput {

    void onAccessUnit(EncodedAccessUnit accessUnit);
}
