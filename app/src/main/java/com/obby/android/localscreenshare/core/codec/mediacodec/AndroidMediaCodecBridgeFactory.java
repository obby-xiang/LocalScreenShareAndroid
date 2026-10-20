package com.obby.android.localscreenshare.core.codec.mediacodec;

import android.media.MediaCodec;

import java.io.IOException;

/**
 * Android {@link MediaCodec} implementation of {@link MediaCodecBridgeFactory}.
 *
 * <p>Each call to {@link #createEncoder(String)} or {@link #createDecoder(String)} creates a fresh
 * {@link MediaCodec} via {@link MediaCodec#createEncoderByType(String)} /
 * {@link MediaCodec#createDecoderByType(String)} and returns a new bridge that owns that codec. The
 * caller must release each returned bridge exactly once.</p>
 */
public final class AndroidMediaCodecBridgeFactory implements MediaCodecBridgeFactory {

    /** Creates the factory. */
    public AndroidMediaCodecBridgeFactory() {
    }

    @Override
    public AndroidMediaCodecEncoderBridge createEncoder(String mime) {
        try {
            return new AndroidMediaCodecEncoderBridge(mime, MediaCodec.createEncoderByType(mime));
        } catch (IOException e) {
            throw new IllegalStateException("failed to create H.264 encoder for mime " + mime, e);
        }
    }

    @Override
    public AndroidMediaCodecDecoderBridge createDecoder(String mime) {
        try {
            return new AndroidMediaCodecDecoderBridge(mime, MediaCodec.createDecoderByType(mime));
        } catch (IOException e) {
            throw new IllegalStateException("failed to create H.264 decoder for mime " + mime, e);
        }
    }
}
