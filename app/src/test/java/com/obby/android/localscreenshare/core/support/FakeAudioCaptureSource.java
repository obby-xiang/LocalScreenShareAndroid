package com.obby.android.localscreenshare.core.support;

import com.obby.android.localscreenshare.core.audio.AudioCaptureException;
import com.obby.android.localscreenshare.core.audio.AudioCaptureSource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Scripted {@link AudioCaptureSource} for JVM tests.
 *
 * <p>Reads return a fixed number of ramp samples up to a configured limit, then report "no data".
 * Failures can be injected at open time or after a number of successful reads, and a latch signals
 * the first read so a test can wait for the pipeline worker without sleeping.</p>
 */
public final class FakeAudioCaptureSource implements AudioCaptureSource {

    private final int blockSamples;

    private final int maxReads;

    private final CountDownLatch firstRead = new CountDownLatch(1);

    private AudioCaptureException openFailure;

    private AudioCaptureException readFailure;

    private int readFailureAfterReads = -1;

    private CaptureTimestamp timestamp;

    private int reads;

    private int allReads;

    private int openCount;

    private int closeCount;

    private boolean open;

    private int sampleRate;

    private int channels;

    /**
     * Creates a source that returns {@code blockSamples} samples per read, at most
     * {@code maxReads} times.
     */
    public FakeAudioCaptureSource(int blockSamples, int maxReads) {
        this.blockSamples = blockSamples;
        this.maxReads = maxReads;
    }

    /**
     * Makes every read fail after the given number of successful reads.
     */
    public FakeAudioCaptureSource failReadsAfter(int successfulReads, AudioCaptureException failure) {
        this.readFailureAfterReads = successfulReads;
        this.readFailure = failure;
        return this;
    }

    /**
     * Makes {@link #open(int, int)} fail.
     */
    public FakeAudioCaptureSource failOpen(AudioCaptureException failure) {
        this.openFailure = failure;
        return this;
    }

    /**
     * Sets the timestamp {@link #getTimestamp()} reports.
     */
    public FakeAudioCaptureSource timestamp(CaptureTimestamp timestamp) {
        this.timestamp = timestamp;
        return this;
    }

    /**
     * Waits until the source was read at least once.
     */
    public boolean awaitFirstRead(long timeoutMs) throws InterruptedException {
        return firstRead.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public int getReadCount() {
        return allReads;
    }

    public int getOpenCount() {
        return openCount;
    }

    public int getCloseCount() {
        return closeCount;
    }

    @Override
    public void open(int sampleRate, int channels) throws AudioCaptureException {
        if (openFailure != null) {
            throw openFailure;
        }
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.open = true;
        this.reads = 0;
        openCount++;
    }

    @Override
    public int read(short[] buffer, int offset, int count) throws AudioCaptureException {
        firstRead.countDown();
        allReads++;
        if (readFailure != null && readFailureAfterReads >= 0
                && reads >= readFailureAfterReads) {
            throw readFailure;
        }
        if (reads >= maxReads || count <= 0) {
            return 0;
        }
        final int samples = Math.min(Math.min(blockSamples, count), buffer.length - offset);
        for (int i = 0; i < samples; i++) {
            buffer[offset + i] = (short) (i % 128);
        }
        reads++;
        return samples;
    }

    @Override
    public boolean isOpen() {
        return open;
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
        return timestamp;
    }

    @Override
    public void close() {
        open = false;
        closeCount++;
    }
}
