package com.obby.android.localscreenshare.core.codec;

/**
 * Immutable registration of one codec: its descriptor, the factories used to create encoders and
 * decoders, the policy shared by its sessions, and a static fallback priority.
 *
 * <p>Factories are held instead of encoder or decoder instances because every session and every
 * configuration epoch needs its own instance.</p>
 *
 * <p>{@code fallbackPriority} is metadata only: lower values are preferred, but the registry does
 * not sort or interpret it. A stream hub consults it to propose an explicit common fallback codec
 * when clients do not share the active codec; the proposal never switches the active stream
 * automatically.</p>
 */
public final class CodecRegistration {

    private final CodecDescriptor descriptor;
    private final EncoderFactory encoderFactory;
    private final DecoderFactory decoderFactory;
    private final CodecPolicy policy;
    private final int fallbackPriority;

    /**
     * Creates a registration. Every component is required and the priority must be non-negative.
     */
    public CodecRegistration(CodecDescriptor descriptor, EncoderFactory encoderFactory,
                             DecoderFactory decoderFactory, CodecPolicy policy,
                             int fallbackPriority) {
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor must not be null");
        }
        if (encoderFactory == null) {
            throw new IllegalArgumentException("encoderFactory must not be null");
        }
        if (decoderFactory == null) {
            throw new IllegalArgumentException("decoderFactory must not be null");
        }
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        if (fallbackPriority < 0) {
            throw new IllegalArgumentException("fallbackPriority must be >= 0");
        }
        this.descriptor = descriptor;
        this.encoderFactory = encoderFactory;
        this.decoderFactory = decoderFactory;
        this.policy = policy;
        this.fallbackPriority = fallbackPriority;
    }

    public CodecDescriptor getDescriptor() {
        return descriptor;
    }

    public EncoderFactory getEncoderFactory() {
        return encoderFactory;
    }

    public DecoderFactory getDecoderFactory() {
        return decoderFactory;
    }

    /** Returns the policy shared by all sessions that use this codec. */
    public CodecPolicy getPolicy() {
        return policy;
    }

    /**
     * Returns the fallback priority. Lower values are preferred; this is not a quality ranking and
     * does not order registrations inside the registry.
     */
    public int getFallbackPriority() {
        return fallbackPriority;
    }
}
