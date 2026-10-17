package com.obby.android.localscreenshare.core.codec.jpeg;

import com.obby.android.localscreenshare.core.codec.CodecIds;
import com.obby.android.localscreenshare.core.codec.Encoder;
import com.obby.android.localscreenshare.core.codec.EncoderFactory;
import com.obby.android.localscreenshare.core.codec.EncoderOutput;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.model.StreamParameters;

/**
 * Creates {@link JpegEncoder} instances; rejects descriptors that are not JPEG.
 */
public final class JpegEncoderFactory implements EncoderFactory {

    @Override
    public Encoder create(StreamKey streamKey, StreamDescriptor descriptor,
                          StreamParameters parameters, EncoderOutput output) {
        if (descriptor == null || !CodecIds.JPEG.equals(descriptor.getCodecId())) {
            throw new IllegalArgumentException("descriptor codecId must be " + CodecIds.JPEG);
        }
        return new JpegEncoder(streamKey, descriptor, parameters, output);
    }
}
