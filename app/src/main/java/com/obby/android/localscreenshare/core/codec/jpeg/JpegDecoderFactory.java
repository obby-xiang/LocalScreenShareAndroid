package com.obby.android.localscreenshare.core.codec.jpeg;

import com.obby.android.localscreenshare.core.codec.Decoder;
import com.obby.android.localscreenshare.core.codec.DecoderFactory;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.render.RenderTarget;

/**
 * Creates {@link JpegDecoder} instances.
 */
public final class JpegDecoderFactory implements DecoderFactory {

    @Override
    public Decoder create(StreamKey streamKey, StreamDescriptor descriptor,
                          RenderTarget renderTarget) {
        return new JpegDecoder(streamKey, descriptor, renderTarget);
    }
}
