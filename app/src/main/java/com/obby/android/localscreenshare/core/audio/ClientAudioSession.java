package com.obby.android.localscreenshare.core.audio;

import com.obby.android.localscreenshare.core.metrics.PipelineMetrics;
import com.obby.android.localscreenshare.core.model.AudioAccessUnit;
import com.obby.android.localscreenshare.core.model.AudioDescriptor;
import com.obby.android.localscreenshare.core.util.NanoClock;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Client-side audio session: decode, buffer, play, mute and degrade.
 *
 * <p>It is completely independent of {@code ClientMediaSession}: audio has its own descriptor,
 * epoch and state, so a broken audio track never moves the video state machine and an audio
 * descriptor never resets the shared session clock. The session is driven by the client's decode
 * thread and writes through a dedicated writer thread by default, because a blocking
 * {@code AudioTrack} write must not stall video decoding.</p>
 *
 * <p><b>States.</b> {@code IDLE} before the first descriptor, {@code ACTIVE} once a decoder is
 * configured, {@code UNAVAILABLE} after any failure (unsupported codec, decoder error, output
 * error), and {@code CLOSED} after {@link #close()}. Only {@code ACTIVE} accepts frames; every
 * failure is reported once through the {@link Listener} and only closes this session.</p>
 *
 * <p><b>Muting.</b> {@link #setVolume(float)} applies the sink volume and keeps decoding and the
 * writer alive, so unmuting never jumps or restarts the audio timeline.</p>
 */
public final class ClientAudioSession {

    /**
     * Maximum decoded frames waiting for the writer.
     */
    private static final int MAX_PENDING_FRAMES = 32;

    /**
     * Quiet period after which an empty queue is treated as an underrun, in milliseconds.
     */
    private static final long UNDERRUN_QUIET_MS = 40L;

    /**
     * Writer wait while a starvation is being timed, in milliseconds.
     */
    private static final long WRITER_WAIT_MS = 20L;

    /**
     * Lifecycle state of the client audio track.
     */
    public enum State {

        /**
         * No descriptor has been applied yet.
         */
        IDLE,

        /**
         * A decoder is configured and frames are accepted.
         */
        ACTIVE,

        /**
         * Audio failed and will not be retried on this stream.
         */
        UNAVAILABLE,

        /**
         * The session was closed by its owner.
         */
        CLOSED
    }

    /**
     * Receiver of state transitions.
     */
    public interface Listener {

        /**
         * Called once per transition, outside the session lock.
         *
         * @param state  the new state
         * @param reason English reason for an unavailable state, or null
         */
        void onStateChanged(State state, String reason);
    }

    private final AudioDecoder decoder;

    private final AudioSink sink;

    private final Listener listener;

    private final NanoClock clock;

    private final boolean asyncWriter;

    private final SessionClock sessionClock;

    private final PipelineMetrics metrics;

    private final AudioJitterBuffer buffer = new AudioJitterBuffer();

    private final Object lock = new Object();

    private final ArrayDeque<PcmFrame> pending = new ArrayDeque<>();

    private AudioDescriptor descriptor;

    private State state = State.IDLE;

    private String unavailableReason;

    private boolean closed;

    private boolean playing;

    private float volume = 1.0f;

    private int sampleRate;

    private int channels;

    private byte[] silenceFrame;

    private long lastEnqueuedMs = Long.MIN_VALUE;

    private long lastDriftCheckMs = Long.MIN_VALUE;

    private long lastWrittenPtsUs = SessionClock.UNSET;

    private long writtenSamples;

    private long writtenEndPtsUs = SessionClock.UNSET;

    private long lastDriftMs;

    private long referenceOffsetUs = SessionClock.UNSET;

    private long writtenFrames;

    private Thread writer;

    /**
     * One decoded frame waiting for the writer, with the timestamp it belongs to.
     *
     * <p>{@code silence} marks the buffers the session produced itself - gap fill, a drift
     * correction or an underrun - so the written-media position advances by their duration instead
     * of jumping to the next decoded timestamp.</p>
     */
    private static final class PcmFrame {

        private final byte[] data;

        private final long ptsUs;

        private final boolean silence;

        private PcmFrame(byte[] data, long ptsUs, boolean silence) {
            this.data = data;
            this.ptsUs = ptsUs;
            this.silence = silence;
        }
    }

    /**
     * Creates a session with the production clock and a writer thread.
     */
    public ClientAudioSession(AudioDecoder decoder, AudioSink sink, Listener listener) {
        this(decoder, sink, listener, NanoClock.MONOTONIC, null, PipelineMetrics.NONE, true);
    }

    /**
     * Creates a session with an injectable clock and optional writer thread.
     *
     * <p>Tests pass {@code asyncWriter=false} and drive {@link #drainOnce()} themselves, which is
     * the exact loop the writer thread runs; production passes true so a blocking write never
     * stalls the decode thread.</p>
     *
     * @param decoder    audio decoder; required
     * @param sink       playback sink; required
     * @param listener   state listener; may be null
     * @param clock      clock used by the jitter buffer; required
     * @param asyncWriter true to run a dedicated writer thread
     * @throws IllegalArgumentException if a required argument is null
     */
    public ClientAudioSession(AudioDecoder decoder, AudioSink sink, Listener listener,
                              NanoClock clock, boolean asyncWriter) {
        this(decoder, sink, listener, clock, null, PipelineMetrics.NONE, asyncWriter);
    }

    /**
     * Creates a session that shares the session timeline with the video track.
     *
     * <p>The shared clock is what makes lip sync possible: the audio anchors it when it arrives
     * first and follows it when video did, and the drift guard compares the sink's playhead with
     * the same mapping the video pacer uses.</p>
     *
     * @param decoder      audio decoder; required
     * @param sink         playback sink; required
     * @param listener     state listener; may be null
     * @param clock        clock used by the jitter buffer; required
     * @param sessionClock shared session timeline, or null for a standalone audio timeline
     * @param metrics      metric scope of the receiving pipeline; required, may be
     *                     {@link PipelineMetrics#NONE}
     * @param asyncWriter  true to run a dedicated writer thread
     * @throws IllegalArgumentException if a required argument is null
     */
    public ClientAudioSession(AudioDecoder decoder, AudioSink sink, Listener listener,
                              NanoClock clock, SessionClock sessionClock, PipelineMetrics metrics,
                              boolean asyncWriter) {
        if (decoder == null) {
            throw new IllegalArgumentException("decoder must not be null");
        }
        if (sink == null) {
            throw new IllegalArgumentException("sink must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        if (metrics == null) {
            throw new IllegalArgumentException("metrics must not be null");
        }
        this.decoder = decoder;
        this.sink = sink;
        this.listener = listener;
        this.clock = clock;
        this.sessionClock = sessionClock;
        this.metrics = metrics;
        this.asyncWriter = asyncWriter;
    }

    /**
     * Returns the current state, read atomically.
     */
    public State getState() {
        synchronized (lock) {
            return state;
        }
    }

    /**
     * Returns why audio is unavailable, or null.
     */
    public String getUnavailableReason() {
        synchronized (lock) {
            return unavailableReason;
        }
    }

    /**
     * Returns the descriptor currently applied, or null.
     */
    public AudioDescriptor getDescriptor() {
        synchronized (lock) {
            return descriptor;
        }
    }

    /**
     * Returns the number of PCM buffers written to the sink, decoded and synthesized alike.
     */
    public long getWrittenFrames() {
        synchronized (lock) {
            return writtenFrames;
        }
    }

    /**
     * Applies a new audio descriptor and configures the decoder.
     *
     * <p>A stale epoch is ignored and a duplicate configuration is a no-op, so a re-sent descriptor
     * never restarts playback. An unknown codec or a decoder failure moves the session to
     * {@code UNAVAILABLE} without touching video.</p>
     */
    public void onDescriptor(AudioDescriptor newDescriptor) {
        if (newDescriptor == null) {
            return;
        }
        boolean failUnsupported = false;
        boolean failDecoder = false;
        boolean applied = false;
        synchronized (lock) {
            if (closed || state == State.UNAVAILABLE) {
                return;
            }
            if (!AudioCodecIds.AAC.equals(newDescriptor.getCodecId())) {
                failUnsupported = true;
            } else if (descriptor != null) {
                if (newDescriptor.getConfigEpoch() < descriptor.getConfigEpoch()) {
                    return;
                }
                if (newDescriptor.getConfigEpoch() == descriptor.getConfigEpoch()
                        && newDescriptor.sameDecodeConfig(descriptor)) {
                    return;
                }
            }
            if (!failUnsupported) {
                try {
                    decoder.configure(newDescriptor);
                } catch (AudioDecoderException | RuntimeException e) {
                    failDecoder = true;
                }
            }
            if (!failUnsupported && !failDecoder) {
                descriptor = newDescriptor;
                sampleRate = newDescriptor.getSampleRate();
                channels = newDescriptor.getChannels();
                silenceFrame = new byte[newDescriptor.getFrameSamples() * channels * 2];
                pending.clear();
                referenceOffsetUs = SessionClock.UNSET;
                buffer.reset();
                state = State.ACTIVE;
                applied = true;
            }
        }
        if (failUnsupported) {
            fail("unsupported audio codec");
        } else if (failDecoder) {
            fail("audio decoder unavailable");
        } else if (applied) {
            notifyState(State.ACTIVE, null);
        }
    }

    /**
     * Decodes and buffers one access unit.
     *
     * <p>Frames of another epoch are ignored. A decoded buffer is planned by the jitter buffer:
     * stale frames are dropped, gaps become silence, an overflowing buffer loses its oldest frames
     * and playback starts once the prefill target or wait limit is reached.</p>
     */
    public void onAccessUnit(AudioAccessUnit accessUnit) {
        if (accessUnit == null) {
            return;
        }
        final AudioDescriptor current;
        synchronized (lock) {
            if (closed || state != State.ACTIVE || descriptor == null) {
                return;
            }
            if (accessUnit.getConfigEpoch() != descriptor.getConfigEpoch()) {
                return;
            }
            current = descriptor;
        }
        final AudioPcm pcm;
        try {
            pcm = decoder.decode(accessUnit.getPayload(), accessUnit.getPtsUs());
        } catch (AudioDecoderException | RuntimeException e) {
            fail("audio decode failed");
            return;
        }
        if (pcm == null) {
            return;
        }
        boolean outputFailed = false;
        boolean startWriter = false;
        boolean notifyPlayback = false;
        synchronized (lock) {
            if (closed || state != State.ACTIVE || descriptor != current) {
                return;
            }
            outputFailed = !ensureFormatLocked(pcm);
            if (!outputFailed) {
                final AudioJitterBuffer.Plan plan = buffer.plan(accessUnit.getPtsUs(),
                        pcm.getDurationUs(), bufferedUsLocked(clock.nowNs()), nowMs());
                if (plan.isAccept()) {
                    for (int i = 0; i < plan.getDropOldestFrames(); i++) {
                        if (pending.pollFirst() == null) {
                            break;
                        }
                    }
                    final long ptsUs = accessUnit.getPtsUs();
                    for (int i = 0; i < plan.getSilenceFrames(); i++) {
                        enqueueLocked(new PcmFrame(silenceFrame, ptsUs, true));
                    }
                    enqueueLocked(new PcmFrame(pcm.getData(), ptsUs, false));
                    metrics.increment(PipelineMetrics.Counter.AUDIO_FRAMES);
                    if (sessionClock != null) {
                        // Audio-first sessions anchor the shared timeline; a session already
                        // anchored by video keeps its anchor untouched.
                        sessionClock.anchorIfUnset(ptsUs, clock.nowNs());
                    }
                    lastEnqueuedMs = nowMs();
                    if (plan.isStartPlayback() && !playing) {
                        playing = true;
                        sink.play();
                        notifyPlayback = true;
                    }
                    if (asyncWriter && writer == null) {
                        startWriter = true;
                    }
                }
            }
        }
        if (outputFailed) {
            fail("audio output unavailable");
            return;
        }
        if (startWriter) {
            startWriterThread();
        }
        if (notifyPlayback) {
            notifyState(State.ACTIVE, null);
        }
        if (!asyncWriter) {
            drainOnce();
        }
    }

    /**
     * Writes at most one buffered frame; the writer thread runs exactly this method in a loop.
     *
     * @return true when a frame was written
     */
    public boolean drainOnce() {
        final PcmFrame frame;
        synchronized (lock) {
            if (closed) {
                return false;
            }
            frame = pending.pollFirst();
        }
        if (frame == null) {
            return false;
        }
        return writeFrame(frame);
    }

    /**
     * Writes one buffer and advances the written-media bookkeeping the drift guard reasons about.
     *
     * @param frame the buffer to write; never null
     * @return false when the sink failed, which has already moved the session to {@code UNAVAILABLE}
     */
    private boolean writeFrame(PcmFrame frame) {
        try {
            sink.write(frame.data, 0, frame.data.length);
        } catch (AudioSinkException e) {
            fail("audio playback failed");
            return false;
        }
        synchronized (lock) {
            writtenFrames++;
            lastWrittenPtsUs = frame.ptsUs;
            recordWrittenLocked(frame);
        }
        applyDriftGuard();
        return true;
    }

    /**
     * Advances the written sample count and the stream timestamp at the end of the written audio.
     *
     * <p>A decoded buffer names its own timestamp, so it sets the end position outright; a buffer
     * the session produced itself only extends the previous end by its duration. That keeps gap fill
     * and drift corrections in the timeline instead of letting them rewind it to the next decoded
     * timestamp.</p>
     */
    private void recordWrittenLocked(PcmFrame frame) {
        final int bytesPerSampleFrame = channels * 2;
        if (bytesPerSampleFrame <= 0) {
            return;
        }
        final long samples = frame.data.length / bytesPerSampleFrame;
        final long durationUs = sampleRate > 0 ? samples * 1_000_000L / sampleRate : 0L;
        writtenSamples += samples;
        if (!frame.silence && frame.ptsUs != SessionClock.UNSET) {
            writtenEndPtsUs = frame.ptsUs + durationUs;
        } else if (writtenEndPtsUs != SessionClock.UNSET) {
            writtenEndPtsUs += durationUs;
        }
    }

    /**
     * Returns the media time buffered ahead of the playhead, in microseconds.
     *
     * <p>This is the depth the jitter buffer has to reason about, and it has two parts: what was
     * already written to the sink but not yet presented, which is what the track still holds, and
     * what is still waiting for the writer. Neither part is a frame count of the session's own
     * history: the previous model passed the cumulative frames received and therefore reported a
     * depth that only ever grew, which crossed the target once and then asked for a drop on every
     * following frame.</p>
     *
     * <p>A sink that cannot report a position is treated as having presented nothing, which
     * over-estimates the depth by at most one track buffer. Starting playback a little early is
     * recoverable through the starvation path; never starting is not.</p>
     */
    private long bufferedUsLocked(long nowNs) {
        if (sampleRate <= 0 || channels <= 0) {
            return 0L;
        }
        final long presented = sink.getPresentedFrames(nowNs);
        final long queuedFrames = writtenSamples - Math.max(0L, presented);
        long samples = Math.max(0L, queuedFrames);
        for (PcmFrame frame : pending) {
            samples += frame.data.length / (channels * 2);
        }
        return samples * 1_000_000L / sampleRate;
    }

    /**
     * Compares the audible stream position with the shared timeline, at most once per drift
     * interval.
     *
     * <p>Both sides are microseconds of the same session timeline: the sink reports which frames it
     * has presented, and the session turns that count into the stream timestamp leaving the speaker
     * by subtracting the frames still queued inside the track from the end of the last written
     * buffer. {@link SessionClock#unmap(long)} gives what the timeline says should be audible at the
     * same instant. The output buffer therefore cancels out instead of showing up as a constant
     * lag, and the comparison is a real measurement rather than a comparison of two clocks.</p>
     *
     * <p>A correction is one frame and the jitter buffer enforces the two-second cooldown, so only a
     * drift that actually exceeds the threshold moves the timeline. The guard corrects the
     * <em>movement</em> of the offset, not its absolute value: one frame of media cannot remove a
     * constant audio/video bias, so chasing one would insert a silence frame every cooldown period
     * for the whole session - the failure this guard used to have. A constant bias is reported
     * through {@link PipelineMetrics.Window#AV_OFFSET} and left to calibration.</p>
     */
    private void applyDriftGuard() {
        final SessionClock shared = sessionClock;
        if (shared == null || !shared.isAnchored()) {
            return;
        }
        final long endPtsUs;
        final long samples;
        final int rate;
        final long frameUs;
        synchronized (lock) {
            if (closed || state != State.ACTIVE) {
                return;
            }
            endPtsUs = writtenEndPtsUs;
            samples = writtenSamples;
            rate = sampleRate;
            frameUs = frameDurationUsLocked();
        }
        if (endPtsUs == SessionClock.UNSET || rate <= 0) {
            return;
        }
        final long nowMs = nowMs();
        if (lastDriftCheckMs != Long.MIN_VALUE
                && nowMs - lastDriftCheckMs < AudioJitterBuffer.DRIFT_COOLDOWN_MS) {
            return;
        }
        final long nowNs = clock.nowNs();
        final long presentedFrames = sink.getPresentedFrames(nowNs);
        if (presentedFrames < 0L) {
            return;
        }
        final long audiblePtsUs = AudioPlayhead.audiblePtsUs(endPtsUs, samples, presentedFrames,
                rate);
        final long expectedPtsUs = shared.unmap(nowNs);
        if (audiblePtsUs == SessionClock.UNSET || expectedPtsUs == SessionClock.UNSET) {
            return;
        }
        lastDriftCheckMs = nowMs;
        final long offsetUs = audiblePtsUs - expectedPtsUs;
        final long referenceUs;
        synchronized (lock) {
            lastDriftMs = offsetUs / 1_000L;
            if (referenceOffsetUs == SessionClock.UNSET) {
                referenceOffsetUs = offsetUs;
            }
            referenceUs = referenceOffsetUs;
        }
        metrics.record(PipelineMetrics.Window.AV_OFFSET, Math.abs(offsetUs) * 1_000L);
        final int correction = buffer.driftCorrectionFrames(offsetUs - referenceUs, frameUs, nowMs);
        if (correction != 0) {
            synchronized (lock) {
                referenceOffsetUs = offsetUs;
            }
        }
        applyFrameCorrection(correction);
    }

    /**
     * Applies one drift correction.
     *
     * <p>Audio that runs ahead of the timeline is delayed by one frame of silence, because the sound
     * already played cannot be taken back; audio that runs behind skips one buffered frame, which is
     * what brings the following content forward again.</p>
     */
    private void applyFrameCorrection(int correction) {
        if (correction == 0) {
            return;
        }
        synchronized (lock) {
            if (closed || state != State.ACTIVE) {
                return;
            }
            if (correction > 0) {
                if (silenceFrame != null) {
                    pending.addLast(new PcmFrame(silenceFrame, lastWrittenPtsUs, true));
                    metrics.increment(PipelineMetrics.Counter.AUDIO_UNDERRUNS);
                }
            } else if (pending.pollFirst() != null) {
                metrics.increment(PipelineMetrics.Counter.AUDIO_DROPPED);
            }
        }
    }

    /**
     * Applies a volume between zero and one; zero is mute.
     *
     * @throws IllegalArgumentException if the volume is outside the range
     */
    public void setVolume(float newVolume) {
        if (newVolume < 0f || newVolume > 1f || Float.isNaN(newVolume)) {
            throw new IllegalArgumentException("volume must be between 0 and 1");
        }
        synchronized (lock) {
            volume = newVolume;
        }
        sink.setVolume(newVolume);
    }

    /**
     * Returns the applied volume.
     */
    public float getVolume() {
        synchronized (lock) {
            return volume;
        }
    }

    /**
     * Closes the session, stops the writer and releases decoder and sink; idempotent.
     */
    public void close() {
        final Thread thread;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            state = State.CLOSED;
            pending.clear();
            thread = writer;
            writer = null;
            lock.notifyAll();
        }
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
            try {
                thread.join(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        sink.release();
        decoder.close();
        notifyState(State.CLOSED, null);
    }

    /**
     * Opens or re-opens the sink when the decoder's actual format differs from the current one.
     *
     * @return false when the sink could not be opened
     */
    private boolean ensureFormatLocked(AudioPcm pcm) {
        if (sink.isOpen() && pcm.getSampleRate() == sampleRate
                && pcm.getChannels() == channels) {
            return true;
        }
        try {
            if (sink.isOpen()) {
                sink.release();
            }
            sink.open(pcm.getSampleRate(), pcm.getChannels());
            sink.setVolume(volume);
            sampleRate = pcm.getSampleRate();
            channels = pcm.getChannels();
            // A new track counts its presented frames from zero, so the written counters restart
            // with it; the written timestamps are re-derived from the next decoded frame.
            writtenSamples = 0L;
            writtenEndPtsUs = SessionClock.UNSET;
            referenceOffsetUs = SessionClock.UNSET;
            final int samples = descriptor != null ? descriptor.getFrameSamples() : 1024;
            silenceFrame = new byte[samples * channels * 2];
            return true;
        } catch (AudioSinkException e) {
            return false;
        }
    }

    private void enqueueLocked(PcmFrame frame) {
        if (frame == null || frame.data == null) {
            return;
        }
        while (pending.size() >= MAX_PENDING_FRAMES) {
            pending.pollFirst();
        }
        pending.addLast(frame);
        // The writer sleeps while the queue is empty, so the enqueue is what has to wake it;
        // without this the writer only noticed a new frame on its next poll, and the poll interval
        // was a hole in the output buffer at every frame.
        lock.notifyAll();
    }

    private void startWriterThread() {
        synchronized (lock) {
            if (closed || writer != null) {
                return;
            }
            writer = new Thread(this::writerLoop, "lss-audio-play");
            writer.setDaemon(true);
            writer.start();
        }
    }

    /**
     * Writes buffered frames until the queue is empty, then sleeps until an enqueue wakes it.
     *
     * <p>The wait happens under the same lock that made the queue look empty, so an enqueue that
     * lands between the poll and the wait cannot leave a frame behind a sleeping writer. Only the
     * starvation check needs a timer, because it is the one decision that time alone changes.</p>
     */
    private void writerLoop() {
        while (true) {
            final PcmFrame frame;
            synchronized (lock) {
                if (closed) {
                    return;
                }
                frame = pending.pollFirst();
            }
            if (frame != null) {
                if (!writeFrame(frame)) {
                    return;
                }
                continue;
            }
            boolean underrun = false;
            synchronized (lock) {
                if (closed) {
                    return;
                }
                if (!pending.isEmpty()) {
                    continue;
                }
                final long currentMs = nowMs();
                underrun = playing && silenceFrame != null && lastEnqueuedMs != Long.MIN_VALUE
                        && currentMs - lastEnqueuedMs >= UNDERRUN_QUIET_MS;
                if (underrun) {
                    buffer.onUnderrun(frameDurationUsLocked());
                }
                try {
                    if (underrun) {
                        lock.wait(WRITER_WAIT_MS);
                    } else {
                        lock.wait();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (underrun) {
                // Silence keeps the output fed; dropping samples instead would speed the track up.
                final byte[] silence;
                final long ptsUs;
                synchronized (lock) {
                    silence = silenceFrame;
                    ptsUs = lastWrittenPtsUs;
                }
                if (silence != null) {
                    metrics.increment(PipelineMetrics.Counter.AUDIO_UNDERRUNS);
                    if (!writeFrame(new PcmFrame(silence, ptsUs, true))) {
                        return;
                    }
                }
            }
        }
    }

    private long frameDurationUsLocked() {
        return descriptor != null ? descriptor.getFrameDurationUs() : 0L;
    }

    /**
     * Returns the injected clock in milliseconds, which is what the jitter buffer reasons about.
     */
    private long nowMs() {
        return clock.nowNs() / 1_000_000L;
    }

    private void fail(String reason) {
        final boolean wasOpen;
        synchronized (lock) {
            if (closed || state == State.UNAVAILABLE) {
                return;
            }
            state = State.UNAVAILABLE;
            unavailableReason = reason;
            pending.clear();
            playing = false;
            wasOpen = true;
        }
        if (wasOpen) {
            sink.release();
            decoder.close();
        }
        notifyState(State.UNAVAILABLE, reason);
    }

    private void notifyState(State newState, String reason) {
        if (listener != null) {
            listener.onStateChanged(newState, reason);
        }
    }

    @Override
    public String toString() {
        synchronized (lock) {
            return "ClientAudioSession{" + state + ", written=" + writtenFrames + "}";
        }
    }

    /**
     * Returns whether the pending queue is empty; test and diagnostics helper.
     */
    public boolean isPendingEmpty() {
        synchronized (lock) {
            return pending.isEmpty();
        }
    }

    /**
     * Returns the number of buffered frames; test and diagnostics helper.
     */
    public int getPendingFrames() {
        synchronized (lock) {
            return pending.size();
        }
    }

    /**
     * Returns the last measured audio/video offset in milliseconds; positive means the audible
     * audio runs ahead of the shared timeline.
     */
    public long getLastDriftMs() {
        synchronized (lock) {
            return lastDriftMs;
        }
    }

    /**
     * Returns the silence frame length in bytes, or zero before a descriptor is applied.
     */
    public int getSilenceFrameLength() {
        synchronized (lock) {
            return silenceFrame == null ? 0 : silenceFrame.length;
        }
    }

    /**
     * Returns the state of the writer thread, or null when none is running.
     *
     * <p>Test and diagnostics helper: an idle writer has to be {@code WAITING} on the session lock,
     * because that is how "sleeps until the next enqueue" is observable without a timing
     * assertion.</p>
     */
    Thread.State getWriterState() {
        synchronized (lock) {
            return writer == null ? null : writer.getState();
        }
    }

    /**
     * Returns a copy of the buffered silence frame's length for assertions about zero content.
     */
    boolean isSilenceFrameAllZero() {
        synchronized (lock) {
            return silenceFrame != null && Arrays.equals(silenceFrame,
                    new byte[silenceFrame.length]);
        }
    }
}
