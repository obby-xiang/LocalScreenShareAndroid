package com.obby.android.localscreenshare.core.codec;

/**
 * Raised when a stream must be handed from one codec to another.
 */
public final class CodecSwitchException extends RuntimeException {

    public CodecSwitchException(String message, Throwable cause) {
        super(message, cause);
    }
}
