package com.obby.android.localscreenshare.core.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.obby.android.localscreenshare.core.model.AudioAccessUnit;
import com.obby.android.localscreenshare.core.model.AudioDescriptor;
import com.obby.android.localscreenshare.core.model.CodecConfigPart;
import com.obby.android.localscreenshare.core.support.FakeAudioDecoder;
import com.obby.android.localscreenshare.core.support.FakeAudioSink;
import com.obby.android.localscreenshare.core.support.MutableAudioClock;

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

        fixture.clock.advanceMs(AudioJitterBuffer.MAX_PREFILL_WAIT_MS);
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

        private final MutableAudioClock clock = new MutableAudioClock(0L);

        private final ClientAudioSession session =
                new ClientAudioSession(decoder, sink, null, clock, false);

        private Fixture() {
        }
    }
}
