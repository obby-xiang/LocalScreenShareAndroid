package com.obby.android.localscreenshare.core.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.obby.android.localscreenshare.core.metrics.PipelineMetrics;
import com.obby.android.localscreenshare.core.model.AudioAccessUnit;
import com.obby.android.localscreenshare.core.model.AudioDescriptor;
import com.obby.android.localscreenshare.core.model.CodecConfigPart;
import com.obby.android.localscreenshare.core.support.FakeAudioDecoder;
import com.obby.android.localscreenshare.core.support.FakeAudioSink;
import com.obby.android.localscreenshare.core.support.MutableAudioClock;
import com.obby.android.localscreenshare.core.util.NanoClock;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Unit tests for {@link ClientAudioSession}.
 */
public class ClientAudioSessionTest {

    private static final long FRAME_US = 21_333L;

    private static final long FRAME_NS = FRAME_US * 1_000L;

    @Test
    public void descriptorConfiguresTheDecoderAndDuplicateIsIgnored() {
        Fixture fixture = new Fixture();

        fixture.session.onDescriptor(descriptor(1));
        fixture.session.onDescriptor(descriptor(1));

        assertEquals(ClientAudioSession.State.ACTIVE, fixture.session.getState());
        assertEquals(1, fixture.decoder.getConfigureCount());
        assertEquals(1, fixture.session.getDescriptor().getConfigEpoch());
        assertNull(fixture.session.getUnavailableReason());
    }

    @Test
    public void unknownCodecMarksAudioUnavailable() {
        Fixture fixture = new Fixture();

        fixture.session.onDescriptor(AudioDescriptor.builder(1, "opus", "audio/opus", 48_000, 2,
                        64_000)
                .configParts(Collections.singletonList(
                        new CodecConfigPart("csd-0", new byte[]{0x01})))
                .build());

        assertEquals(ClientAudioSession.State.UNAVAILABLE, fixture.session.getState());
        assertEquals("unsupported audio codec", fixture.session.getUnavailableReason());
        assertEquals(0, fixture.decoder.getConfigureCount());
    }

    @Test
    public void framesPrebufferThenStartPlayback() {
        Fixture fixture = new Fixture();
        fixture.session.onDescriptor(descriptor(1));

        for (int i = 0; i < 5; i++) {
            fixture.session.onAccessUnit(unit(1, i, i * FRAME_US));
            assertEquals(0, fixture.sink.getPlayCount());
        }
        // Five written frames plus this one are 128 ms, above the 120 ms target.
        fixture.session.onAccessUnit(unit(1, 5, 5 * FRAME_US));

        assertEquals(1, fixture.sink.getPlayCount());
        assertEquals(6, fixture.sink.writeLengths.size());
        assertEquals(4096, (int) fixture.sink.writeLengths.get(0));
        assertEquals(6L, fixture.session.getWrittenFrames());
    }

    @Test
    public void theTrackBufferCountsTowardsTheTargetDepth() {
        Fixture fixture = new Fixture();
        fixture.session.onDescriptor(descriptor(1));
        // The track has presented everything it was given: five frames have no reservoir at all.
        fixture.sink.setQueuedFrames(0L);
        for (int i = 0; i < 5; i++) {
            fixture.session.onAccessUnit(unit(1, i, i * FRAME_US));
        }
        assertEquals(0, fixture.sink.getPlayCount());

        // The same five frames are now still inside the track, which is 106 ms of depth.
        fixture.sink.setQueuedFrames(5 * 1_024L);
        fixture.session.onAccessUnit(unit(1, 5, 5 * FRAME_US));

        assertEquals(1, fixture.sink.getPlayCount());
    }

    @Test
    public void aFrameCountIsNotDepth() {
        Fixture fixture = new Fixture();
        fixture.session.onDescriptor(descriptor(1));
        // Nothing is ever buffered: every frame is presented as soon as it is written.
        fixture.sink.setQueuedFrames(0L);

        for (int i = 0; i < 20; i++) {
            fixture.session.onAccessUnit(unit(1, i, i * FRAME_US));
        }

        // Twenty frames of history are not twenty frames of depth, so playback waits for the
        // prefill limit instead of starting on a phantom reservoir.
        assertEquals(0, fixture.sink.getPlayCount());
    }

    @Test
    public void lateFramesWaitForThePrefillTimeLimit() {
        Fixture fixture = new Fixture();
        fixture.session.onDescriptor(descriptor(1));
        fixture.session.onAccessUnit(unit(1, 0, 0L));
        assertEquals(0, fixture.sink.getPlayCount());

        fixture.advanceMs(AudioJitterBuffer.MAX_PREFILL_WAIT_MS);
        fixture.session.onAccessUnit(unit(1, 1, FRAME_US));

        assertEquals(1, fixture.sink.getPlayCount());
    }

    @Test
    public void aGapIsFilledWithSilenceBeforeTheNewFrame() {
        Fixture fixture = new Fixture();
        fixture.session.onDescriptor(descriptor(1));
        fixture.session.onAccessUnit(unit(1, 0, 0L));
        fixture.session.onAccessUnit(unit(1, 1, FRAME_US));
        fixture.session.onAccessUnit(unit(1, 2, 2 * FRAME_US));
        final int before = fixture.sink.writeLengths.size();

        fixture.session.onAccessUnit(unit(1, 3, 5 * FRAME_US));
        fixture.session.drainOnce();
        fixture.session.drainOnce();

        List<Integer> lengths = fixture.sink.writeLengths;
        assertEquals(before + 3, lengths.size());
        for (int i = before; i < lengths.size(); i++) {
            assertEquals(4096, (int) lengths.get(i));
        }
    }

    @Test
    public void backwardFramesAreDroppedWithoutWriting() {
        Fixture fixture = new Fixture();
        fixture.session.onDescriptor(descriptor(1));
        fixture.session.onAccessUnit(unit(1, 0, 2 * FRAME_US));
        final int before = fixture.sink.writeLengths.size();

        fixture.session.onAccessUnit(unit(1, 1, 0L));

        assertEquals(before, fixture.sink.writeLengths.size());
    }

    @Test
    public void framesOfAnotherEpochAndUnknownCodecAreIgnored() {
        Fixture fixture = new Fixture();
        fixture.session.onDescriptor(descriptor(2));

        fixture.session.onAccessUnit(unit(1, 0, 0L));

        assertEquals(0, fixture.decoder.getDecodeCount());
        assertEquals(0, fixture.sink.getOpenCount());
    }

    @Test
    public void decoderFailureMarksAudioUnavailable() {
        Fixture fixture = new Fixture();
        fixture.session.onDescriptor(descriptor(1));
        fixture.decoder.failDecode(new AudioDecoderException("boom"));

        fixture.session.onAccessUnit(unit(1, 0, 0L));

        assertEquals(ClientAudioSession.State.UNAVAILABLE, fixture.session.getState());
        assertEquals("audio decode failed", fixture.session.getUnavailableReason());
        assertTrue(fixture.sink.getReleaseCount() > 0);
    }

    @Test
    public void sinkOpenFailureMarksAudioUnavailable() {
        Fixture fixture = new Fixture();
        fixture.sink.failOpen(new AudioSinkException("no output"));
        fixture.session.onDescriptor(descriptor(1));

        fixture.session.onAccessUnit(unit(1, 0, 0L));

        assertEquals(ClientAudioSession.State.UNAVAILABLE, fixture.session.getState());
        assertEquals("audio output unavailable", fixture.session.getUnavailableReason());
    }

    @Test
    public void sinkWriteFailureMarksAudioUnavailable() {
        Fixture fixture = new Fixture();
        fixture.session.onDescriptor(descriptor(1));
        fixture.session.onAccessUnit(unit(1, 0, 0L));
        fixture.sink.failWrite(new AudioSinkException("write failed"));

        fixture.session.onAccessUnit(unit(1, 1, FRAME_US));

        assertEquals(ClientAudioSession.State.UNAVAILABLE, fixture.session.getState());
        assertEquals("audio playback failed", fixture.session.getUnavailableReason());
    }

    @Test
    public void mutingOnlyChangesTheVolume() {
        Fixture fixture = new Fixture();
        fixture.session.onDescriptor(descriptor(1));
        fixture.session.onAccessUnit(unit(1, 0, 0L));

        fixture.session.setVolume(0f);

        assertEquals(0f, fixture.session.getVolume(), 0.0001f);
        assertEquals(0f, fixture.sink.volumes.get(fixture.sink.volumes.size() - 1), 0.0001f);
        assertEquals(ClientAudioSession.State.ACTIVE, fixture.session.getState());
        assertEquals(1L, fixture.session.getWrittenFrames());
        assertThrows(IllegalArgumentException.class, () -> fixture.session.setVolume(1.5f));
    }

    @Test
    public void aNewEpochReconfiguresWithoutClosingTheSession() {
        Fixture fixture = new Fixture();
        fixture.session.onDescriptor(descriptor(1));
        fixture.session.onAccessUnit(unit(1, 0, 0L));

        fixture.session.onDescriptor(descriptor(2));

        assertEquals(2, fixture.decoder.getConfigureCount());
        assertEquals(ClientAudioSession.State.ACTIVE, fixture.session.getState());
        assertEquals(0, fixture.session.getPendingFrames());
    }

    @Test
    public void closeIsIdempotentAndStopsAcceptingFrames() {
        Fixture fixture = new Fixture();
        fixture.session.onDescriptor(descriptor(1));
        fixture.session.onAccessUnit(unit(1, 0, 0L));

        fixture.session.close();
        fixture.session.close();
        fixture.session.onAccessUnit(unit(1, 1, FRAME_US));

        assertEquals(ClientAudioSession.State.CLOSED, fixture.session.getState());
        assertEquals(1, fixture.decoder.getCloseCount());
        assertTrue(fixture.session.isPendingEmpty());
    }

    @Test
    public void descriptorIsRequiredBeforeFrames() {
        Fixture fixture = new Fixture();

        fixture.session.onAccessUnit(unit(1, 0, 0L));

        assertEquals(ClientAudioSession.State.IDLE, fixture.session.getState());
        assertEquals(0, fixture.decoder.getDecodeCount());
    }

    @Test
    public void nullOutputFromTheDecoderIsNotAnError() {
        Fixture fixture = new Fixture();
        fixture.session.onDescriptor(descriptor(1));
        fixture.decoder.returnNoOutput();

        fixture.session.onAccessUnit(unit(1, 0, 0L));

        assertEquals(ClientAudioSession.State.ACTIVE, fixture.session.getState());
        assertEquals(0, fixture.sink.writeLengths.size());
        assertNotNull(fixture.session.getDescriptor());
    }

    @Test
    public void anIdleWriterWaitsForTheNextEnqueueInsteadOfPolling() throws Exception {
        Fixture fixture = new Fixture(true, new MutableAudioClock(0L));
        fixture.session.onDescriptor(descriptor(1));

        fixture.session.onAccessUnit(unit(1, 0, 0L));

        // The frame reaches the sink on the writer thread, and an idle writer has to be waiting on
        // the session lock: a timed poll would leave it in TIMED_WAITING and add the poll interval
        // to every frame that arrives while it sleeps.
        assertTrue(awaitWriterState(fixture.session, Thread.State.WAITING));
        assertEquals(1L, fixture.session.getWrittenFrames());
        fixture.session.close();
    }

    @Test
    public void aStarvedWriterKeepsTheOutputFed() throws Exception {
        Fixture fixture = new Fixture(true, NanoClock.MONOTONIC);
        fixture.session.onDescriptor(descriptor(1));
        for (int i = 0; i < 6; i++) {
            fixture.session.onAccessUnit(unit(1, i, i * FRAME_US));
        }
        assertTrue(awaitPlayback(fixture));

        // Nothing arrives for three frames worth of wall time; the writer has to fill the gap with
        // silence instead of letting the track run dry.
        Thread.sleep(100L);

        // Six frames were received, so every frame beyond the sixth is silence the writer produced.
        assertTrue("the writer must keep the track fed",
                fixture.session.getWrittenFrames() > 6L);
        // The starvation is counted once per episode, which is the rate the device report reads.
        assertTrue(fixture.metrics.count(PipelineMetrics.Counter.AUDIO_STARVED) >= 1L);
        fixture.session.close();
    }

    private static boolean awaitPlayback(Fixture fixture) throws InterruptedException {
        final long deadlineNs = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadlineNs) {
            if (fixture.sink.getPlayCount() > 0) {
                return true;
            }
            Thread.sleep(1L);
        }
        return false;
    }

    private static boolean awaitWriterState(ClientAudioSession session, Thread.State expected)
            throws InterruptedException {
        final long deadlineNs = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadlineNs) {
            if (session.getWriterState() == expected) {
                return true;
            }
            Thread.sleep(1L);
        }
        return false;
    }

    private static AudioDescriptor descriptor(int epoch) {
        return AudioDescriptor.builder(epoch, AudioCodecIds.AAC, AudioCodecIds.AAC_MIME, 48_000,
                        2, 128_000)
                .configParts(Collections.singletonList(
                        new CodecConfigPart(AudioCodecIds.CONFIG_PART_CSD_0,
                                new byte[]{0x12, 0x10})))
                .build();
    }

    private static AudioAccessUnit unit(int epoch, long sequence, long ptsUs) {
        return new AudioAccessUnit(epoch, sequence, ptsUs, new byte[]{0x21, 0x01});
    }

    private static final class Fixture {

        private final FakeAudioDecoder decoder = new FakeAudioDecoder();

        private final FakeAudioSink sink = new FakeAudioSink();

        private final NanoClock clock;

        private final PipelineMetrics metrics;

        private final ClientAudioSession session;

        private Fixture() {
            this(false, new MutableAudioClock(0L));
        }

        private Fixture(boolean asyncWriter, NanoClock clock) {
            this.clock = clock;
            this.metrics = PipelineMetrics.create(clock, 16);
            session = new ClientAudioSession(decoder, sink, null, clock, null, metrics,
                    asyncWriter);
        }

        private void advanceMs(long deltaMs) {
            ((MutableAudioClock) clock).advanceMs(deltaMs);
        }
    }
}
