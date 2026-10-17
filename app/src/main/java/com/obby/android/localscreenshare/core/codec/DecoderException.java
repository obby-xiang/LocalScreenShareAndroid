package com.obby.android.localscreenshare.core.codec;

/**
 * The single error channel for decode/render failures.
 */
public final class DecoderException extends RuntimeException {

    public DecoderException(String message, Throwable cause) {
        super(message, cause);
    }
}
