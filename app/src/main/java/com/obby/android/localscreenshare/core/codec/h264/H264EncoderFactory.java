package com.obby.android.localscreenshare.core.codec.h264;

import com.obby.android.localscreenshare.core.codec.CodecIds;
import com.obby.android.localscreenshare.core.codec.Encoder;
import com.obby.android.localscreenshare.core.codec.EncoderFactory;
import com.obby.android.localscreenshare.core.codec.EncoderOutput;
import com.obby.android.localscreenshare.core.codec.mediacodec.MediaCodecBridgeFactory;
import com.obby.android.localscreenshare.core.metrics.PipelineMetrics;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.model.StreamParameters;
import com.obby.android.localscreenshare.core.util.NanoClock;

/**
 * Creates {@link H264Encoder} instances backed by a {@link MediaCodecBridgeFactory}.
 *
 * <p>All H.264 encoders share one bridge factory, which supplies a fresh encoder bridge per
 * instance. The bridge is configured and its input surface is created during construction (inside
 * {@link H264Encoder}'s constructor); the returned encoder is not yet started.</p>
 *
 * <p><b>Probes.</b> The factory also carries the metric scope and clock handed to every encoder it
 * creates, because encoders are created by the runtime through the codec registration rather than
 * per call. A registration built without a scope creates unmeasured encoders, which is what the
 * single-argument constructor keeps doing.</p>
 */
public final class H264EncoderFactory implements EncoderFactory {

    private final MediaCodecBridgeFactory bridgeFactory;

    private final PipelineMetrics metrics;

    private final NanoClock clock;

    /**
     * Creates a factory whose encoders are not measured.
     */
    public H264EncoderFactory(MediaCodecBridgeFactory bridgeFactory) {
        this(bridgeFactory, PipelineMetrics.NONE, NanoClock.MONOTONIC);
    }

    /**
     * Creates a factory whose encoders report their encode-stage windows to {@code metrics}.
     *
     * @param bridgeFactory creates the platform encoder bridge; required
     * @param metrics       metric scope shared by every encoder of this registration; required
     * @param clock         clock used by the encode-stage probes; required
     * @throws IllegalArgumentException if any argument is null
     */
    public H264EncoderFactory(MediaCodecBridgeFactory bridgeFactory, PipelineMetrics metrics,
                              NanoClock clock) {
        if (bridgeFactory == null) {
            throw new IllegalArgumentException("bridgeFactory must not be null");
        }
        if (metrics == null) {
            throw new IllegalArgumentException("metrics must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.bridgeFactory = bridgeFactory;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public Encoder create(StreamKey streamKey, StreamDescriptor descriptor,
                          StreamParameters parameters, EncoderOutput output) {
        if (descriptor == null || !CodecIds.H264.equals(descriptor.getCodecId())) {
            throw new IllegalArgumentException("descriptor codecId must be " + CodecIds.H264);
        }
        return new H264Encoder(streamKey, descriptor, parameters, output, bridgeFactory, metrics,
                clock);
    }
}
