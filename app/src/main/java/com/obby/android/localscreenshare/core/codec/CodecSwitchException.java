package com.obby.android.localscreenshare.core.codec;

/**
 * Raised when a stream cannot be switched to a requested descriptor.
 *
 * <p>Unchecked. Covers both unsupported switches (for example a codec pair whose configuration is
 * still deferred) and a new encoder that could not be created or started during the switch. The
 * original failure, when there is one, is preserved as the cause and the old encoder remains
 * active.</p>
 */
public final class CodecSwitchException extends RuntimeException {

    public CodecSwitchException(String message, Throwable cause) {
        super(message, cause);
    }
}
