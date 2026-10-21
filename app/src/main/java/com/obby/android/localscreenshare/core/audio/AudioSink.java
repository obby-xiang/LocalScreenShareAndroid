package com.obby.android.localscreenshare.core.audio;

/**
 * Playback sink contract.
 *
 * <p>The sink hides {@code AudioTrack} so the buffering and degradation logic of the client audio
 * session can be tested on the JVM. A sink is opened once per output format and written with
 * little-endian 16-bit PCM; volume changes are applied without stopping playback, so unmuting never
 * restarts the timeline.</p>
 *
 * <p><b>Playhead.</b> {@link #getPresentedFrames(long)} reports how many frames have left the sink,
 * counted in the same unit as the frames written, or a negative value when the platform cannot
 * report a position. It is deliberately a frame count and not a time: only counts share a base with
 * the session's own bookkeeping, and the session converts them into stream time where the session
 * clock lives.</p>
 */
public interface AudioSink {

    /**
     * Opens the sink for one format.
     *
     * @throws AudioSinkException when no output can be created (for example the device has no
     *                            usable audio output)
     */
    void open(int sampleRate, int channels) throws AudioSinkException;

    /**
     * Returns whether the sink is open.
     */
    boolean isOpen();

    /**
     * Writes PCM samples; blocks until the sink accepted them.
     *
     * @throws AudioSinkException when the sink failed
     */
    void write(byte[] pcm, int offset, int count) throws AudioSinkException;

    /**
     * Applies a volume between zero (muted) and one; does not stop playback.
     */
    void setVolume(float volume);

    /**
     * Starts or resumes playback; idempotent.
     */
    void play();

    /**
     * Stops playback without releasing the sink; idempotent.
     */
    void pause();

    /**
     * Releases the sink; idempotent and non-throwing.
     */
    void release();

    /**
     * Returns the number of PCM frames the device has presented at {@code nowNs}, or a negative
     * value when unknown.
     *
     * <p>The count is per channel and cumulative since the sink was opened, which is the base the
     * session's written-frame count uses. Counts advance with the device clock, so projecting the
     * last platform timestamp onto {@code nowNs} is what keeps the value comparable between two
     * measurements rather than stale by up to one buffer.</p>
     *
     * @param nowNs monotonic instant to project the last platform timestamp onto
     */
    long getPresentedFrames(long nowNs);
}
