package com.obby.android.localscreenshare.core.audio;

/**
 * Failure of the playback sink.
 *
 * <p>Like every other audio failure it only closes the audio session: the video keeps rendering and
 * the viewer marks sound as unavailable.</p>
 */
public final class AudioSinkException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a failure.
     *
     * @param message English detail
     * @param cause   underlying failure, or null
     */
    public AudioSinkException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates a failure without an underlying cause.
     */
    public AudioSinkException(String message) {
        super(message);
    }
}
