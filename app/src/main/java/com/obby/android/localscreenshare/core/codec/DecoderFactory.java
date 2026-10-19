package com.obby.android.localscreenshare.core.codec;

import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.render.RenderTarget;

/**
 * Creates {@link Decoder} instances for one registered codec, binding each decoder to the
 * {@link RenderTarget} that receives decoded output.
 *
 * <p>The render target is passed explicitly so that a decoder never owns, creates or closes it.
 * The same target instance is reused for every decoder of a client session, including replacement
 * decoders created after a configuration change or a resynchronization. A decoder returned by this
 * factory is not configured: the caller must call {@link Decoder#configure} with the stream
 * descriptor before decoding.</p>
 *
 * <p>Depending on the codec, creation may be cheap (work happens in {@code configure}) or may
 * acquire codec resources immediately. Failures are unchecked; the client media session treats
 * them as a configuration failure and moves to the resynchronizing state.</p>
 */
public interface DecoderFactory {

    /**
     * Creates a decoder for the given stream and decode configuration.
     *
     * @param streamKey    identity of the stream the decoder belongs to
     * @param descriptor   decode configuration the decoder is expected to handle; it is applied
     *                     explicitly through {@link Decoder#configure}
     * @param renderTarget destination for decoded output; owned by the caller
     * @return a new decoder that has not been configured
     */
    Decoder create(StreamKey streamKey, StreamDescriptor descriptor, RenderTarget renderTarget);
}
