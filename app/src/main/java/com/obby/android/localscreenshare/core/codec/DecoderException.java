package com.obby.android.localscreenshare.core.codec;

/**
 * The single error channel for decode and render failures.
 *
 * <p>Unchecked, so implementations can report failures without checked-exception signatures.
 * Decoders wrap the underlying failure as the cause when one exists; the cause is {@code null}
 * for precondition violations such as an unconfigured decoder or an epoch mismatch. No other
 * exception type is used to report decode problems.</p>
 */
public final class DecoderException extends RuntimeException {

    public DecoderException(String message, Throwable cause) {
        super(message, cause);
    }
}
