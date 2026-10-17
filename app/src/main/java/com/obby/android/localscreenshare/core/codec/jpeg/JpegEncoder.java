package com.obby.android.localscreenshare.core.codec.jpeg;

import android.view.Surface;

import com.obby.android.localscreenshare.core.codec.Encoder;
import com.obby.android.localscreenshare.core.codec.EncoderOutput;
import com.obby.android.localscreenshare.core.model.AccessUnitFlags;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.JoinInfo;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.model.StreamParameters;

import java.util.Collections;

/**
 * Encoder for already-compressed JPEG frames. Each accepted frame becomes a
 * single join-point access unit; the per-instance sequence restarts at zero.
 */
public final class JpegEncoder implements Encoder {

    private final StreamKey streamKey;
    private final StreamDescriptor descriptor;
    private final EncoderOutput output;

    private boolean started;
    private boolean stopped;
    private boolean closed;
    private long sequence;

    public JpegEncoder(StreamKey streamKey, StreamDescriptor descriptor,
                       StreamParameters parameters, EncoderOutput output) {
        if (streamKey == null) {
            throw new IllegalArgumentException("streamKey must not be null");
        }
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor must not be null");
        }
        if (output == null) {
            throw new IllegalArgumentException("output must not be null");
        }
        this.streamKey = streamKey;
        this.descriptor = descriptor;
        this.output = output;
    }

    @Override
    public void start() {
        started = true;
        stopped = false;
    }

    @Override
    public void stop() {
        stopped = true;
    }

    @Override
    public Surface getInputSurface() {
        return null;
    }

    @Override
    public void updateParameters(StreamParameters parameters) {
        // JPEG frames are produced upstream; there are no live encoder parameters.
    }

    @Override
    public void requestSyncPoint() {
        // Every complete JPEG frame is independently decodable, so no-op.
    }

    @Override
    public void close() {
        closed = true;
    }

    public boolean offerJpegFrame(long ptsUs, byte[] jpegBytes) {
        if (!started || stopped || closed) {
            return false;
        }
        if (!JpegFrameValidator.isCompleteFrame(jpegBytes)) {
            return false;
        }
        int configEpoch = descriptor.getConfigEpoch();
        long currentSequence = sequence;
        sequence++;
        EncodedAccessUnit accessUnit = new EncodedAccessUnit(streamKey, configEpoch,
                currentSequence, ptsUs, EncodedAccessUnit.DTS_UNSET,
                AccessUnitFlags.of(true, true, false),
                new JoinInfo(configEpoch, Collections.emptyList()), null, jpegBytes);
        output.onAccessUnit(accessUnit);
        return true;
    }
}
