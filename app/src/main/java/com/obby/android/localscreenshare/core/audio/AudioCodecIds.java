package com.obby.android.localscreenshare.core.audio;

/**
 * Codec ids and MIME types used by the audio pipeline.
 *
 * <p>Audio does not join {@code CodecRegistry}: the codec registry describes the video stream's
 * descriptor and policy semantics, which have no meaning for a self-contained audio track. Audio
 * therefore keeps its own small constants next to the audio model. AAC-LC is the only codec this
 * version produces, because its encoder and decoder are mandatory on every Android device that
 * defines a microphone; Opus encoding is not guaranteed and would require a new native
 * dependency.</p>
 */
public final class AudioCodecIds {

    /**
     * Codec id of the AAC-LC track, used as the capability key.
     */
    public static final String AAC = "aac";

    /**
     * MIME type of AAC-LC in an MP4 container, as accepted by {@code MediaCodec}.
     */
    public static final String AAC_MIME = "audio/mp4a-latm";

    /**
     * Identifier of the AudioSpecificConfig configuration part.
     */
    public static final String CONFIG_PART_CSD_0 = "csd-0";

    private AudioCodecIds() {
    }
}
