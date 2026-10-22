package com.obby.android.localscreenshare.core.audio.mediacodec;

import android.annotation.TargetApi;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.projection.MediaProjection;
import android.os.Build;

import androidx.annotation.RequiresApi;

import com.obby.android.localscreenshare.core.audio.AudioCaptureException;
import com.obby.android.localscreenshare.core.audio.AudioCaptureSource;

/**
 * Playback-audio capture through {@code AudioPlaybackCaptureConfiguration} (API 29 and later).
 *
 * <p>This source captures what other applications are playing, not the microphone: the builder
 * never calls {@code setAudioSource}, which the platform rejects with
 * {@link IllegalStateException} when a playback-capture configuration is present. Only the media,
 * game and unknown usages are eligible, and the playing application must allow capture, so
 * "silence" is a normal outcome rather than a fault. The capture reuses the screen-capture
 * {@link MediaProjection}: opening audio never asks the user for a second consent.</p>
 *
 * <p>The class is only instantiated when the caller checked the API level; it exists as a separate
 * implementation because the microphone path is a different source, never a flag on this one.</p>
 */
@RequiresApi(Build.VERSION_CODES.Q)
@TargetApi(Build.VERSION_CODES.Q)
public final class PlaybackCaptureSource implements AudioCaptureSource {

    /**
     * Multiplier applied to the platform minimum buffer size to absorb scheduling jitter.
     */
    private static final int BUFFER_SIZE_MULTIPLIER = 2;

    private final MediaProjection projection;

    private final AudioTimestamp timestamp = new AudioTimestamp();

    private AudioRecord record;

    private int sampleRate;

    private int channels;

    /**
     * Creates a source bound to the active media projection.
     *
     * @param projection projection that authorized capture; required
     * @throws IllegalArgumentException if {@code projection} is null
     */
    public PlaybackCaptureSource(MediaProjection projection) {
        if (projection == null) {
            throw new IllegalArgumentException("projection must not be null");
        }
        this.projection = projection;
    }

    @Override
    public void open(int sampleRate, int channels) throws AudioCaptureException {
        if (record != null) {
            return;
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive");
        }
        if (channels < 1 || channels > 2) {
            throw new IllegalArgumentException("channels must be 1 or 2");
        }
        final int channelMask = channels == 1
                ? AudioFormat.CHANNEL_IN_MONO
                : AudioFormat.CHANNEL_IN_STEREO;
        final AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build();
        try {
            final int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelMask,
                    AudioFormat.ENCODING_PCM_16BIT);
            final int bufferSize = minBufferSize > 0
                    ? minBufferSize * BUFFER_SIZE_MULTIPLIER
                    : sampleRate * channels;
            final android.media.AudioPlaybackCaptureConfiguration captureConfiguration =
                    new android.media.AudioPlaybackCaptureConfiguration.Builder(projection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .build();
            record = new AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(captureConfiguration)
                    .build();
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                close();
                throw new AudioCaptureException(AudioCaptureException.Reason.DEVICE_BUSY,
                        "playback capture record is not initialized");
            }
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                close();
                throw new AudioCaptureException(AudioCaptureException.Reason.DEVICE_BUSY,
                        "playback capture did not start recording");
            }
            this.sampleRate = sampleRate;
            this.channels = channels;
        } catch (SecurityException e) {
            close();
            throw new AudioCaptureException(AudioCaptureException.Reason.PERMISSION_DENIED,
                    "recording permission missing", e);
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            close();
            throw new AudioCaptureException(AudioCaptureException.Reason.UNSUPPORTED,
                    "playback capture is not supported", e);
        } catch (IllegalStateException e) {
            close();
            throw new AudioCaptureException(AudioCaptureException.Reason.DEVICE_BUSY,
                    "playback capture device is busy", e);
        }
    }

    @Override
    public int read(short[] buffer, int offset, int count) throws AudioCaptureException {
        final AudioRecord current = record;
        if (current == null) {
            throw new AudioCaptureException(AudioCaptureException.Reason.INTERNAL,
                    "capture source is not open");
        }
        try {
            final int read = current.read(buffer, offset, count, AudioRecord.READ_BLOCKING);
            if (read < 0) {
                throw new AudioCaptureException(AudioCaptureException.Reason.DEVICE_BUSY,
                        "capture read failed: " + read);
            }
            return read;
        } catch (RuntimeException e) {
            // A revoked recording permission surfaces here as a SecurityException, which used to
            // escape the capture thread and end the process; the shared mapping keeps the reason
            // consistent with the encoder side.
            throw new AudioCaptureException(AudioCaptureException.classify(e),
                    "capture read failed", e);
        }
    }

    @Override
    public boolean isOpen() {
        return record != null;
    }

    @Override
    public int getSampleRate() {
        return sampleRate;
    }

    @Override
    public int getChannels() {
        return channels;
    }

    @Override
    public CaptureTimestamp getTimestamp() {
        final AudioRecord current = record;
        if (current == null) {
            return null;
        }
        try {
            if (current.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC)
                    != AudioRecord.SUCCESS) {
                return null;
            }
            return new CaptureTimestamp(timestamp.framePosition, timestamp.nanoTime);
        } catch (RuntimeException ignored) {
            // A device that cannot report a timestamp simply falls back to the linear derivation.
            return null;
        }
    }

    @Override
    public void close() {
        final AudioRecord current = record;
        record = null;
        if (current == null) {
            return;
        }
        try {
            if (current.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                current.stop();
            }
        } catch (RuntimeException ignored) {
            // A record that failed to start needs no stop.
        }
        try {
            current.release();
        } catch (RuntimeException ignored) {
            // Releasing an already released record is harmless.
        }
    }
}
