package com.obby.android.localscreenshare.core.audio;

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
     * Writer wait between two empty polls, in milliseconds.
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

    private final AudioJitterBuffer buffer = new AudioJitterBuffer();

    private final Object lock = new Object();

    private final ArrayDeque<byte[]> pending = new ArrayDeque<>();

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

    private long framesReceived;

    private long writtenFrames;

    private Thread writer;

    /**
     * Creates a session with the production clock and a writer thread.
     */
    public ClientAudioSession(AudioDecoder decoder, AudioSink sink, Listener listener) {
        this(decoder, sink, listener, NanoClock.MONOTONIC, true);
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
        if (decoder == null) {
            throw new IllegalArgumentException("decoder must not be null");
        }
        if (sink == null) {
            throw new IllegalArgumentException("sink must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.decoder = decoder;
        this.sink = sink;
        this.listener = listener;
        this.clock = clock;
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
     * Returns the number of PCM buffers written to the sink.
     */
    public long getWrittenFrames() {
        synchronized (lock) {
            return writtenFrames;
        }
    }

    /**
     * Returns the playhead the sink reports, or a negative value when unknown.
     */
    public long getPlayheadNs() {
        return sink.getPlayheadNs();
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
                framesReceived = 0L;
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
                        pcm.getDurationUs(), (int) Math.min(Integer.MAX_VALUE, framesReceived),
                        nowMs());
                framesReceived++;
                if (plan.isAccept()) {
                    for (int i = 0; i < plan.getDropOldestFrames(); i++) {
                        if (pending.pollFirst() == null) {
                            break;
                        }
                    }
                    for (int i = 0; i < plan.getSilenceFrames(); i++) {
                        enqueueLocked(silenceFrame);
                    }
                    enqueueLocked(pcm.getData());
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
        final byte[] frame;
        synchronized (lock) {
            if (closed) {
                return false;
            }
            frame = pending.pollFirst();
        }
        if (frame == null) {
            return false;
        }
        try {
            sink.write(frame, 0, frame.length);
        } catch (AudioSinkException e) {
            fail("audio playback failed");
            return false;
        }
        synchronized (lock) {
            writtenFrames++;
        }
        return true;
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
            final int samples = descriptor != null ? descriptor.getFrameSamples() : 1024;
            silenceFrame = new byte[samples * channels * 2];
            return true;
        } catch (AudioSinkException e) {
            return false;
        }
    }

    private void enqueueLocked(byte[] frame) {
        if (frame == null) {
            return;
        }
        while (pending.size() >= MAX_PENDING_FRAMES) {
            pending.pollFirst();
        }
        pending.addLast(frame);
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

    private void writerLoop() {
        while (true) {
            synchronized (lock) {
                if (closed) {
                    return;
                }
            }
            if (drainOnce()) {
                continue;
            }
            boolean underrun = false;
            synchronized (lock) {
                if (closed) {
                    return;
                }
                final long currentMs = nowMs();
                underrun = playing && silenceFrame != null && lastEnqueuedMs != Long.MIN_VALUE
                        && currentMs - lastEnqueuedMs >= UNDERRUN_QUIET_MS;
                if (underrun) {
                    buffer.onUnderrun(frameDurationUsLocked());
                }
                try {
                    lock.wait(WRITER_WAIT_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (underrun) {
                // Silence keeps the output fed; dropping samples instead would speed the track up.
                final byte[] frame;
                synchronized (lock) {
                    frame = silenceFrame;
                }
                if (frame != null) {
                    try {
                        sink.write(frame, 0, frame.length);
                    } catch (AudioSinkException e) {
                        fail("audio playback failed");
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
     * Returns the silence frame length in bytes, or zero before a descriptor is applied.
     */
    public int getSilenceFrameLength() {
        synchronized (lock) {
            return silenceFrame == null ? 0 : silenceFrame.length;
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
