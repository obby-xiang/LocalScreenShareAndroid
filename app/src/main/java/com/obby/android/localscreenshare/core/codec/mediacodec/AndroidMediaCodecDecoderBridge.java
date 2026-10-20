package com.obby.android.localscreenshare.core.codec.mediacodec;

import android.media.MediaCodec;
import android.media.MediaFormat;
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
 * {@code formatChanged=true}, and an ordinary output buffer to a {@link DecodedOutput} carrying the
 * presentation timestamp and the end-of-stream flag. Each buffer is released with {@code render=true}
 * so the frame is rendered to the configured surface.</p>
 */
public final class AndroidMediaCodecDecoderBridge implements MediaCodecDecoderBridge {

    private static final String CSD_0_KEY = "csd-0";

    private static final String CSD_1_KEY = "csd-1";

    private final String mime;
    private final MediaCodec codec;

    private boolean started;
    private boolean released;

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
     * Builds the decoder {@link MediaFormat} from the width, height and CSD blobs and configures the
     * codec against the render surface carried by {@link DecoderFormat#getOutputSurface()}. A missing
     * surface is a fatal configuration error.
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
        MediaFormat mediaFormat = MediaFormat.createVideoFormat(mime, format.getWidth(),
                format.getHeight());
        setCsd(mediaFormat, CSD_0_KEY, format.getCsd0());
        setCsd(mediaFormat, CSD_1_KEY, format.getCsd1());
        codec.configure(mediaFormat, surface, null, 0);
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
            return new DecodedOutput(0L, true, false);
        }
        if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
            return null;
        }
        if (index < 0) {
            return null;
        }
        boolean endOfStream = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
        codec.releaseOutputBuffer(index, true);
        return new DecodedOutput(info.presentationTimeUs, false, endOfStream);
    }

    private static void setCsd(MediaFormat format, String key, byte[] csd) {
        if (csd != null) {
            format.setByteBuffer(key, ByteBuffer.wrap(csd));
        }
    }
}
