package com.obby.android.localscreenshare.core.codec;

import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;

/**
 * Lifecycle of a single decoder instance for one stream.
 */
public interface Decoder {

    void configure(StreamDescriptor descriptor);

    void decode(EncodedAccessUnit accessUnit) throws DecoderException;

    void close();
}
