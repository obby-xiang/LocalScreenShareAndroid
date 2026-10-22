package com.obby.android.localscreenshare.core.support;

import com.obby.android.localscreenshare.core.audio.AudioCaptureException;
import com.obby.android.localscreenshare.core.audio.AudioEncoderBridge;
import com.obby.android.localscreenshare.core.audio.EncodedAudioFrame;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Scripted {@link AudioEncoderBridge} for JVM tests.
 *
 * <p>One queued PCM frame produces exactly one encoded frame whose payload is derived from its
 * timestamp, so a test can assert framing and PTS propagation without an AAC codec. The
 * AudioSpecificConfig is either available right after configuration (a codec that knows it up
 * front) or after the first queued frame (the platform's output-format change).</p>
 */
public final class FakeAudioEncoderBridge implements AudioEncoderBridge {

    private final Deque<EncodedAudioFrame> outputs = new ArrayDeque<>();

    private final List<Long> queuedPtsUs = new ArrayList<>();

    private final boolean configImmediately;

    private final byte[] config;

    private AudioCaptureException configureFailure;

    private AudioCaptureException queueFailure;

    private boolean configured;

    private boolean closed;

    private int configureCount;

    private int closeCount;

    /**
     * Creates a bridge that reports its AudioSpecificConfig immediately.
     */
    public FakeAudioEncoderBridge() {
        this(true);
    }

    /**
     * Creates a bridge whose AudioSpecificConfig appears with the first encoded frame when
     * {@code configImmediately} is false, mirroring the platform encoder.
     */
    public FakeAudioEncoderBridge(boolean configImmediately) {
        this.configImmediately = configImmediately;
        this.config = new byte[]{0x12, 0x10};
    }

    /**
     * Makes {@link #configure(int, int, int)} fail.
     */
    public FakeAudioEncoderBridge failConfigure(AudioCaptureException failure) {
        this.configureFailure = failure;
        return this;
    }

    /**
     * Makes {@link #queueInput(short[], int, int, long)} fail.
     */
    public FakeAudioEncoderBridge failQueue(AudioCaptureException failure) {
        this.queueFailure = failure;
        return this;
    }

    public List<Long> getQueuedPtsUs() {
        return queuedPtsUs;
    }

    public boolean isConfigured() {
        return configured;
    }

    public boolean isClosed() {
        return closed;
    }

    public int getConfigureCount() {
        return configureCount;
    }

    public int getCloseCount() {
        return closeCount;
    }

    @Override
    public void configure(int sampleRate, int channels, int bitrateBps)
            throws AudioCaptureException {
        if (configureFailure != null) {
            throw configureFailure;
        }
        // A configure creates a new codec on the real bridge, so a closed bridge becomes usable
        // again; keeping the flag would make a pipeline restart fail on its first queued frame.
        closed = false;
        configured = true;
        configureCount++;
    }

    @Override
    public void queueInput(short[] pcm, int offset, int count, long ptsUs)
            throws AudioCaptureException {
        if (queueFailure != null) {
            throw queueFailure;
        }
        if (closed) {
            throw new IllegalStateException("bridge is closed");
        }
        queuedPtsUs.add(ptsUs);
        final byte[] payload = new byte[]{(byte) (ptsUs & 0xFF), (byte) 0x21};
        outputs.add(new EncodedAudioFrame(ptsUs, payload));
    }

    @Override
    public EncodedAudioFrame dequeueOutput() {
        if (closed) {
            return null;
        }
        return outputs.poll();
    }

    @Override
    public byte[] getAudioSpecificConfig() {
        if (configImmediately) {
            return config.clone();
        }
        return queuedPtsUs.isEmpty() ? null : config.clone();
    }

    @Override
    public void close() {
        closed = true;
        closeCount++;
    }
}
