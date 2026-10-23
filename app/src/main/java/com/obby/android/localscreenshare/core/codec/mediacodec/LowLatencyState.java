package com.obby.android.localscreenshare.core.codec.mediacodec;

/**
 * Outcome of the optional decoder keys requested by {@link AndroidMediaCodecDecoderBridge}.
 *
 * <p>The enum has four constants, while the {@code dec.lowLat} gauge it maps to reports three
 * values: {@link #APPLIED} and {@link #APPLIED_WITHOUT_PRIORITY} share the "applied" value, and
 * {@link #NOT_REQUESTED} writes nothing at all. The state is the outcome of the configuration
 * attempt, not a reading of the picture: it says which optional keys the codec accepted when it was
 * configured, and nothing about the latency that resulted. It is reported through
 * {@link MediaCodecDecoderBridge#getLowLatencyState()} and mapped to the gauge by the decoder; when
 * the decoder is closed it clears the value it wrote, so a codec switch does not leave the previous
 * decoder's reading behind. A decoder that never wrote the gauge leaves it untouched when closed.</p>
 */
public enum LowLatencyState {

    /**
     * The optional keys were not requested (the platform is too old to send them), so the gauge is
     * never written for this decoder.
     */
    NOT_REQUESTED,

    /**
     * Both optional keys were accepted.
     */
    APPLIED,

    /**
     * The low-latency key survived, the priority key was dropped.
     */
    APPLIED_WITHOUT_PRIORITY,

    /**
     * Every attempt that carried the low-latency key was rejected.
     */
    REJECTED
}
