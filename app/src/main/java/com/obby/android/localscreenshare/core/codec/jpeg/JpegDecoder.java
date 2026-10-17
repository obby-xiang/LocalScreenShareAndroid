package com.obby.android.localscreenshare.core.codec.jpeg;

import com.obby.android.localscreenshare.core.codec.Decoder;
import com.obby.android.localscreenshare.core.codec.DecoderException;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.render.EncodedImageFrame;
import com.obby.android.localscreenshare.core.render.RenderTarget;

/**
 * Decoder for JPEG access units. It does not decode pixels: it validates the
 * epoch and frame completeness, then forwards the compressed payload as an
 * {@link EncodedImageFrame} to the {@link RenderTarget}.
 */
public final class JpegDecoder implements Decoder {

    private final StreamKey streamKey;
    private final RenderTarget renderTarget;

    private StreamDescriptor descriptor;
    private boolean configured;

    public JpegDecoder(StreamKey streamKey, StreamDescriptor descriptor,
                       RenderTarget renderTarget) {
        if (streamKey == null) {
            throw new IllegalArgumentException("streamKey must not be null");
        }
        if (renderTarget == null) {
            throw new IllegalArgumentException("renderTarget must not be null");
        }
        this.streamKey = streamKey;
        this.descriptor = descriptor;
        this.renderTarget = renderTarget;
    }

    @Override
    public void configure(StreamDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor must not be null");
        }
        this.descriptor = descriptor;
        this.configured = true;
    }

    @Override
    public void decode(EncodedAccessUnit accessUnit) throws DecoderException {
        if (accessUnit == null) {
            throw new DecoderException("access unit must not be null", null);
        }
        if (!configured) {
            throw new DecoderException("decoder is not configured", null);
        }
        if (accessUnit.getConfigEpoch() != descriptor.getConfigEpoch()) {
            throw new DecoderException("config epoch mismatch: expected="
                    + descriptor.getConfigEpoch()
                    + ", actual=" + accessUnit.getConfigEpoch(), null);
        }
        if (!JpegFrameValidator.isCompleteFrame(accessUnit.getPayload())) {
            throw new DecoderException("incomplete JPEG frame", null);
        }
        EncodedImageFrame frame = new EncodedImageFrame(accessUnit.getPayload(),
                accessUnit.getPtsUs(), descriptor.getVisibleWidth(),
                descriptor.getVisibleHeight());
        try {
            renderTarget.renderEncodedImage(frame);
        } catch (RuntimeException e) {
            throw new DecoderException("failed to render encoded image", e);
        }
    }

    @Override
    public void close() {
        configured = false;
        descriptor = null;
    }
}
