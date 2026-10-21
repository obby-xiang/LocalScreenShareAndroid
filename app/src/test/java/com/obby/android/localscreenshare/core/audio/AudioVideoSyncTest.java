package com.obby.android.localscreenshare.core.audio;

import static org.junit.Assert.assertEquals;
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
 *
 * <p>The offset is measured in stream microseconds on both sides. Three regressions that used to
 * make the guard fire on a healthy session are covered explicitly: the device uptime leaking into
 * the comparison, the output buffer counting as a constant lag, and a fixed offset being chased with
 * a silence frame every cooldown period for the whole session.</p>
 */
public class AudioVideoSyncTest {

    private static final long FRAME_US = 21_333L;

    private static final long FRAME_SAMPLES = 1_024L;

    private static final int SAMPLE_RATE = 48_000;

    /**
     * Twenty-one hours of device uptime in nanoseconds; the order of magnitude of the defect.
     */
    private static final long UPTIME_NS = 76_289_000_000_000L;

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
    public void deviceUptimeDoesNotLeakIntoTheOffset() {
        Fixture fixture = new Fixture();
        fixture.clock.setNs(UPTIME_NS);
        fixture.sessionClock.anchorIfUnset(0L, UPTIME_NS);
        fixture.session.onDescriptor(descriptor());
        fixture.sink.setQueuedFrames(3 * FRAME_SAMPLES);

        // A 3-frame output buffer with playback keeping pace with the presented frames.
        for (int written = 1; written <= 8; written++) {
            final long playedFrames = Math.max(0L, written - 3);
            fixture.clock.setNs(UPTIME_NS + playedFrames * FRAME_US * 1_000L);
            fixture.session.onAccessUnit(unit((written - 1) * FRAME_US));
        }

        assertTrue(Math.abs(fixture.session.getLastDriftMs()) <= 1L);
        assertEquals(0L, fixture.counter(PipelineMetrics.Counter.AUDIO_DRIFT_INSERT));
        assertEquals(0L, fixture.counter(PipelineMetrics.Counter.AUDIO_DROPPED));
    }

    @Test
    public void aBufferedOutputDoesNotCountAsDrift() {
        Fixture fixture = new Fixture();
        fixture.sessionClock.anchorIfUnset(0L, fixture.clock.nowNs());
        fixture.session.onDescriptor(descriptor());
        fixture.sink.setQueuedFrames(3 * FRAME_SAMPLES);

        for (int written = 1; written <= 40; written++) {
            final long playedFrames = Math.max(0L, written - 3);
            fixture.clock.setNs(1_000_000_000L + playedFrames * FRAME_US * 1_000L);
            fixture.session.onAccessUnit(unit((written - 1) * FRAME_US));
            // Without the queued-frame subtraction every measurement would read 64 ms behind and
            // insert a silence frame every cooldown period.
            assertTrue(Math.abs(fixture.session.getLastDriftMs()) <= 1L);
        }

        assertEquals(0L, fixture.counter(PipelineMetrics.Counter.AUDIO_DRIFT_INSERT));
        assertEquals(0L, fixture.counter(PipelineMetrics.Counter.AUDIO_DROPPED));
    }

    @Test
    public void audioThatMovesAheadOfTheTimelineIsDelayedWithSilence() {
        Fixture fixture = new Fixture();
        fixture.sessionClock.anchorIfUnset(0L, fixture.clock.nowNs());
        fixture.session.onDescriptor(descriptor());
        // Everything written has already left the track, so the audio advances as fast as it is fed.
        fixture.sink.setPresentedFrames(Long.MAX_VALUE / 2L);

        // The audio clock runs ~4 % faster than the timeline: the offset grows by 0.9 ms per frame.
        for (int index = 0; index < 110; index++) {
            fixture.clock.setNs(1_000_000_000L + index * (FRAME_US - 900L) * 1_000L);
            fixture.session.onAccessUnit(unit(index * FRAME_US));
        }

        assertEquals(1L, fixture.counter(PipelineMetrics.Counter.AUDIO_DRIFT_INSERT));
        assertEquals(0L, fixture.counter(PipelineMetrics.Counter.AUDIO_DROPPED));
        assertTrue(fixture.session.getLastDriftMs() >= 100L);
    }

    @Test
    public void audioThatMovesBehindTheTimelineDropsABufferedFrame() {
        Fixture fixture = new Fixture();
        fixture.sessionClock.anchorIfUnset(0L, fixture.clock.nowNs());
        fixture.session.onDescriptor(descriptor());
        fixture.sink.setPresentedFrames(0L);
        fixture.session.onAccessUnit(unit(0L));
        fixture.sink.setPresentedFrames(FRAME_SAMPLES);
        // The timeline jumps 2.2 s ahead while the audio keeps its rate.
        fixture.clock.setNs(3_200_000_000L);

        // The gap leaves four silence frames and the decoded frame queued; playback writes one.
        fixture.session.onAccessUnit(unit(5 * FRAME_US));

        assertTrue(fixture.session.getLastDriftMs() <= -1_000L);
        assertEquals(1L, fixture.counter(PipelineMetrics.Counter.AUDIO_DRIFT_DROP));
        assertEquals(0L, fixture.counter(PipelineMetrics.Counter.AUDIO_DRIFT_INSERT));
        assertEquals(3, fixture.session.getPendingFrames());
    }

    @Test
    public void aStableOffsetIsReportedButNotCorrected() {
        Fixture fixture = new Fixture();
        fixture.sessionClock.anchorIfUnset(0L, fixture.clock.nowNs());
        fixture.session.onDescriptor(descriptor());
        fixture.sink.setQueuedFrames(3 * FRAME_SAMPLES);

        // A constant 130 ms offset, far above the threshold but never moving.
        for (int written = 1; written <= 240; written++) {
            final long playedFrames = Math.max(0L, written - 3);
            fixture.clock.setNs(1_000_000_000L + playedFrames * FRAME_US * 1_000L
                    - 130_000_000L);
            fixture.session.onAccessUnit(unit((written - 1) * FRAME_US));
        }

        assertTrue(fixture.session.getLastDriftMs() >= 120L);
        assertEquals(0L, fixture.counter(PipelineMetrics.Counter.AUDIO_DRIFT_INSERT));
        assertEquals(0L, fixture.counter(PipelineMetrics.Counter.AUDIO_DROPPED));
    }

    @Test
    public void theGuardCorrectsAtMostOncePerCooldown() {
        Fixture fixture = new Fixture();
        fixture.sessionClock.anchorIfUnset(0L, fixture.clock.nowNs());
        fixture.session.onDescriptor(descriptor());
        fixture.sink.setPresentedFrames(Long.MAX_VALUE / 2L);

        for (int index = 0; index < 150; index++) {
            fixture.clock.setNs(1_000_000_000L + index * (FRAME_US - 900L) * 1_000L);
            fixture.session.onAccessUnit(unit(index * FRAME_US));
        }

        assertEquals(1L, fixture.counter(PipelineMetrics.Counter.AUDIO_DRIFT_INSERT)
                + fixture.counter(PipelineMetrics.Counter.AUDIO_DRIFT_DROP));
    }

    @Test
    public void aStandaloneAudioTimelineNeverMeasuresAnOffset() {
        FakeAudioSink sink = new FakeAudioSink();
        MutableAudioClock clock = new MutableAudioClock(1_000_000_000L);
        ClientAudioSession session = new ClientAudioSession(new FakeAudioDecoder(), sink, null,
                clock, null, PipelineMetrics.NONE, false);
        session.onDescriptor(descriptor());
        sink.setPresentedFrames(FRAME_SAMPLES);

        session.onAccessUnit(unit(0L));

        assertEquals(0L, session.getLastDriftMs());
        assertEquals(0, session.getPendingFrames());
    }

    @Test
    public void offsetIsRecordedIntoTheMetricScope() {
        Fixture fixture = new Fixture();
        fixture.sessionClock.anchorIfUnset(0L, fixture.clock.nowNs());
        fixture.session.onDescriptor(descriptor());
        fixture.sink.setPresentedFrames(Long.MAX_VALUE / 2L);

        for (int index = 0; index < 110; index++) {
            fixture.clock.setNs(1_000_000_000L + index * (FRAME_US - 900L) * 1_000L);
            fixture.session.onAccessUnit(unit(index * FRAME_US));
        }

        MetricsSnapshot snapshot = fixture.metrics.snapshot(1_000L);
        assertNotNull(snapshot);
        assertTrue(snapshot.getWindow(PipelineMetrics.Window.AV_OFFSET).getCount() > 0);
        assertTrue(snapshot.getWindow(PipelineMetrics.Window.AV_OFFSET).getMax() >= 100_000_000L);
        assertTrue(snapshot.getWindow(PipelineMetrics.Window.AV_OFFSET).getMax() <= 120_000_000L);
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
        return AudioDescriptor.builder(1, AudioCodecIds.AAC, AudioCodecIds.AAC_MIME, SAMPLE_RATE, 2,
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

        private long counter(PipelineMetrics.Counter counter) {
            return metrics.snapshot(1_000L).getCounter(counter);
        }
    }
}
