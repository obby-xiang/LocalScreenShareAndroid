package com.obby.android.localscreenshare.core.codec;

/**
 * Unchecked failure raised when an encoder cannot be created, configured, started, or when it
 * fails asynchronously.
 *
 * <p>Extends {@link IllegalStateException} so existing callers that expect an
 * {@code IllegalStateException} continue to work. A failure reported through
 * {@link EncoderOutput#onEncoderFailure} is terminal for that encoder instance; the runtime marks
 * its attempt failed and does not expect further output.</p>
 */
public final class EncoderException extends IllegalStateException {

    public EncoderException(String message) {
        super(message);
    }

    public EncoderException(String message, Throwable cause) {
        super(message, cause);
    }
}
