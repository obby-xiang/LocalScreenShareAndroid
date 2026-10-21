package com.obby.android.localscreenshare.core.audio;

/**
 * Failure of the audio capture or encoding path.
 *
 * <p>The audio pipeline is optional: every failure is reported with one of these reasons so the
 * user interface can explain why there is no sound, and the video stream keeps running. The reason
 * is never used to switch capture sources automatically - a denied permission or an unsupported
 * device never turns into a microphone capture.</p>
 */
public final class AudioCaptureException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Why audio is unavailable.
     */
    public enum Reason {

        /**
         * The recording permission was denied or revoked.
         */
        PERMISSION_DENIED,

        /**
         * This device or API level cannot capture playback audio.
         */
        UNSUPPORTED,

        /**
         * Another component holds the capture device or the audio input is busy.
         */
        DEVICE_BUSY,

        /**
         * The encoder could not be created, configured or driven.
         */
        ENCODER_FAILED,

        /**
         * Any other failure; the message carries the detail.
         */
        INTERNAL
    }

    private final Reason reason;

    /**
     * Creates a failure.
     *
     * @param reason  classification shown to the user; required
     * @param message English detail for logs; may be null
     * @param cause   underlying failure; may be null
     * @throws IllegalArgumentException if {@code reason} is null
     */
    public AudioCaptureException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        if (reason == null) {
            throw new IllegalArgumentException("reason must not be null");
        }
        this.reason = reason;
    }

    /**
     * Creates a failure without an underlying cause.
     */
    public AudioCaptureException(Reason reason, String message) {
        this(reason, message, null);
    }

    /**
     * Returns the classification of this failure.
     */
    public Reason getReason() {
        return reason;
    }
}
