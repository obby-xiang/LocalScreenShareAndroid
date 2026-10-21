package com.obby.android.localscreenshare.core.audio;

/**
 * Failure of the client audio decode path.
 *
 * <p>Audio is optional on the viewer too: a decoder that cannot be created, a payload the codec
 * rejects or an output format change the player cannot follow closes the audio session and leaves
 * the video stream running. The message is for logs, never for the user.</p>
 */
public final class AudioDecoderException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a failure.
     *
     * @param message English detail
     * @param cause   underlying failure, or null
     */
    public AudioDecoderException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates a failure without an underlying cause.
     */
    public AudioDecoderException(String message) {
        super(message);
    }
}
