package com.obby.android.localscreenshare.core.codec;

import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;

/**
 * Decoder for one stream instance, created by a {@link DecoderFactory}.
 *
 * <p><b>Lifecycle.</b> A decoder is configured exactly once with the stream's published
 * {@link StreamDescriptor} through {@link #configure(StreamDescriptor)}, then receives access
 * units through {@link #decode(EncodedAccessUnit)}. Applying a different decode configuration
 * means creating a new decoder; an instance is not reused across descriptors. All failures in
 * configuration, decoding and rendering use {@link DecoderException} as their single error
 * channel.</p>
 *
 * <p><b>Output.</b> Decoded output never travels through the decoder's return values. It is
 * written either directly to a surface obtained from the {@link
 * com.obby.android.localscreenshare.core.render.RenderTarget} supplied to the factory or handed
 * to that target as an encoded image frame. A decoder must not close or release the render target,
 * which is owned by the display side and outlives individual decoders.</p>
 *
 * <p><b>Threading.</b> Calls are serialized by the owner (a client media session); implementations
 * are not required to be thread-safe and must not be shared between threads.</p>
 */
public interface Decoder {

    /**
     * Configures the decoder for the supplied descriptor.
     *
     * <p>Must succeed before the first {@link #decode(EncodedAccessUnit)}. The descriptor must
     * describe this decoder's codec and carry every configuration part the codec needs; a decoder
     * may acquire codec resources here. Failures are reported as {@link DecoderException}.</p>
     */
    void configure(StreamDescriptor descriptor);

    /**
     * Decodes one access unit and renders the result into the bound render target.
     *
     * <p>Preconditions: the unit is non-null, {@link #configure(StreamDescriptor)} has succeeded,
     * and the unit's config epoch equals the configured descriptor's epoch. The payload is opaque
     * codec data, not a decoded frame. Any decode or render failure, including a rejected
     * precondition, is reported as {@link DecoderException}; implementations must not let checked
     * exceptions escape.</p>
     */
    void decode(EncodedAccessUnit accessUnit) throws DecoderException;

    /**
     * Discards everything that belongs to the frames already submitted.
     *
     * <p>Called by the owning session when the receiver intentionally skips ahead: buffered input
     * and undisplayed output must not surface after the stream resumes, and the decoder must not
     * continue a reference chain that started before the skip. After this call the decoder stays
     * configured but expects a random-access point before ordinary frames, so the next picture it
     * shows is a complete one.</p>
     *
     * <p>Implementations that hold no such state implement this as a no-op. Failures are contained
     * by the implementation, like {@link #close()}: a decoder that cannot flush is not a reason to
     * abandon the recovery.</p>
     */
    void flush();

    /**
     * Releases codec resources.
     *
     * <p>Idempotent, does not close the render target, and renders nothing after it returns.
     * Failures during release are contained by the implementation.</p>
     */
    void close();
}
