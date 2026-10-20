package com.obby.android.localscreenshare.core.codec.h264;

import com.obby.android.localscreenshare.core.codec.Decoder;
import com.obby.android.localscreenshare.core.codec.DecoderFactory;
import com.obby.android.localscreenshare.core.codec.mediacodec.MediaCodecBridgeFactory;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.render.RenderTarget;

/**
 * Creates {@link H264Decoder} instances backed by a {@link MediaCodecBridgeFactory}.
 *
 * <p>All H.264 decoders share one bridge factory, which supplies a fresh decoder bridge per
 * instance. Decoder construction is cheap and performs no bridge I/O; the bridge is created,
 * configured and started later inside {@link H264Decoder#configure(StreamDescriptor)}, so a
 * factory-created decoder is safe to discard without releasing any bridge resource.</p>
 */
public final class H264DecoderFactory implements DecoderFactory {

    private final MediaCodecBridgeFactory bridgeFactory;

    public H264DecoderFactory(MediaCodecBridgeFactory bridgeFactory) {
        if (bridgeFactory == null) {
            throw new IllegalArgumentException("bridgeFactory must not be null");
        }
        this.bridgeFactory = bridgeFactory;
    }

    @Override
    public Decoder create(StreamKey streamKey, StreamDescriptor descriptor,
                          RenderTarget renderTarget) {
        return new H264Decoder(streamKey, descriptor, renderTarget, bridgeFactory);
    }
}
