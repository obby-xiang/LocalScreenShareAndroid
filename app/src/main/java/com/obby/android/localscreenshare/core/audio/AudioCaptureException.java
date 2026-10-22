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

    /**
     * Classifies a platform failure into the reason reported to the user.
     *
     * <p>The mapping is deliberately small and ordered. A revoked recording permission surfaces as
     * a {@link SecurityException} on some API levels, so the security check comes first. An
     * {@link IllegalStateException} means the capture device became unusable while it was in use,
     * which the user sees as a busy device. An unsupported device reports
     * {@link UnsupportedOperationException} or rejects an argument. Everything else is an internal
     * failure; the message of the original exception is kept as the cause by the caller.</p>
     *
     * @param failure failure to classify; {@code null} is classified as {@link Reason#INTERNAL}
     * @return the reason to report; never null
     */
    public static Reason classify(final Throwable failure) {
        if (failure instanceof SecurityException) {
            return Reason.PERMISSION_DENIED;
        }
        if (failure instanceof IllegalStateException) {
            return Reason.DEVICE_BUSY;
        }
        if (failure instanceof UnsupportedOperationException
                || failure instanceof IllegalArgumentException) {
            return Reason.UNSUPPORTED;
        }
        return Reason.INTERNAL;
    }
}
