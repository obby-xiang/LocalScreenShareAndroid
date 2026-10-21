package com.obby.android.localscreenshare.core.codec.h264;

import com.obby.android.localscreenshare.core.codec.Decoder;
import com.obby.android.localscreenshare.core.codec.DecoderFactory;
import com.obby.android.localscreenshare.core.codec.KeyFrameRequester;
import com.obby.android.localscreenshare.core.codec.mediacodec.MediaCodecBridgeFactory;
import com.obby.android.localscreenshare.core.metrics.PipelineMetrics;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.render.RenderTarget;
import com.obby.android.localscreenshare.core.util.NanoClock;

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

    private final KeyFrameRequester keyFrameRequester;

    private final PipelineMetrics metrics;

    private final NanoClock clock;

    /**
     * Creates a factory whose decoders do not report key-frame needs.
     */
    public H264DecoderFactory(MediaCodecBridgeFactory bridgeFactory) {
        this(bridgeFactory, KeyFrameRequester.NONE);
    }

    /**
     * Creates a factory whose decoders report dropped frames to {@code keyFrameRequester}.
     */
    public H264DecoderFactory(MediaCodecBridgeFactory bridgeFactory,
                              KeyFrameRequester keyFrameRequester) {
        this(bridgeFactory, keyFrameRequester, PipelineMetrics.NONE, NanoClock.MONOTONIC);
    }

    /**
     * Creates a factory whose decoders report decode and presentation metrics.
     *
     * <p>The scope and clock travel with the factory because decoders are created by the client
     * session through the codec registration; one scope therefore describes the single active
     * decoder of the receiving pipeline and survives a reconfiguration.</p>
     *
     * @param bridgeFactory     creates platform decoder bridges; required
     * @param keyFrameRequester notified when a decoder had to shed a frame; required
     * @param metrics           metric scope of the receiving pipeline; required
     * @param clock             clock used by the decode and presentation probes; required
     * @throws IllegalArgumentException if any argument is null
     */
    public H264DecoderFactory(MediaCodecBridgeFactory bridgeFactory,
                              KeyFrameRequester keyFrameRequester, PipelineMetrics metrics,
                              NanoClock clock) {
        if (bridgeFactory == null) {
            throw new IllegalArgumentException("bridgeFactory must not be null");
        }
        if (keyFrameRequester == null) {
            throw new IllegalArgumentException("keyFrameRequester must not be null");
        }
        if (metrics == null) {
            throw new IllegalArgumentException("metrics must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.bridgeFactory = bridgeFactory;
        this.keyFrameRequester = keyFrameRequester;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public Decoder create(StreamKey streamKey, StreamDescriptor descriptor,
                          RenderTarget renderTarget) {
        return new H264Decoder(streamKey, descriptor, renderTarget, bridgeFactory,
                keyFrameRequester, metrics, clock);
    }
}
