package com.obby.android.localscreenshare.core.codec.mediacodec;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.view.Surface;

import java.nio.ByteBuffer;

/**
 * Android {@link MediaCodec} implementation of {@link MediaCodecDecoderBridge}.
 *
 * <p><b>Lifecycle.</b> The codec is created by
 * {@link AndroidMediaCodecBridgeFactory#createDecoder(String)} and handed to this bridge in its
 * constructor. {@link #configure(DecoderFormat)} builds an Annex-B {@link MediaFormat} from the
 * width, height and CSD blobs and configures the codec against the render surface carried by
 * {@link DecoderFormat#getOutputSurface()}. Output surface is supplied only through
 * {@link DecoderFormat}; any surface-availability change requires re-creating the decoder rather
 * than mutating this bridge.</p>
 *
 * <p>{@link #start()}, {@link #stop()} and {@link #release()} are idempotent. The render surface is
 * caller-owned and is never released by this bridge; {@link #release()} releases only the codec.</p>
 *
 * <p><b>Dequeue semantics.</b> {@link #dequeueOutput(long)} maps {@code INFO_TRY_AGAIN_LATER} and any
 * other non-buffer result to {@code null} (the timeout elapsed with no output), a
 * {@code INFO_OUTPUT_FORMAT_CHANGED} result to a {@link DecodedOutput} with
 * {@code formatChanged=true} and no buffer index, and an ordinary output buffer to a
 * {@link DecodedOutput} carrying its index, presentation timestamp and end-of-stream flag. The
 * buffer is left unreleased, so the frame reaches the surface only through
 * {@link #present(int, long)} and is dropped through {@link #discard(int)}.</p>
 */
public final class AndroidMediaCodecDecoderBridge implements MediaCodecDecoderBridge {

    private static final String CSD_0_KEY = "csd-0";

    private static final String CSD_1_KEY = "csd-1";

    /**
     * Decoder-only optional key, {@code MediaFormat.KEY_LOW_LATENCY} since API 30. The literal is
     * used, like the encoder's {@code max-fps-to-encoder} key, so a minSdk 25 build carries no
     * platform-constant dependency.
     */
    private static final String LOW_LATENCY_KEY = "low-latency";

    /**
     * Requested scheduler priority, {@code MediaFormat.KEY_PRIORITY}; zero means realtime.
     */
    private static final String PRIORITY_KEY = "priority";

    private static final int PRIORITY_REALTIME = 0;

    private final String mime;
    private final MediaCodec codec;

    private boolean started;
    private boolean released;

    private LowLatencyState lowLatencyState = LowLatencyState.NOT_REQUESTED;

    /**
     * Applies one decoder configuration.
     *
     * <p>Flags only: the applier owns the {@link MediaFormat}, so the fallback ladder stays a pure
     * JVM unit and never exposes a platform type to a test.</p>
     */
    interface FormatApplier {

        /**
         * Configures the codec, optionally carrying the two low-latency keys.
         */
        void configure(boolean lowLatency, boolean priority);
    }

    /**
     * Creates the bridge around an already-created decoder codec. The codec is neither configured nor
     * started; call {@link #configure(DecoderFormat)} first.
     *
     * @param mime the media type used to build the {@link MediaFormat}; must not be null or blank
     * @param codec the decoder codec created by the factory; must not be null
     */
    public AndroidMediaCodecDecoderBridge(String mime, MediaCodec codec) {
        if (mime == null || mime.trim().isEmpty()) {
            throw new IllegalArgumentException("mime must not be null or blank");
        }
        if (codec == null) {
            throw new IllegalArgumentException("codec must not be null");
        }
        this.mime = mime;
        this.codec = codec;
    }

    /**
     * Builds the decoder {@link MediaFormat} and configures the codec against the render surface
     * carried by {@link DecoderFormat#getOutputSurface()}. A missing surface is a fatal
     * configuration error.
     *
     * <p>On API 30 and later the low-latency and realtime-priority keys are requested; a codec
     * that rejects them is reconfigured one step at a time, because a vendor decoder may support
     * the low-latency mode without accepting the priority hint, or neither. The resulting state is
     * kept for {@link #getLowLatencyState()}.</p>
     *
     * @throws IllegalStateException if the format carries no output surface
     */
    @Override
    public void configure(DecoderFormat format) {
        if (format == null) {
            throw new IllegalArgumentException("format must not be null");
        }
        Surface surface = format.getOutputSurface();
        if (surface == null) {
            throw new IllegalStateException("output surface must not be null");
        }
        boolean requestLowLatency = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
        lowLatencyState = configureWithFallback(requestLowLatency,
                (lowLatency, priority) -> codec.configure(
                        buildMediaFormat(format, lowLatency, priority), surface, null, 0),
                codec::reset);
    }

    /**
     * Returns the state recorded by the last {@link #configure(DecoderFormat)}.
     */
    @Override
    public LowLatencyState getLowLatencyState() {
        return lowLatencyState;
    }

    /**
     * Tries the requested optional keys in three steps and reports which ones survived.
     *
     * <p>The order is fixed: both keys, then the low-latency key alone, then neither. A rejected
     * attempt resets the codec before the next one, because the platform requires a reset before a
     * failed configuration can be retried. A failure unrelated to the optional keys is rethrown
     * unchanged: the last attempt carries no optional key, so its failure is the caller's format
     * error, not a vendor rejection.</p>
     *
     * @param requestLowLatency whether the platform is new enough to ask at all
     * @param applier           performs one configuration attempt
     * @param resetAction       resets the codec between attempts that carried an optional key
     * @return which keys took effect
     */
    static LowLatencyState configureWithFallback(boolean requestLowLatency, FormatApplier applier,
                                                 Runnable resetAction) {
        if (!requestLowLatency) {
            applier.configure(false, false);
            return LowLatencyState.NOT_REQUESTED;
        }
        try {
            applier.configure(true, true);
            return LowLatencyState.APPLIED;
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            resetAction.run();
        }
        try {
            applier.configure(true, false);
            return LowLatencyState.APPLIED_WITHOUT_PRIORITY;
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            resetAction.run();
        }
        applier.configure(false, false);
        return LowLatencyState.REJECTED;
    }

    @Override
    public void start() {
        if (started) {
            return;
        }
        codec.start();
        started = true;
    }

    @Override
    public void stop() {
        if (!started) {
            return;
        }
        codec.stop();
        started = false;
    }

    @Override
    public void release() {
        if (released) {
            return;
        }
        released = true;
        codec.release();
    }

    @Override
    public int dequeueInputBuffer(long timeoutUs) {
        return codec.dequeueInputBuffer(timeoutUs);
    }

    @Override
    public void queueInputBuffer(int index, byte[] data, long ptsUs) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        ByteBuffer inputBuffer = codec.getInputBuffer(index);
        inputBuffer.clear();
        inputBuffer.put(data);
        codec.queueInputBuffer(index, 0, data.length, ptsUs, 0);
    }

    @Override
    public DecodedOutput dequeueOutput(long timeoutUs) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int index = codec.dequeueOutputBuffer(info, timeoutUs);
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            return new DecodedOutput(DecodedOutput.NO_BUFFER, 0L, true, false);
        }
        if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
            return null;
        }
        if (index < 0) {
            return null;
        }
        boolean endOfStream = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
        return new DecodedOutput(index, info.presentationTimeUs, false, endOfStream);
    }

    @Override
    public void present(int index, long renderTimestampNs) {
        // A timestamp in the past asks the platform to show the frame as soon as possible.
        codec.releaseOutputBuffer(index, renderTimestampNs);
    }

    @Override
    public void discard(int index) {
        codec.releaseOutputBuffer(index, false);
    }

    /**
     * Flushes the codec when it is started; a codec that never started holds no buffers, so the
     * call is a no-op there. Configuration survives, which is what lets the caller resume on a
     * random-access point without rebuilding the decoder.
     */
    @Override
    public void flush() {
        if (!started || released) {
            return;
        }
        codec.flush();
    }

    private static void setCsd(MediaFormat format, String key, byte[] csd) {
        if (csd != null) {
            format.setByteBuffer(key, ByteBuffer.wrap(csd));
        }
    }

    /**
     * Builds one configuration attempt; the optional keys are absent unless requested.
     *
     * <p>Every attempt gets its own format so a rejected key cannot survive into the next one - a
     * {@link MediaFormat} is mutable and the platform reads it only when the codec is configured.</p>
     */
    private MediaFormat buildMediaFormat(DecoderFormat format, boolean lowLatency,
                                         boolean priority) {
        MediaFormat mediaFormat = MediaFormat.createVideoFormat(mime, format.getWidth(),
                format.getHeight());
        setCsd(mediaFormat, CSD_0_KEY, format.getCsd0());
        setCsd(mediaFormat, CSD_1_KEY, format.getCsd1());
        if (lowLatency) {
            mediaFormat.setInteger(LOW_LATENCY_KEY, 1);
        }
        if (priority) {
            mediaFormat.setInteger(PRIORITY_KEY, PRIORITY_REALTIME);
        }
        return mediaFormat;
    }
}
