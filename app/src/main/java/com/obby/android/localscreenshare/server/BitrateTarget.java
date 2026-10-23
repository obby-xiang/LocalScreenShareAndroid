package com.obby.android.localscreenshare.server;

/**
 * Narrow view of the running encoder that the backpressure coordinator may change.
 *
 * <p>The seam exists so the coordinator is a JVM unit under test: a fake target can count calls,
 * refuse a change and simulate an external write without a MediaCodec. The production
 * implementation is {@link RuntimeBitrateTarget}.</p>
 */
public interface BitrateTarget {

    /**
     * Returns the number of attached client sessions.
     */
    int getSessionCount();

    /**
     * Returns the codec id of the published descriptor, or null while none is published.
     */
    String getCodecId();

    /**
     * Returns the target bitrate currently in force, in bits per second.
     *
     * <p>Reading it back is how the coordinator detects an external write: a user setting, a
     * thermal tier, a rotation or a size change all write the bitrate outside the coordinator.</p>
     */
    int getTargetBitrateBps();

    /**
     * Applies a new target bitrate.
     *
     * @param bitrateBps target in bits per second
     * @throws RuntimeException when the encoder refuses or the runtime is not running
     */
    void applyTargetBitrate(int bitrateBps);
}
