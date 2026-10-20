package com.obby.android.localscreenshare.core.codec.h264;

import com.obby.android.localscreenshare.core.codec.CodecIds;
import com.obby.android.localscreenshare.core.codec.Encoder;
import com.obby.android.localscreenshare.core.codec.EncoderFactory;
import com.obby.android.localscreenshare.core.codec.EncoderOutput;
import com.obby.android.localscreenshare.core.codec.mediacodec.MediaCodecBridgeFactory;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.model.StreamParameters;

/**
 * Creates {@link H264Encoder} instances backed by a {@link MediaCodecBridgeFactory}.
 *
 * <p>All H.264 encoders share one bridge factory, which supplies a fresh encoder bridge per
 * instance. The bridge is configured and its input surface is created during construction (inside
 * {@link H264Encoder}'s constructor); the returned encoder is not yet started.</p>
 */
public final class H264EncoderFactory implements EncoderFactory {

    private final MediaCodecBridgeFactory bridgeFactory;

    public H264EncoderFactory(MediaCodecBridgeFactory bridgeFactory) {
        if (bridgeFactory == null) {
            throw new IllegalArgumentException("bridgeFactory must not be null");
        }
        this.bridgeFactory = bridgeFactory;
    }

    @Override
    public Encoder create(StreamKey streamKey, StreamDescriptor descriptor,
                          StreamParameters parameters, EncoderOutput output) {
        if (descriptor == null || !CodecIds.H264.equals(descriptor.getCodecId())) {
            throw new IllegalArgumentException("descriptor codecId must be " + CodecIds.H264);
        }
        return new H264Encoder(streamKey, descriptor, parameters, output, bridgeFactory);
    }
}
