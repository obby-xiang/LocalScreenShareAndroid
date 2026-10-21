package com.obby.android.localscreenshare.core.audio;

/**
 * Playback sink contract.
 *
 * <p>The sink hides {@code AudioTrack} so the buffering and degradation logic of the client audio
 * session can be tested on the JVM. A sink is opened once per output format and written with
 * little-endian 16-bit PCM; volume changes are applied without stopping playback, so unmuting never
 * restarts the timeline.</p>
 *
 * <p><b>Playhead.</b> {@link #getPlayheadNs()} reports the estimated played position on the
 * monotonic clock, or a negative value when the platform cannot report one. It is used by the drift
 * guard to compare what is audible with what the session clock expects.</p>
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
     * Returns the estimated playhead position in monotonic nanoseconds, or a negative value when
     * unknown.
     */
    long getPlayheadNs();
}
