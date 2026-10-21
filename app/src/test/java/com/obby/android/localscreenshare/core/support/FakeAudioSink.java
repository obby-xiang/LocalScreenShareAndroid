package com.obby.android.localscreenshare.core.support;

import com.obby.android.localscreenshare.core.audio.AudioSink;
import com.obby.android.localscreenshare.core.audio.AudioSinkException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Recording {@link AudioSink} for JVM tests.
 *
 * <p>Keeps every written buffer as a byte-length record (contents are all zeros in these tests), the
 * volume history, and the lifecycle counters, so a test can assert exactly how much audio reached
 * the output. Open and write failures are injectable; the playhead is a settable value.</p>
 */
public final class FakeAudioSink implements AudioSink {

    public final List<Integer> writeLengths = Collections.synchronizedList(new ArrayList<Integer>());

    public final List<Float> volumes = Collections.synchronizedList(new ArrayList<Float>());

    private final List<Integer> sampleRates = Collections.synchronizedList(new ArrayList<Integer>());

    private AudioSinkException openFailure;

    private AudioSinkException writeFailure;

    private long playheadNs = -1L;

    private boolean open;

    private int openCount;

    private int releaseCount;

    private int playCount;

    private int pauseCount;

    private int channels;

    public void failOpen(AudioSinkException failure) {
        this.openFailure = failure;
    }

    public void failWrite(AudioSinkException failure) {
        this.writeFailure = failure;
    }

    public void setPlayheadNs(long playheadNs) {
        this.playheadNs = playheadNs;
    }

    public int getOpenCount() {
        return openCount;
    }

    public int getReleaseCount() {
        return releaseCount;
    }

    public int getPlayCount() {
        return playCount;
    }

    public int getPauseCount() {
        return pauseCount;
    }

    public int getChannels() {
        return channels;
    }

    public List<Integer> getSampleRates() {
        return sampleRates;
    }

    public int getWrittenBytes() {
        int total = 0;
        synchronized (writeLengths) {
            for (Integer length : writeLengths) {
                total += length;
            }
        }
        return total;
    }

    @Override
    public void open(int sampleRate, int channels) throws AudioSinkException {
        if (openFailure != null) {
            throw openFailure;
        }
        this.open = true;
        this.channels = channels;
        openCount++;
        sampleRates.add(sampleRate);
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void write(byte[] pcm, int offset, int count) throws AudioSinkException {
        if (writeFailure != null) {
            throw writeFailure;
        }
        writeLengths.add(count);
    }

    @Override
    public void setVolume(float volume) {
        volumes.add(volume);
    }

    @Override
    public void play() {
        playCount++;
    }

    @Override
    public void pause() {
        pauseCount++;
    }

    @Override
    public void release() {
        open = false;
        releaseCount++;
    }

    @Override
    public long getPlayheadNs() {
        return playheadNs;
    }
}
