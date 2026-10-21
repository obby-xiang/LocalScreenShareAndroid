package com.obby.android.localscreenshare.core.codec.mediacodec;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.view.Surface;

import com.obby.android.localscreenshare.core.model.ColorInfo;

import java.nio.ByteBuffer;

/**
 * Android {@link MediaCodec} implementation of {@link MediaCodecEncoderBridge}.
 *
 * <p><b>Lifecycle.</b> The codec is created by
 * {@link AndroidMediaCodecBridgeFactory#createEncoder(String)} and handed to this bridge in its
 * constructor. {@link #configure(EncoderFormat)} builds the {@link MediaFormat} and configures the
 * codec, then {@link #createInputSurface()} creates the encoder's input surface. The adapter performs
 * both during construction; {@link #start()} only starts the codec and begins draining, and never
 * reconfigures or recreates the surface.</p>
 *
 * <p>{@link #start()}, {@link #stop()} and {@link #release()} are idempotent. {@link #release()}
 * owns and releases the input surface created by {@link #createInputSurface()} and then the codec
 * itself; the caller must not release either separately.</p>
 *
 * <p><b>Dequeue semantics.</b> {@link #dequeueOutput(long)} maps {@code INFO_TRY_AGAIN_LATER} and any
 * other non-buffer result to {@code null} (the timeout elapsed with no sample), a
 * {@code INFO_OUTPUT_FORMAT_CHANGED} result to an {@link EncodedSample} with
 * {@code formatChanged=true}, and an ordinary output buffer to an {@link EncodedSample} carrying the
 * copied bytes, key-frame / codec-config / end-of-stream flags and presentation timestamp. The
 * underlying output buffer is always released with {@code render=false} before returning a sample.</p>
 *
 * <p><b>Parameter updates.</b> {@link #requestSyncFrame()} and {@link #updateBitrate(int)} are
 * {@code synchronized} so concurrent parameter requests are serialized into one
 * {@link MediaCodec#setParameters(Bundle)} call at a time; they never reconfigure the codec.</p>
 */
public final class AndroidMediaCodecEncoderBridge implements MediaCodecEncoderBridge {

    private static final String CSD_0_KEY = "csd-0";

    private static final String CSD_1_KEY = "csd-1";

    // Public since API 29 as MediaFormat.KEY_MAX_FPS_TO_ENCODER; the same string works on older
    // releases, where the key existed privately, so the literal keeps minSdk 25 compiling and
    // behaving identically. The value is a float, and an encoder that does not know the key ignores
    // it instead of failing configuration.
    private static final String MAX_FPS_TO_ENCODER_KEY = "max-fps-to-encoder";

    private final String mime;
    private final MediaCodec codec;

    private Surface inputSurface;
    private MediaFormat outputFormat;
    private boolean started;
    private boolean released;

    /**
     * Creates the bridge around an already-created encoder codec. The codec is neither configured nor
     * started; call {@link #configure(EncoderFormat)} first.
     *
     * @param mime the media type used to build the {@link MediaFormat}; must not be null or blank
     * @param codec the encoder codec created by the factory; must not be null
     */
    public AndroidMediaCodecEncoderBridge(String mime, MediaCodec codec) {
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
     * Builds the encoder {@link MediaFormat} and configures the codec. The format carries only the
     * requested size, bitrate, frame rate, I-frame interval in seconds and surface color format; no
     * profile, level, B-frame, HDR or temporal-layer keys are set.
     *
     * <p>The frame rate is written twice, for two different purposes. {@code KEY_FRAME_RATE} is a
     * target the rate controller uses and does not cap anything; {@code max-fps-to-encoder} asks a
     * surface-input encoder to drop source frames above that rate, which is the only frame-rate cap
     * available while a virtual display renders straight into the input surface. Encoders that do
     * not implement the key ignore it, so the second write can only help.</p>
     *
     * <p>A codec that rejects the unlisted key is retried once without it: a vendor encoder may
     * refuse any key it does not recognise, and a stream that would otherwise run must not fail
     * because an optional cap was requested.</p>
     */
    @Override
    public void configure(EncoderFormat format) {
        configureWithFallback(format,
                candidate -> codec.configure(createMediaFormat(candidate), null, null,
                        MediaCodec.CONFIGURE_FLAG_ENCODE),
                codec::reset);
    }

    /**
     * Applies one format and retries once without the frame-rate cap if the codec rejects it.
     *
     * <p>A vendor encoder may refuse any key it does not recognise, and a stream that would
     * otherwise run must not fail because an optional cap was requested. The reset runs between the
     * attempts because a failed configuration leaves the codec in a state that accepts no second
     * configure. Package private so the fallback is testable without a real codec.</p>
     *
     * @param format      format to apply; the cap is dropped on the retry
     * @param sink        applies one format to the codec
     * @param resetAction returns the codec to its uninitialized state
     */
    static void configureWithFallback(EncoderFormat format, FormatSink sink, Runnable resetAction) {
        if (format == null || sink == null || resetAction == null) {
            throw new IllegalArgumentException("format, sink and resetAction must not be null");
        }
        try {
            sink.configure(format);
        } catch (IllegalArgumentException | IllegalStateException e) {
            if (format.getMaxFpsToEncoder() <= 0f) {
                throw e;
            }
            resetAction.run();
            sink.configure(format.withoutMaxFpsToEncoder());
        }
    }

    /**
     * Applies one {@link EncoderFormat}; implemented by the codec call and by tests.
     */
    interface FormatSink {

        /**
         * Applies one format.
         *
         * @param format format to apply
         */
        void configure(EncoderFormat format);
    }

    /**
     * Builds one encoder format, including the optional frame-rate cap.
     */
    private MediaFormat createMediaFormat(EncoderFormat format) {
        MediaFormat mediaFormat = MediaFormat.createVideoFormat(mime, format.getWidth(),
                format.getHeight());
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, format.getBitrateBps());
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, format.getFrameRate());
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, format.getKeyFrameIntervalSeconds());
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        if (format.getMaxFpsToEncoder() > 0f) {
            mediaFormat.setFloat(MAX_FPS_TO_ENCODER_KEY, format.getMaxFpsToEncoder());
        }
        return mediaFormat;
    }

    /**
     * Creates the encoder's input surface. The bridge owns the returned surface and releases it in
     * {@link #release()}.
     *
     * @throws IllegalStateException if the codec cannot produce a surface
     */
    @Override
    public Surface createInputSurface() {
        Surface surface = codec.createInputSurface();
        if (surface == null) {
            throw new IllegalStateException("encoder failed to create an input surface");
        }
        this.inputSurface = surface;
        return surface;
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
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
        codec.release();
    }

    @Override
    public synchronized void requestSyncFrame() {
        Bundle params = new Bundle();
        params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
        codec.setParameters(params);
    }

    @Override
    public synchronized void updateBitrate(int bitrateBps) {
        Bundle params = new Bundle();
        params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrateBps);
        codec.setParameters(params);
    }

    @Override
    public EncodedSample dequeueOutput(long timeoutUs) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int index = codec.dequeueOutputBuffer(info, timeoutUs);
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            this.outputFormat = codec.getOutputFormat();
            return new EncodedSample(null, 0L, false, false, true, false);
        }
        if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
            return null;
        }
        if (index < 0) {
            return null;
        }
        ByteBuffer outputBuffer = codec.getOutputBuffer(index);
        if (outputBuffer == null) {
            codec.releaseOutputBuffer(index, false);
            return null;
        }
        byte[] data = new byte[info.size];
        outputBuffer.get(data);
        boolean keyFrame = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        boolean codecConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
        boolean endOfStream = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
        codec.releaseOutputBuffer(index, false);
        return new EncodedSample(data, info.presentationTimeUs, keyFrame, codecConfig, false,
                endOfStream);
    }

    /**
     * Returns the latest output format, or {@code null} before the first format change. The CSD blobs
     * are copied defensively; color is always {@link ColorInfo#UNSPECIFIED}.
     */
    @Override
    public EncoderStreamFormat getOutputFormat() {
        MediaFormat format = this.outputFormat;
        if (format == null) {
            return null;
        }
        byte[] csd0 = copyCsd(format, CSD_0_KEY);
        byte[] csd1 = copyCsd(format, CSD_1_KEY);
        int width = getIntegerOrZero(format, MediaFormat.KEY_WIDTH);
        int height = getIntegerOrZero(format, MediaFormat.KEY_HEIGHT);
        return new EncoderStreamFormat(csd0, csd1, width, height, ColorInfo.UNSPECIFIED);
    }

    private static byte[] copyCsd(MediaFormat format, String key) {
        ByteBuffer buffer = format.getByteBuffer(key);
        if (buffer == null) {
            return null;
        }
        ByteBuffer copy = buffer.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }

    private static int getIntegerOrZero(MediaFormat format, String key) {
        return format.containsKey(key) ? format.getInteger(key) : 0;
    }
}
