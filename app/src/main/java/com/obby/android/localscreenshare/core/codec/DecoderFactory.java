package com.obby.android.localscreenshare.core.codec;

import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.render.RenderTarget;

/**
 * Creates {@link Decoder} instances for a registered codec, receiving the
 * {@link RenderTarget} the decoder should push frames into.
 */
public interface DecoderFactory {

    Decoder create(StreamKey streamKey, StreamDescriptor descriptor, RenderTarget renderTarget);
}
