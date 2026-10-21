package com.obby.android.localscreenshare.core.audio;

/**
 * Why audio is or is not available, in terms the user interface can explain.
 *
 * <p>The share side must never leave a user guessing why there is no sound. Every reason maps to
 * one resource key so both languages stay in sync, and to a retry flag: a denied permission or a
 * busy capture device may work on the next attempt, an unsupported API level or a client without a
 * decoder will not. The enum deliberately has no Android dependency, so the mapping is covered by
 * JVM tests.</p>
 */
public enum AudioAvailability {

    /**
     * Audio is available and either running or waiting for a viewer.
     */
    AVAILABLE(null, false),

    /**
     * This Android version cannot capture playback audio (API 25-28).
     */
    API_UNSUPPORTED("audio_unavailable_api_unsupported", false),

    /**
     * The recording permission was denied; audio is not captured and the microphone is not used.
     */
    PERMISSION_DENIED("audio_unavailable_permission_denied", true),

    /**
     * The capture device could not be opened or stopped working.
     */
    CAPTURE_FAILED("audio_unavailable_capture_failed", true),

    /**
     * The AAC encoder could not be created or failed while running.
     */
    ENCODER_FAILED("audio_unavailable_encoder_failed", true),

    /**
     * Nothing is being watched, so capture is stopped to save power.
     */
    NO_VIEWERS("audio_status_no_viewers", false),

    /**
     * The connected viewer did not advertise an AAC decoder.
     */
    CLIENT_UNSUPPORTED("audio_status_client_unsupported", false);

    /**
     * Lowest API level that can capture playback audio.
     */
    public static final int MIN_CAPTURE_API = 29;

    private final String stringKey;

    private final boolean retryable;

    AudioAvailability(String stringKey, boolean retryable) {
        this.stringKey = stringKey;
        this.retryable = retryable;
    }

    /**
     * Returns the availability for an Android API level.
     *
     * @param sdkInt platform API level
     */
    public static AudioAvailability forApiLevel(int sdkInt) {
        return sdkInt >= MIN_CAPTURE_API ? AVAILABLE : API_UNSUPPORTED;
    }

    /**
     * Classifies a capture or encoding failure.
     *
     * @param reason failure reason; null returns {@link #AVAILABLE}
     */
    public static AudioAvailability fromFailure(AudioCaptureException.Reason reason) {
        if (reason == null) {
            return AVAILABLE;
        }
        switch (reason) {
            case PERMISSION_DENIED:
                return PERMISSION_DENIED;
            case UNSUPPORTED:
                return API_UNSUPPORTED;
            case ENCODER_FAILED:
                return ENCODER_FAILED;
            case DEVICE_BUSY:
            case INTERNAL:
            default:
                return CAPTURE_FAILED;
        }
    }

    /**
     * Returns the resource key of the explanation, or null when audio is available.
     */
    public String getStringKey() {
        return stringKey;
    }

    /**
     * Returns whether trying again can succeed without a settings change by the user.
     */
    public boolean isRetryable() {
        return retryable;
    }
}
