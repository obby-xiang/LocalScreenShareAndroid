package com.obby.android.localscreenshare.core.audio.mediacodec;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;

import androidx.annotation.RequiresApi;

import com.obby.android.localscreenshare.core.audio.AudioCodecIds;
import com.obby.android.localscreenshare.core.audio.AudioDecoder;
import com.obby.android.localscreenshare.core.audio.AudioDecoderException;
import com.obby.android.localscreenshare.core.audio.AudioPcm;
import com.obby.android.localscreenshare.core.model.AudioDescriptor;
import com.obby.android.localscreenshare.core.model.CodecConfigPart;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * {@link AudioDecoder} backed by a platform AAC decoder in synchronous mode.
 *
 * <p>The decoder is configured from the descriptor's AudioSpecificConfig, so the wire never needs
 * ADTS headers. The output format is taken from the codec itself rather than assumed: some devices
 * return a different sample rate or channel count than requested, and the player has to follow what
 * actually came out.</p>
 *
 * <p>One payload may produce no output yet, which is normal for the first frame after a
 * configuration change; the caller simply waits for the next payload. Any codec failure is reported
 * as an {@link AudioDecoderException} and turns into an unavailable audio track, never a video
 * problem.</p>
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
public final class AndroidAudioDecoder implements AudioDecoder {

    private static final long DEQUEUE_TIMEOUT_US = 5_000L;

    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

    private MediaCodec codec;

    private int outputSampleRate;

    private int outputChannels;

    @Override
    public void configure(AudioDescriptor descriptor) throws AudioDecoderException {
        close();
        try {
            final MediaFormat format = MediaFormat.createAudioFormat(descriptor.getMime(),
                    descriptor.getSampleRate(), descriptor.getChannels());
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024);
            for (CodecConfigPart part : descriptor.getConfigParts()) {
                format.setByteBuffer(part.getId(), ByteBuffer.wrap(part.getPayload()));
            }
            codec = MediaCodec.createDecoderByType(descriptor.getMime());
            codec.configure(format, null, null, 0);
            codec.start();
            outputSampleRate = descriptor.getSampleRate();
            outputChannels = descriptor.getChannels();
        } catch (IOException | RuntimeException e) {
            close();
            throw new AudioDecoderException("cannot create the audio decoder", e);
        }
    }

    @Override
    public AudioPcm decode(byte[] payload, long ptsUs) throws AudioDecoderException {
        final MediaCodec current = codec;
        if (current == null) {
            throw new AudioDecoderException("audio decoder is not configured");
        }
        if (payload == null || payload.length == 0) {
            return null;
        }
        try {
            final int inputIndex = current.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
            if (inputIndex < 0) {
                return null;
            }
            final ByteBuffer input = current.getInputBuffer(inputIndex);
            if (input == null) {
                return null;
            }
            input.clear();
            input.put(payload);
            current.queueInputBuffer(inputIndex, 0, payload.length, ptsUs, 0);

            while (true) {
                final int outputIndex = current.dequeueOutputBuffer(bufferInfo,
                        DEQUEUE_TIMEOUT_US);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    readOutputFormat(current);
                    continue;
                }
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    return null;
                }
                if (outputIndex < 0) {
                    return null;
                }
                final ByteBuffer output = current.getOutputBuffer(outputIndex);
                if (output == null || bufferInfo.size == 0) {
                    current.releaseOutputBuffer(outputIndex, false);
                    return null;
                }
                final byte[] pcm = new byte[bufferInfo.size];
                output.position(bufferInfo.offset);
                output.limit(bufferInfo.offset + bufferInfo.size);
                output.get(pcm);
                current.releaseOutputBuffer(outputIndex, false);
                return new AudioPcm(pcm, outputSampleRate, outputChannels);
            }
        } catch (RuntimeException e) {
            throw new AudioDecoderException("audio decode failed", e);
        }
    }

    @Override
    public void close() {
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

    private void readOutputFormat(MediaCodec current) {
        final MediaFormat format = current.getOutputFormat();
        if (format == null) {
            return;
        }
        if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            outputSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        }
        if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            outputChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        }
    }
}
