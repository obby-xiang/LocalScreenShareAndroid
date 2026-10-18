package com.obby.android.localscreenshare.core.stream;

import com.obby.android.localscreenshare.core.codec.EncoderException;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;

/**
 * Callback for a single active stream's runtime events.
 */
public interface StreamRuntimeListener {

    void onAccessUnit(EncodedAccessUnit accessUnit);

    void onDescriptorChanged(StreamDescriptor descriptor);

    default void onEncoderFailed(StreamKey streamKey, EncoderException failure) {
    }
}
