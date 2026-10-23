package com.obby.android.localscreenshare.core.support;

import com.obby.android.localscreenshare.core.codec.mediacodec.LowLatencyState;
import com.obby.android.localscreenshare.core.codec.mediacodec.MediaCodecBridgeFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JVM-only bridge factory that creates a fresh fake for every encoder and decoder request and
 * records the requested MIME types.
 *
 * <p>A decoder is created inside the decoder's own {@code configure}, so {@link
 * #setNextDecoderLowLatencyState(LowLatencyState)} preloads the state onto the next one; the
 * preload is consumed once and never leaks into a later decoder.</p>
 */
public final class FakeMediaCodecBridgeFactory implements MediaCodecBridgeFactory {

    private final List<FakeMediaCodecEncoderBridge> createdEncoders = new CopyOnWriteArrayList<>();
    private final List<FakeMediaCodecDecoderBridge> createdDecoders = new CopyOnWriteArrayList<>();
    private final List<String> createdEncoderMimes = new CopyOnWriteArrayList<>();
    private final List<String> createdDecoderMimes = new CopyOnWriteArrayList<>();

    private final AtomicReference<LowLatencyState> nextDecoderLowLatencyState =
            new AtomicReference<>();

    private volatile FakeMediaCodecEncoderBridge lastEncoder;
    private volatile FakeMediaCodecDecoderBridge lastDecoder;

    @Override
    public FakeMediaCodecEncoderBridge createEncoder(String mime) {
        FakeMediaCodecEncoderBridge encoder = new FakeMediaCodecEncoderBridge();
        lastEncoder = encoder;
        createdEncoders.add(encoder);
        createdEncoderMimes.add(mime);
        return encoder;
    }

    @Override
    public FakeMediaCodecDecoderBridge createDecoder(String mime) {
        FakeMediaCodecDecoderBridge decoder = new FakeMediaCodecDecoderBridge();
        LowLatencyState state = nextDecoderLowLatencyState.getAndSet(null);
        if (state != null) {
            decoder.setLowLatencyState(state);
        }
        lastDecoder = decoder;
        createdDecoders.add(decoder);
        createdDecoderMimes.add(mime);
        return decoder;
    }

    /**
     * Makes the next created decoder report {@code state}; the value is consumed by that decoder.
     */
    public void setNextDecoderLowLatencyState(LowLatencyState state) {
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        nextDecoderLowLatencyState.set(state);
    }

    public FakeMediaCodecEncoderBridge getLastEncoder() {
        return lastEncoder;
    }

    public FakeMediaCodecDecoderBridge getLastDecoder() {
        return lastDecoder;
    }

    public String getLastEncoderMime() {
        return createdEncoderMimes.isEmpty() ? null
                : createdEncoderMimes.get(createdEncoderMimes.size() - 1);
    }

    public String getLastDecoderMime() {
        return createdDecoderMimes.isEmpty() ? null
                : createdDecoderMimes.get(createdDecoderMimes.size() - 1);
    }

    public List<FakeMediaCodecEncoderBridge> getCreatedEncoders() {
        return Collections.unmodifiableList(new ArrayList<>(createdEncoders));
    }

    public List<FakeMediaCodecDecoderBridge> getCreatedDecoders() {
        return Collections.unmodifiableList(new ArrayList<>(createdDecoders));
    }

    public List<String> getCreatedEncoderMimes() {
        return Collections.unmodifiableList(new ArrayList<>(createdEncoderMimes));
    }

    public List<String> getCreatedDecoderMimes() {
        return Collections.unmodifiableList(new ArrayList<>(createdDecoderMimes));
    }
}
