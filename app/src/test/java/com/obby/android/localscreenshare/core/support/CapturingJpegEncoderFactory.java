package com.obby.android.localscreenshare.core.support;

import android.view.Surface;

import com.obby.android.localscreenshare.core.codec.Encoder;
import com.obby.android.localscreenshare.core.codec.EncoderFactory;
import com.obby.android.localscreenshare.core.codec.EncoderOutput;
import com.obby.android.localscreenshare.core.codec.jpeg.JpegEncoder;
import com.obby.android.localscreenshare.core.codec.jpeg.JpegEncoderFactory;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.model.StreamParameters;

/**
 * Wraps {@link JpegEncoderFactory} and exposes the created encoder so tests can
 * feed complete JPEG frames. Also counts join-point requests, because the JPEG
 * encoder itself treats {@code requestSyncPoint()} as a no-op.
 */
public final class CapturingJpegEncoderFactory implements EncoderFactory {

    private final JpegEncoderFactory delegate = new JpegEncoderFactory();

    private JpegEncoder lastEncoder;

    private int requestCount;

    @Override
    public Encoder create(StreamKey streamKey, StreamDescriptor descriptor,
                          StreamParameters parameters, EncoderOutput output) {
        final JpegEncoder encoder =
                (JpegEncoder) delegate.create(streamKey, descriptor, parameters, output);
        lastEncoder = encoder;
        return new Encoder() {

            @Override
            public void start() {
                encoder.start();
            }

            @Override
            public void stop() {
                encoder.stop();
            }

            @Override
            public Surface getInputSurface() {
                return encoder.getInputSurface();
            }

            @Override
            public void updateParameters(StreamParameters newParameters) {
                encoder.updateParameters(newParameters);
            }

            @Override
            public void requestSyncPoint() {
                requestCount++;
                encoder.requestSyncPoint();
            }

            @Override
            public void close() {
                encoder.close();
            }
        };
    }

    public JpegEncoder getLastEncoder() {
        return lastEncoder;
    }

    public int getRequestCount() {
        return requestCount;
    }
}
