package com.obby.android.localscreenshare.core.audio.mediacodec;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.util.Log;

import com.obby.android.localscreenshare.core.audio.AudioPlayhead;
import com.obby.android.localscreenshare.core.audio.AudioSink;
import com.obby.android.localscreenshare.core.audio.AudioSinkException;
import java.util.Locale;

/**
 * {@link AudioSink} backed by a streaming {@code AudioTrack}.
 *
 * <p>Playback uses {@code MODE_STREAM} with media attributes: a screen-share viewer is consuming
 * media, and the track must coexist with whatever else the device is playing. Buffering is 128 ms or
 * the platform minimum, whichever is larger, which is the reservoir the jitter buffer prefills;
 * a smaller track cannot hold the target depth at all, which is how the original 64 ms buffer turned
 * every refill into a track underrun. The low-latency performance mode is deliberately not requested:
 * a viewer's picture already runs hundreds of milliseconds behind the sender, so trading buffer for
 * latency buys nothing audible and costs every starvation.</p>
 *
 * <p>Volume is applied through the track, so muting never stops decoding or resets the timeline.</p>
 */
public final class AudioTrackSink implements AudioSink {

    /**
     * Buffer duration in milliseconds; six AAC frames.
     */
    private static final int BUFFER_MS = 128;

    /**
     * Minimum interval between two platform-underrun reports, in milliseconds.
     */
    private static final long UNDERRUN_LOG_INTERVAL_MS = 1_000L;

    private final String tag = "AudioTrackSink@" + hashCode();

    private final AudioTimestamp timestamp = new AudioTimestamp();

    private AudioTrack track;

    private int sampleRate;

    private int channels;

    private long writtenBytes;

    private int reportedUnderrunCount;

    private long lastUnderrunLogMs;

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
            track = builder.build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                release();
                throw new AudioSinkException("audio track is not initialized");
            }
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.writtenBytes = 0L;
            this.reportedUnderrunCount = 0;
            this.lastUnderrunLogMs = 0L;
            Log.i(tag, String.format(Locale.US, "open: sampleRate = %d, channels = %d, bufferBytes = %d,"
                    + " bufferFrames = %d, bufferMs = %d", sampleRate, channels, bufferSize,
                track.getBufferSizeInFrames(),
                sampleRate > 0 ? track.getBufferSizeInFrames() * 1_000L / sampleRate : 0L));
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
            reportTrackUnderruns(current);
        } catch (RuntimeException e) {
            throw new AudioSinkException("audio track write failed", e);
        }
    }

    /**
     * Reports the platform's own underrun counter when it grows.
     *
     * <p>This is the number {@code AudioFlinger} increments when the mixer had to fill with zeros;
     * on the device that reported this bug it grew at 6.03 events per second while the application
     * only saw 2.60, which is how the two layers were told apart. Reporting it from the application
     * means the next field report does not have to reconstruct it from {@code dumpsys} and the
     * platform log. The report is limited to one line per second.</p>
     */
    private void reportTrackUnderruns(AudioTrack current) {
        final int underruns;
        try {
            underruns = current.getUnderrunCount();
        } catch (RuntimeException ignored) {
            return;
        }
        if (underruns <= reportedUnderrunCount) {
            return;
        }
        final long nowMs = System.nanoTime() / 1_000_000L;
        if (lastUnderrunLogMs != 0L && nowMs - lastUnderrunLogMs < UNDERRUN_LOG_INTERVAL_MS) {
            return;
        }
        // The delta is measured from the last report, not from the last write, so a burst inside one
        // second is reported as one number instead of only its last increment.
        final int delta = underruns - reportedUnderrunCount;
        reportedUnderrunCount = underruns;
        lastUnderrunLogMs = nowMs;
        Log.w(tag, String.format(Locale.US, "track underrun: +%d since the last report, total = %d,"
                + " writtenBytes = %d", delta, underruns, writtenBytes));
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
     * Returns the frames presented at {@code nowNs}, or a negative value when unknown.
     *
     * <p>The platform timestamp pairs a frame position with the monotonic instant that position was
     * presented at, which is the only combination that can be projected onto the session clock: the
     * position alone is track-relative and would be off by the device uptime. The playback-head
     * fallback is the same frame count without the projection, so a device that reports no
     * timestamp still yields a position, just up to one hardware buffer late.</p>
     */
    @Override
    public long getPresentedFrames(long nowNs) {
        final AudioTrack current = track;
        if (current == null) {
            return -1L;
        }
        try {
            if (current.getTimestamp(timestamp) && timestamp.nanoTime > 0L) {
                return AudioPlayhead.presentedFrames(timestamp.framePosition, timestamp.nanoTime,
                        nowNs, sampleRate);
            }
            return current.getPlaybackHeadPosition() & 0xFFFFFFFFL;
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
