package com.obby.android.localscreenshare.core.audio;

import com.obby.android.localscreenshare.core.model.AudioAccessUnit;
import com.obby.android.localscreenshare.core.model.AudioDescriptor;
import com.obby.android.localscreenshare.core.util.NanoClock;

import java.util.List;

/**
 * Capture loop: reads PCM from an {@link AudioCaptureSource}, frames and encodes it with an
 * {@link AacEncoder}, and reports descriptors and access units to a {@link Listener}.
 *
 * <p>The pipeline owns exactly one worker thread and never waits for a consumer: the listener is
 * expected to hand the frames to a fan-out hub that copies them into per-client queues. A failure
 * in the capture or encode stage is reported once through
 * {@link Listener#onUnavailable(AudioCaptureException)} and stops the worker; it never propagates
 * to the caller and never touches the video stream.</p>
 *
 * <p><b>Idle reads.</b> A source may return zero samples (the device is muted, or another
 * component holds the capture). That is not a failure: the loop waits a few milliseconds and reads
 * again, so an idle capture costs almost no CPU and a stalled device does not spin.</p>
 *
 * <p><b>Timestamps.</b> The worker anchors frame zero at the monotonic time the capture opened and
 * lets {@link AacEncoder} propagate it by sample count; the platform timestamp from
 * {@link AudioCaptureSource#getTimestamp()} is therefore not needed for framing and is only used
 * to detect a device that restarted its capture position.</p>
 */
public final class AudioPipeline {

    /**
     * Duration of one read block in milliseconds.
     */
    private static final int READ_BLOCK_MS = 10;

    /**
     * Wait between two reads that produced no samples, in milliseconds.
     */
    private static final long IDLE_WAIT_MS = 5L;

    /**
     * How long {@link #stop()} waits for the worker thread to exit, in milliseconds.
     */
    private static final long STOP_JOIN_MS = 1_000L;

    /**
     * Receiver of the audio a pipeline produces.
     */
    public interface Listener {

        /**
         * Reports a new decode configuration; always precedes the first access unit of its epoch.
         */
        void onDescriptor(AudioDescriptor descriptor);

        /**
         * Reports one encoded frame.
         */
        void onAccessUnit(AudioAccessUnit accessUnit);

        /**
         * Reports that the pipeline stopped because of a failure.
         */
        void onUnavailable(AudioCaptureException failure);
    }

    private final AudioCaptureSource source;

    private final AacEncoder encoder;

    private final Listener listener;

    private final NanoClock clock;

    private volatile boolean running;

    private Thread worker;

    private AudioDescriptor publishedDescriptor;

    /**
     * Creates a pipeline.
     *
     * @param source   capture device; required
     * @param encoder  AAC encoder; required
     * @param listener receiver of descriptors, frames and failures; required
     * @throws IllegalArgumentException if any argument is null
     */
    public AudioPipeline(AudioCaptureSource source, AacEncoder encoder, Listener listener) {
        this(source, encoder, listener, NanoClock.MONOTONIC);
    }

    /**
     * Creates a pipeline with an injectable clock.
     *
     * @param source   capture device; required
     * @param encoder  AAC encoder; required
     * @param listener receiver of descriptors, frames and failures; required
     * @param clock    clock used to anchor the first frame; required
     * @throws IllegalArgumentException if any argument is null
     */
    public AudioPipeline(AudioCaptureSource source, AacEncoder encoder, Listener listener,
                         NanoClock clock) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        if (encoder == null) {
            throw new IllegalArgumentException("encoder must not be null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.source = source;
        this.encoder = encoder;
        this.listener = listener;
        this.clock = clock;
    }

    /**
     * Opens the capture device, configures the encoder and starts the worker thread.
     *
     * @throws AudioCaptureException if the device cannot be opened or the encoder cannot be
     *                               configured; the pipeline then stays stopped
     */
    public void start(int configEpoch, int sampleRate, int channels, int bitrateBps)
            throws AudioCaptureException {
        if (running) {
            return;
        }
        source.open(sampleRate, channels);
        final long startNs = clock.nowNs();
        try {
            encoder.open(configEpoch, sampleRate, channels, bitrateBps,
                    Math.max(0L, startNs / 1_000L));
        } catch (AudioCaptureException | RuntimeException e) {
            source.close();
            throw e;
        }
        publishDescriptorIfNew();
        running = true;
        final short[] block = new short[Math.max(1, sampleRate / (1_000 / READ_BLOCK_MS))
                * channels];
        worker = new Thread(() -> runLoop(block), "lss-audio-capture");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Stops the worker and releases the device and the encoder; idempotent.
     */
    public void stop() {
        running = false;
        final Thread thread = worker;
        worker = null;
        if (thread != null) {
            thread.interrupt();
            if (thread != Thread.currentThread()) {
                try {
                    thread.join(STOP_JOIN_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        source.close();
        encoder.close();
    }

    /**
     * Returns whether the worker is running.
     */
    public boolean isRunning() {
        return running;
    }

    private void runLoop(short[] block) {
        while (running) {
            final int read;
            try {
                read = source.read(block, 0, block.length);
            } catch (AudioCaptureException e) {
                fail(e);
                return;
            } catch (RuntimeException e) {
                fail(new AudioCaptureException(AudioCaptureException.Reason.INTERNAL,
                        "capture read failed", e));
                return;
            }
            if (!running) {
                return;
            }
            if (read <= 0) {
                if (!sleepIdle()) {
                    return;
                }
                continue;
            }
            try {
                final List<AudioAccessUnit> units = encoder.encode(block, 0, read);
                publishDescriptorIfNew();
                for (AudioAccessUnit unit : units) {
                    listener.onAccessUnit(unit);
                }
            } catch (AudioCaptureException e) {
                fail(e);
                return;
            } catch (RuntimeException e) {
                fail(new AudioCaptureException(AudioCaptureException.Reason.INTERNAL,
                        "audio encode or delivery failed", e));
                return;
            }
        }
    }

    private boolean sleepIdle() {
        try {
            Thread.sleep(IDLE_WAIT_MS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void publishDescriptorIfNew() {
        final AudioDescriptor descriptor = encoder.getDescriptor();
        if (descriptor == null || descriptor == publishedDescriptor) {
            return;
        }
        publishedDescriptor = descriptor;
        listener.onDescriptor(descriptor);
    }

    private void fail(AudioCaptureException failure) {
        running = false;
        try {
            listener.onUnavailable(failure);
        } catch (RuntimeException ignored) {
            // A failing consumer must not turn an audio failure into a process failure.
        }
    }
}
