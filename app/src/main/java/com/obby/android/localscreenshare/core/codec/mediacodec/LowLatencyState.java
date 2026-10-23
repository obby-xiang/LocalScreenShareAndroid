package com.obby.android.localscreenshare.core.codec.mediacodec;

/**
 * Outcome of the optional decoder keys requested by {@link AndroidMediaCodecDecoderBridge}.
 *
 * <p>The state is a tri-state reading of the configuration request, not of the picture: it says
 * which optional keys the codec accepted when it was configured, and nothing about the latency
 * that resulted. It is reported through {@link MediaCodecDecoderBridge#getLowLatencyState()} and
 * mapped to the {@code dec.lowLat} gauge by the decoder, where {@link #NOT_REQUESTED} stays
 * unprinted so old platforms do not look like a failed request.</p>
 */
public enum LowLatencyState {

    /**
     * The platform is too old, so the optional keys were never sent.
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
