package com.obby.android.localscreenshare.core.codec;

import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.model.StreamParameters;

/**
 * Creates {@link Encoder} instances for a registered codec.
 */
public interface EncoderFactory {

    Encoder create(StreamKey streamKey, StreamDescriptor descriptor,
                   StreamParameters parameters, EncoderOutput output);
}
