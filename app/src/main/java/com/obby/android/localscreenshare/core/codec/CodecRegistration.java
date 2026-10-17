package com.obby.android.localscreenshare.core.codec;

/**
 * Immutable registration of a codec: its descriptor plus the factories used to
 * create encoders and decoders for it, and a static fallback priority.
 *
 * <p>{@code fallbackPriority} is metadata only: lower values are preferred, but
 * the registry does not sort or interpret it.</p>
 */
public final class CodecRegistration {

    private final CodecDescriptor descriptor;
    private final EncoderFactory encoderFactory;
    private final DecoderFactory decoderFactory;
    private final CodecPolicy policy;
    private final int fallbackPriority;

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

    public CodecPolicy getPolicy() {
        return policy;
    }

    public int getFallbackPriority() {
        return fallbackPriority;
    }
}
