package com.obby.android.localscreenshare.core.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.obby.android.localscreenshare.core.metrics.MetricsSnapshot;
import com.obby.android.localscreenshare.core.metrics.PipelineMetrics;
import com.obby.android.localscreenshare.core.model.AudioAccessUnit;
import com.obby.android.localscreenshare.core.model.AudioDescriptor;
import com.obby.android.localscreenshare.core.model.CodecConfigPart;
import com.obby.android.localscreenshare.core.render.PresentationPacer;
import com.obby.android.localscreenshare.core.support.FakeAudioDecoder;
import com.obby.android.localscreenshare.core.support.FakeAudioSink;
import com.obby.android.localscreenshare.core.support.MutableAudioClock;
import com.obby.android.localscreenshare.core.support.MutableNanoClock;

import org.junit.Test;

import java.util.Collections;

/**
 * JVM tests for the audio/video timing rules of A5.
 *
 * <p>They exercise the shared {@link SessionClock} from both sides: the video side through its
 * mapping and {@link PresentationPacer} decision, the audio side through the drift guard of
 * {@link ClientAudioSession}. The real H.264 decoder and the real {@code AudioTrack} are device
 * concerns covered by the device checklist; the arithmetic and the decisions are what can be
 * pinned here.</p>
 */
public class AudioVideoSyncTest {

    private static final long FRAME_US = 21_333L;

    @Test
    public void audioFirstAnchorsAndVideoFollows() {
        SessionClock clock = new SessionClock();
        // The first audio frame is audible at 5 s; the first video frame is 500 ms later.
        clock.anchorIfUnset(1_000_000L, 5_000_000_000L);
        assertTrue(clock.isAnchored());

        long videoTargetNs = clock.map(1_500_000L);

        assertEquals(5_500_000_000L, videoTargetNs);
    }

    @Test
    public void videoFirstAnchorsAndAudioFollows() {
        SessionClock clock = new SessionClock();
        clock.anchorIfUnset(0L, 5_000_000_000L);

        long audioTargetNs = clock.map(FRAME_US);

        assertEquals(5_000_000_000L + FRAME_US * 1_000L, audioTargetNs);
    }

    @Test
    public void videoPresentedLateIsPresentedImmediately() {
        SessionClock clock = new SessionClock();
        clock.anchorIfUnset(0L, 1_000_000_000L);
        long nowNs = 1_100_000_000L;
        long targetNs = clock.map(50_000L);

        PresentationPacer.Decision decision =
                new PresentationPacer().decide(nowNs, targetNs, PresentationPacer.UNSET, 0L);

        assertEquals(PresentationPacer.Decision.PRESENT_NOW, decision);
    }

    @Test
    public void videoPresentedEarlyIsScheduledForItsSlot() {
        SessionClock clock = new SessionClock();
        clock.anchorIfUnset(0L, 1_000_000_000L);
        long nowNs = 1_010_000_000L;
        long targetNs = clock.map(40_000L);

        PresentationPacer.Decision decision =
                new PresentationPacer().decide(nowNs, targetNs, PresentationPacer.UNSET, 0L);

        assertEquals(PresentationPacer.Decision.SCHEDULE_AT, decision);
    }

    @Test
    public void audioAheadOfTheTimelineIsMeasuredAsPositiveOffset() {
        Fixture fixture = new Fixture();
        fixture.session.onDescriptor(descriptor());
        // The sink reports 50 ms more elapsed audio than the shared timeline expects.
        fixture.sink.setPlayheadNs(1_050_000_000L);

        fixture.session.onAccessUnit(unit(0L));

        assertTrue(fixture.session.getLastDriftMs() >= 45L);
        assertTrue(fixture.session.getLastDriftMs() <= 55L);
    }

    @Test
    public void audioBehindTheTimelineAsksForSilence() {
        Fixture fixture = new Fixture();
        fixture.session.onDescriptor(descriptor());
        fixture.sink.setPlayheadNs(900_000_000L);

        fixture.session.onAccessUnit(unit(0L));

        assertTrue(fixture.session.getLastDriftMs() <= -45L);
        assertEquals(1, fixture.session.getPendingFrames());
    }

    @Test
    public void offsetIsRecordedIntoTheMetricScope() {
        Fixture fixture = new Fixture();
        fixture.session.onDescriptor(descriptor());
        fixture.sink.setPlayheadNs(1_060_000_000L);

        fixture.session.onAccessUnit(unit(0L));

        MetricsSnapshot snapshot = fixture.metrics.snapshot(1_000L);
        assertNotNull(snapshot);
        assertEquals(1, snapshot.getWindow(PipelineMetrics.Window.AV_OFFSET).getCount());
        assertTrue(snapshot.getWindow(PipelineMetrics.Window.AV_OFFSET).getMax() >= 50_000_000L);
    }

    @Test
    public void audioAnchorsTheSharedClockWhenItArrivesFirst() {
        Fixture fixture = new Fixture();
        fixture.clock.setNs(2_000_000_000L);
        fixture.session.onDescriptor(descriptor());

        fixture.session.onAccessUnit(unit(123_456L));

        assertTrue(fixture.sessionClock.isAnchored());
        assertEquals(123_456L, fixture.sessionClock.getAnchorPtsUs());
        assertEquals(2_000_000_000L, fixture.sessionClock.getAnchorNs());
    }

    private static AudioDescriptor descriptor() {
        return AudioDescriptor.builder(1, AudioCodecIds.AAC, AudioCodecIds.AAC_MIME, 48_000, 2,
                        128_000)
                .configParts(Collections.singletonList(
                        new CodecConfigPart(AudioCodecIds.CONFIG_PART_CSD_0,
                                new byte[]{0x12, 0x10})))
                .build();
    }

    private static AudioAccessUnit unit(long ptsUs) {
        return new AudioAccessUnit(1, ptsUs / FRAME_US, ptsUs, new byte[]{0x21, 0x01});
    }

    private static final class Fixture {

        private final FakeAudioDecoder decoder = new FakeAudioDecoder();

        private final FakeAudioSink sink = new FakeAudioSink();

        private final MutableAudioClock clock = new MutableAudioClock(1_000_000_000L);

        private final SessionClock sessionClock = new SessionClock();

        private final PipelineMetrics metrics =
                PipelineMetrics.create(new MutableNanoClock(), 32);

        private final ClientAudioSession session = new ClientAudioSession(decoder, sink, null,
                clock, sessionClock, metrics, false);
    }
}
