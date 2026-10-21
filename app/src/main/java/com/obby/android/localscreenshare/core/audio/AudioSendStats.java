package com.obby.android.localscreenshare.core.audio;

/**
 * Immutable snapshot of one audio send session's counters.
 *
 * <p>{@code queuedFrames} and {@code queuedMs} describe the frames currently waiting,
 * {@code sentFrames} and {@code droppedFrames} accumulate over the session's lifetime, and
 * {@code descriptorSends} counts descriptors actually handed to the transport. Every dropped frame
 * is about 21 ms of audio that the client will fill with silence, so the counter is the first thing
 * to look at when a viewer reports choppy sound.</p>
 */
public final class AudioSendStats {

    /**
     * Stats of an audio session that never sent anything.
     */
    public static final AudioSendStats EMPTY = new AudioSendStats(0, 0, 0, 0, 0);

    private final long queuedFrames;

    private final long queuedMs;

    private final long sentFrames;

    private final long droppedFrames;

    private final long descriptorSends;

    /**
     * Creates a snapshot.
     */
    public AudioSendStats(long queuedFrames, long queuedMs, long sentFrames, long droppedFrames,
                          long descriptorSends) {
        this.queuedFrames = queuedFrames;
        this.queuedMs = queuedMs;
        this.sentFrames = sentFrames;
        this.droppedFrames = droppedFrames;
        this.descriptorSends = descriptorSends;
    }

    public long getQueuedFrames() {
        return queuedFrames;
    }

    public long getQueuedMs() {
        return queuedMs;
    }

    public long getSentFrames() {
        return sentFrames;
    }

    public long getDroppedFrames() {
        return droppedFrames;
    }

    public long getDescriptorSends() {
        return descriptorSends;
    }

    /**
     * Returns this snapshot plus {@code other}, used to aggregate per-session counters.
     */
    public AudioSendStats plus(AudioSendStats other) {
        if (other == null) {
            return this;
        }
        return new AudioSendStats(queuedFrames + other.queuedFrames, queuedMs + other.queuedMs,
                sentFrames + other.sentFrames, droppedFrames + other.droppedFrames,
                descriptorSends + other.descriptorSends);
    }
}
