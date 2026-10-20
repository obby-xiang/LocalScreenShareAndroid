package com.obby.android.localscreenshare.core.codec.mediacodec;

/**
 * Creates the platform bridges used by the H.264 encoder and decoder.
 *
 * <p>This is the single seam between the pure-Java H.264 adapter and {@code android.media}; the
 * adapter never touches {@code MediaCodec} itself. Production code uses
 * {@link AndroidMediaCodecBridgeFactory}; JVM tests inject a fake factory that returns
 * deterministic bridges or injects failures.</p>
 *
 * <p>Each call must return a new bridge owning a new codec: bridges are never shared between an
 * encoder and a decoder, and while one factory may serve every encoder and decoder of a
 * registration, each returned bridge has a single owner. A creation failure is reported as an
 * unchecked exception (the Android implementation wraps the checked {@code IOException} in an
 * {@code IllegalStateException}); the adapter turns that into an encoder or decoder failure after
 * releasing whatever it had already created.</p>
 */
public interface MediaCodecBridgeFactory {

    /** Creates an encoder bridge for {@code mime}, or throws an unchecked exception on failure. */
    MediaCodecEncoderBridge createEncoder(String mime);

    /** Creates a decoder bridge for {@code mime}, or throws an unchecked exception on failure. */
    MediaCodecDecoderBridge createDecoder(String mime);
}
