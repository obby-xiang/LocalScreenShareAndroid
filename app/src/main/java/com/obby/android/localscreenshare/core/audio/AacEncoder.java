package com.obby.android.localscreenshare.core.audio;

import com.obby.android.localscreenshare.core.model.AudioAccessUnit;
import com.obby.android.localscreenshare.core.model.AudioDescriptor;
import com.obby.android.localscreenshare.core.model.CodecConfigPart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Frames PCM samples into AAC-LC access units.
 *
 * <p>The encoder accumulates interleaved 16-bit samples until it holds exactly one frame and then
 * hands that frame to the {@link AudioEncoderBridge}. Framing lives here rather than in the
 * capture source, so a source may return any block size (a 10 ms read, a partial read, a larger
 * read) without changing the frame rhythm the receiver sees.</p>
 *
 * <p><b>Timestamps.</b> The presentation time of frame {@code n} is
 * {@code anchorPtsUs + n * frameSamples * 1e6 / sampleRate}, that is, the caller's capture anchor
 * propagated by integer arithmetic. Nothing about the wall clock re-enters the calculation, so a
 * slow read never shifts the timeline.</p>
 *
 * <p><b>Descriptor.</b> The descriptor becomes available once the bridge reports the
 * AudioSpecificConfig, which for AAC happens before the first encoded frame. {@link #getDescriptor()}
 * returns null before that, and the caller publishes it ahead of the first access unit.</p>
 */
public final class AacEncoder {

    /**
     * Samples per encoded frame; AAC-LC always encodes 1024 samples.
     */
    public static final int FRAME_SAMPLES = 1024;

    private final AudioEncoderBridge bridge;

    private AudioDescriptor descriptor;

    private boolean open;

    private boolean closed;

    private int configEpoch;

    private int sampleRate;

    private int channels;

    private int bitrateBps;

    private long anchorPtsUs;

    private long framesQueued;

    private long nextSequence;

    private short[] pending;

    private int pendingCount;

    /**
     * Creates an encoder over {@code bridge}.
     *
     * @param bridge platform or fake encoder; required
     * @throws IllegalArgumentException if {@code bridge} is null
     */
    public AacEncoder(AudioEncoderBridge bridge) {
        if (bridge == null) {
            throw new IllegalArgumentException("bridge must not be null");
        }
        this.bridge = bridge;
    }

    /**
     * Creates and configures the encoder.
     *
     * @param configEpoch configuration epoch stamped on every unit and descriptor
     * @param sampleRate  sample rate in hertz; must be positive
     * @param channels    channel count, one or two
     * @param bitrateBps  target bitrate in bits per second; must be positive
     * @param anchorPtsUs presentation time of the first sample of frame zero
     * @throws AudioCaptureException    with {@code ENCODER_FAILED} when the codec cannot be used
     * @throws IllegalArgumentException if a parameter is out of range
     */
    public void open(int configEpoch, int sampleRate, int channels, int bitrateBps,
                     long anchorPtsUs) throws AudioCaptureException {
        if (configEpoch < 1) {
            throw new IllegalArgumentException("configEpoch must be >= 1");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive");
        }
        if (channels < 1 || channels > 2) {
            throw new IllegalArgumentException("channels must be 1 or 2");
        }
        if (bitrateBps <= 0) {
            throw new IllegalArgumentException("bitrateBps must be positive");
        }
        if (anchorPtsUs < 0) {
            throw new IllegalArgumentException("anchorPtsUs must be non-negative");
        }
        this.configEpoch = configEpoch;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.bitrateBps = bitrateBps;
        this.anchorPtsUs = anchorPtsUs;
        this.framesQueued = 0L;
        this.nextSequence = 0L;
        this.pendingCount = 0;
        this.pending = new short[FRAME_SAMPLES * channels];
        this.descriptor = null;
        this.open = true;
        this.closed = false;
        bridge.configure(sampleRate, channels, bitrateBps);
        publishDescriptorIfReady();
    }

    /**
     * Encodes a block of interleaved PCM samples and returns every frame it completed.
     *
     * <p>The block may be smaller or larger than one frame; incomplete samples are kept for the
     * next call. Returned units are ordered by sequence and their timestamps are strictly
     * increasing.</p>
     *
     * @param pcm    interleaved samples; must not be null
     * @param offset first sample of the block
     * @param count  number of valid samples; the remainder of {@code pcm} is ignored
     * @return completed access units, possibly empty, never null
     * @throws AudioCaptureException    with {@code ENCODER_FAILED} when the codec fails
     * @throws IllegalStateException    when the encoder is not open
     * @throws IllegalArgumentException if the range is invalid
     */
    public List<AudioAccessUnit> encode(short[] pcm, int offset, int count)
            throws AudioCaptureException {
        if (!open) {
            throw new IllegalStateException("encoder is not open");
        }
        if (pcm == null) {
            throw new IllegalArgumentException("pcm must not be null");
        }
        if (offset < 0 || count < 0 || offset + count > pcm.length) {
            throw new IllegalArgumentException("invalid PCM range");
        }
        if (count == 0) {
            return Collections.emptyList();
        }
        final int samplesPerFrame = FRAME_SAMPLES * channels;
        List<AudioAccessUnit> units = new ArrayList<>(count / samplesPerFrame + 1);
        int consumed = 0;
        while (consumed < count) {
            final int copy = Math.min(count - consumed, pending.length - pendingCount);
            System.arraycopy(pcm, offset + consumed, pending, pendingCount, copy);
            pendingCount += copy;
            consumed += copy;
            if (pendingCount == pending.length) {
                queuePendingFrame();
                pendingCount = 0;
                drainLocked(units);
            }
        }
        return units;
    }

    /**
     * Returns the descriptor of this encoder, or null before the AudioSpecificConfig is known.
     */
    public AudioDescriptor getDescriptor() {
        return descriptor;
    }

    /**
     * Returns the duration of one encoded frame in microseconds.
     */
    public long getFrameDurationUs() {
        return FRAME_SAMPLES * 1_000_000L / sampleRate;
    }

    /**
     * Closes the encoder; idempotent and non-throwing.
     */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        open = false;
        bridge.close();
    }

    private void queuePendingFrame() throws AudioCaptureException {
        final long ptsUs = anchorPtsUs
                + framesQueued * FRAME_SAMPLES * 1_000_000L / sampleRate;
        framesQueued++;
        bridge.queueInput(pending, 0, pending.length, ptsUs);
    }

    private void drainLocked(List<AudioAccessUnit> units) throws AudioCaptureException {
        EncodedAudioFrame frame;
        while ((frame = bridge.dequeueOutput()) != null) {
            publishDescriptorIfReady();
            units.add(new AudioAccessUnit(configEpoch, nextSequence++, frame.getPtsUs(),
                    frame.getPayload()));
        }
    }

    private void publishDescriptorIfReady() {
        if (descriptor != null) {
            return;
        }
        final byte[] csd = bridge.getAudioSpecificConfig();
        if (csd == null || csd.length == 0) {
            return;
        }
        descriptor = AudioDescriptor.builder(configEpoch, AudioCodecIds.AAC,
                        AudioCodecIds.AAC_MIME, sampleRate, channels, bitrateBps)
                .configParts(Collections.singletonList(
                        new CodecConfigPart(AudioCodecIds.CONFIG_PART_CSD_0, csd)))
                .build();
    }
}
