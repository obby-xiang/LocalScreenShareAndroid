package com.obby.android.localscreenshare.core.audio.mediacodec;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.os.Build;

import com.obby.android.localscreenshare.core.audio.AudioSink;
import com.obby.android.localscreenshare.core.audio.AudioSinkException;

/**
 * {@link AudioSink} backed by a streaming {@code AudioTrack}.
 *
 * <p>Playback uses {@code MODE_STREAM} with media attributes: a screen-share viewer is consuming
 * media, and the track must coexist with whatever else the device is playing. Buffering is three
 * frames or the platform minimum, whichever is larger, which is the range where the jitter buffer
 * has room to absorb jitter without adding audible delay. API 26 and later request the low-latency
 * performance mode; older devices simply ignore the concept.</p>
 *
 * <p>Volume is applied through the track, so muting never stops decoding or resets the timeline.</p>
 */
public final class AudioTrackSink implements AudioSink {

    /**
     * Buffer duration in milliseconds; three AAC frames.
     */
    private static final int BUFFER_MS = 64;

    private final AudioTimestamp timestamp = new AudioTimestamp();

    private AudioTrack track;

    private int sampleRate;

    private int channels;

    private long writtenBytes;

    @Override
    public void open(int sampleRate, int channels) throws AudioSinkException {
        release();
        if (sampleRate <= 0 || channels <= 0) {
            throw new IllegalArgumentException("sample rate and channels must be positive");
        }
        final int channelMask = channels == 1
                ? AudioFormat.CHANNEL_OUT_MONO
                : AudioFormat.CHANNEL_OUT_STEREO;
        try {
            final int minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelMask,
                    AudioFormat.ENCODING_PCM_16BIT);
            final int requested = sampleRate * channels * 2 * BUFFER_MS / 1_000;
            final int bufferSize = Math.max(minBufferSize > 0 ? minBufferSize : requested,
                    requested);
            final AudioTrack.Builder builder = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelMask)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferSize);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            }
            track = builder.build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                release();
                throw new AudioSinkException("audio track is not initialized");
            }
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.writtenBytes = 0L;
        } catch (RuntimeException e) {
            release();
            throw new AudioSinkException("cannot create the audio track", e);
        }
    }

    @Override
    public boolean isOpen() {
        return track != null;
    }

    @Override
    public void write(byte[] pcm, int offset, int count) throws AudioSinkException {
        final AudioTrack current = track;
        if (current == null) {
            throw new AudioSinkException("audio track is not open");
        }
        try {
            final int written = current.write(pcm, offset, count, AudioTrack.WRITE_BLOCKING);
            if (written < 0) {
                throw new AudioSinkException("audio track write failed: " + written);
            }
            writtenBytes += written;
        } catch (RuntimeException e) {
            throw new AudioSinkException("audio track write failed", e);
        }
    }

    @Override
    public void setVolume(float volume) {
        final AudioTrack current = track;
        if (current == null) {
            return;
        }
        try {
            current.setVolume(volume);
        } catch (RuntimeException ignored) {
            // A volume change on a released track is not worth failing the session for.
        }
    }

    @Override
    public void play() {
        final AudioTrack current = track;
        if (current == null) {
            return;
        }
        try {
            if (current.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                current.play();
            }
        } catch (RuntimeException ignored) {
            // Play on a failed track is reported by the next write.
        }
    }

    @Override
    public void pause() {
        final AudioTrack current = track;
        if (current == null) {
            return;
        }
        try {
            if (current.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                current.pause();
            }
        } catch (RuntimeException ignored) {
            // Pause on a failed track is reported by the next write.
        }
    }

    @Override
    public void release() {
        final AudioTrack current = track;
        track = null;
        if (current == null) {
            return;
        }
        try {
            current.pause();
        } catch (RuntimeException ignored) {
            // A track that never played needs no pause.
        }
        try {
            current.flush();
        } catch (RuntimeException ignored) {
            // Flushing a failed track is harmless.
        }
        try {
            current.release();
        } catch (RuntimeException ignored) {
            // Releasing twice is harmless.
        }
    }

    /**
     * Returns the played position in monotonic nanoseconds, or a negative value when unknown.
     *
     * <p>Uses the platform timestamp when it is available and falls back to the playback head
     * position, which is still a monotonic frame count even if it does not name a clock.</p>
     */
    @Override
    public long getPlayheadNs() {
        final AudioTrack current = track;
        if (current == null) {
            return -1L;
        }
        try {
            if (current.getTimestamp(timestamp)) {
                return timestamp.framePosition * 1_000_000_000L / sampleRate;
            }
            final long position = current.getPlaybackHeadPosition() & 0xFFFFFFFFL;
            return position * 1_000_000_000L / sampleRate;
        } catch (RuntimeException ignored) {
            return -1L;
        }
    }

    /**
     * Returns the bytes written since the track was opened.
     */
    public long getWrittenBytes() {
        return writtenBytes;
    }

    /**
     * Returns the channel count of the open track, or zero.
     */
    public int getChannels() {
        return channels;
    }
}
