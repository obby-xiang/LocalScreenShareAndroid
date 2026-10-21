package com.obby.android.localscreenshare.core.audio.mediacodec;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;

import com.obby.android.localscreenshare.core.audio.AudioCaptureException;
import com.obby.android.localscreenshare.core.audio.AudioCodecIds;
import com.obby.android.localscreenshare.core.audio.AudioEncoderBridge;
import com.obby.android.localscreenshare.core.audio.EncodedAudioFrame;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * {@link AudioEncoderBridge} backed by a platform AAC-LC encoder.
 *
 * <p>Encoding is synchronous: one input frame in, at most one output frame out per drain. The
 * AudioSpecificConfig arrives with the output-format change that AAC encoders emit before their
 * first frame, and is cached for the descriptor. Input-buffer exhaustion is treated as ordinary
 * back-pressure and drops that frame instead of failing the pipeline, because losing one 21 ms
 * frame is cheaper than stopping audio.</p>
 */
public final class AndroidAudioEncoderBridge implements AudioEncoderBridge {

    private static final long DEQUEUE_TIMEOUT_US = 10_000L;

    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

    private MediaCodec codec;

    private byte[] audioSpecificConfig;

    private boolean closed;

    @Override
    public void configure(int sampleRate, int channels, int bitrateBps)
            throws AudioCaptureException {
        if (codec != null) {
            return;
        }
        try {
            final MediaFormat format = MediaFormat.createAudioFormat(AudioCodecIds.AAC_MIME,
                    sampleRate, channels);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024);
            codec = MediaCodec.createEncoderByType(AudioCodecIds.AAC_MIME);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            closed = false;
        } catch (IOException | RuntimeException e) {
            close();
            throw new AudioCaptureException(AudioCaptureException.Reason.ENCODER_FAILED,
                    "cannot create the AAC encoder", e);
        }
    }

    @Override
    public void queueInput(short[] pcm, int offset, int count, long ptsUs)
            throws AudioCaptureException {
        final MediaCodec current = codec;
        if (current == null) {
            throw new AudioCaptureException(AudioCaptureException.Reason.ENCODER_FAILED,
                    "encoder is not configured");
        }
        try {
            final int index = current.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
            if (index < 0) {
                // No input buffer: the encoder is behind. Dropping the frame keeps audio alive.
                return;
            }
            final ByteBuffer buffer = current.getInputBuffer(index);
            if (buffer == null) {
                return;
            }
            final int bytes = count * 2;
            buffer.clear();
            buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, offset, count);
            current.queueInputBuffer(index, 0, bytes, ptsUs, 0);
        } catch (RuntimeException e) {
            throw new AudioCaptureException(AudioCaptureException.Reason.ENCODER_FAILED,
                    "cannot queue PCM to the AAC encoder", e);
        }
    }

    @Override
    public EncodedAudioFrame dequeueOutput() throws AudioCaptureException {
        final MediaCodec current = codec;
        if (current == null) {
            return null;
        }
        try {
            final int index = current.dequeueOutputBuffer(bufferInfo, 0L);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                audioSpecificConfig = readConfig(current.getOutputFormat());
                return null;
            }
            if (index < 0) {
                return null;
            }
            final ByteBuffer buffer = current.getOutputBuffer(index);
            if (buffer == null || bufferInfo.size == 0) {
                current.releaseOutputBuffer(index, false);
                return null;
            }
            final byte[] payload = new byte[bufferInfo.size];
            buffer.position(bufferInfo.offset);
            buffer.limit(bufferInfo.offset + bufferInfo.size);
            buffer.get(payload);
            current.releaseOutputBuffer(index, false);
            return new EncodedAudioFrame(Math.max(0L, bufferInfo.presentationTimeUs), payload);
        } catch (RuntimeException e) {
            throw new AudioCaptureException(AudioCaptureException.Reason.ENCODER_FAILED,
                    "cannot dequeue from the AAC encoder", e);
        }
    }

    @Override
    public byte[] getAudioSpecificConfig() {
        return audioSpecificConfig;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        final MediaCodec current = codec;
        codec = null;
        if (current == null) {
            return;
        }
        try {
            current.stop();
        } catch (RuntimeException ignored) {
            // A codec that never started needs no stop.
        }
        try {
            current.release();
        } catch (RuntimeException ignored) {
            // Releasing twice is harmless.
        }
    }

    private static byte[] readConfig(MediaFormat format) {
        final ByteBuffer csd = format == null ? null : format.getByteBuffer("csd-0");
        if (csd == null) {
            return null;
        }
        final byte[] config = new byte[csd.remaining()];
        csd.get(config);
        return config;
    }
}
