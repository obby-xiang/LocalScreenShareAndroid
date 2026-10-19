package com.obby.android.localscreenshare.core.codec;

import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.model.StreamParameters;

/**
 * Creates {@link Encoder} instances for one registered codec.
 *
 * <p>Ownership of the returned encoder passes to the caller, which must start it and close it.
 * The factory must not start the encoder: a surface-input encoder may acquire and configure codec
 * resources during creation (including creating its input surface), but start is a separate,
 * caller-driven step.</p>
 *
 * <p>Creation runs on the thread that starts the stream and may acquire native codec resources
 * synchronously. Failures are unchecked; the runtime wraps them in {@link EncoderException} for a
 * start attempt (or in {@link CodecSwitchException} during a descriptor switch) and treats the
 * attempt as failed. Implementations should reject a descriptor whose codec id does not match
 * this factory's codec.</p>
 */
public interface EncoderFactory {

    /**
     * Creates an encoder for the given stream and encode configuration.
     *
     * @param streamKey  identity of the stream the encoder belongs to
     * @param descriptor requested encode configuration; its epoch is the epoch of every access
     *                   unit the encoder will emit
     * @param parameters initial rate and cadence hints
     * @param output     sink that receives configuration, access units and terminal failures
     * @return a new encoder that has not been started
     */
    Encoder create(StreamKey streamKey, StreamDescriptor descriptor,
                   StreamParameters parameters, EncoderOutput output);
}
