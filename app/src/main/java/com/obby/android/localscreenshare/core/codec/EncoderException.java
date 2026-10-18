package com.obby.android.localscreenshare.core.codec;

/**
 * Unchecked failure raised when an encoder cannot be created, configured, or
 * started. Extends {@link IllegalStateException} so existing callers that expect
 * an {@code IllegalStateException} continue to work.
 */
public final class EncoderException extends IllegalStateException {

    public EncoderException(String message) {
        super(message);
    }

    public EncoderException(String message, Throwable cause) {
        super(message, cause);
    }
}
