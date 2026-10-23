package com.obby.android.localscreenshare.core.metrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.obby.android.localscreenshare.core.metrics.MetricsFormatter.Role;
import com.obby.android.localscreenshare.core.net.MediaTransportKind;
import com.obby.android.localscreenshare.core.support.MutableNanoClock;

import org.junit.Test;

import java.util.List;

/**
 * Unit tests for {@link MetricsFormatter}.
 *
 * <p>Expected strings are compared verbatim where the whole line is short, so a key rename or a
 * unit change fails the test instead of silently making historical log lines incomparable.</p>
 */
public class MetricsFormatterTest {

    @Test
    public void serverLine_containsOnlyRecordedMetrics() {
        PipelineMetrics metrics = PipelineMetrics.create(new MutableNanoClock(), 16);
        metrics.record(PipelineMetrics.Window.SEND, 12_300_000L);
        metrics.add(PipelineMetrics.Counter.ACCESS_UNITS, 30L);

        String line = MetricsFormatter.toLogLine(Role.SERVER, metrics.snapshot(1000L), null,
                new MetricsRate(1000L, 30L, 0L));

        assertEquals("lss.metrics role=server period=1000 tx.send.p50=12.3"
                + " tx.send.p95=12.3 tx.send.max=12.3 enc.auRate=30.0 enc.bitrate=0", line);
    }

    @Test
    public void serverLine_omitsRatesThatWereNotMeasured() {
        PipelineMetrics metrics = PipelineMetrics.create(new MutableNanoClock(), 16);
        metrics.add(PipelineMetrics.Counter.ACCESS_UNITS, 30L);

        String line = MetricsFormatter.toLogLine(Role.SERVER, metrics.snapshot(1000L));

        assertFalse(line.contains("enc.auRate"));
        assertFalse(line.contains("enc.bitrate"));
    }

    @Test
    public void emptySnapshot_printsOnlyTheFramingFields() {
        String line = MetricsFormatter.toLogLine(Role.SERVER, PipelineMetrics.NONE.snapshot(5000L));

        assertEquals("lss.metrics role=server period=5000", line);
        assertFalse(line.contains("tx."));
        assertFalse(line.contains("enc.cap2out"));
    }

    @Test
    public void emptySnapshot_withAnInterval_printsZeroRates() {
        String line = MetricsFormatter.toLogLine(Role.SERVER, PipelineMetrics.NONE.snapshot(5000L),
                null, new MetricsRate(5000L, 0L, 0L));

        assertEquals("lss.metrics role=server period=5000 enc.auRate=0.0 enc.bitrate=0", line);
    }

    @Test
    public void serverLine_printsPercentilesAndCounters() {
        PipelineMetrics metrics = PipelineMetrics.create(new MutableNanoClock(), 16);
        metrics.record(PipelineMetrics.Window.CAP_TO_OUT, 5_000_000L);
        metrics.record(PipelineMetrics.Window.CAP_TO_OUT, 25_000_000L);
        metrics.record(PipelineMetrics.Window.OUT_INTERVAL, 33_300_000L);
        metrics.record(PipelineMetrics.Window.AU_BYTES, 41_233L);
        metrics.record(PipelineMetrics.Window.QUEUE_DEPTH, 3L);
        metrics.record(PipelineMetrics.Window.QUEUE_RESIDENCY, 120_000_000L);
        metrics.record(PipelineMetrics.Window.SEND, 400_000L);
        metrics.record(PipelineMetrics.Window.SEND, 3_900_000L);
        metrics.add(PipelineMetrics.Counter.JOIN_REQUESTS, 1L);
        metrics.add(PipelineMetrics.Counter.JOIN_POINTS, 2L);
        metrics.add(PipelineMetrics.Counter.SENT_UNITS, 1489L);
        metrics.add(PipelineMetrics.Counter.DROPPED_UNITS, 4L);
        metrics.add(PipelineMetrics.Counter.RESYNCS, 1L);
        metrics.setGauge(PipelineMetrics.Gauge.PEER_COUNT, 2L);

        String line = MetricsFormatter.toLogLine(Role.SERVER, metrics.snapshot(5000L));

        assertTrue(line.contains(" enc.cap2out.p50=5.0"));
        assertTrue(line.contains(" enc.cap2out.p95=25.0"));
        assertTrue(line.contains(" enc.cap2out.max=25.0"));
        assertTrue(line.contains(" enc.outInterval.p50=33.3"));
        assertTrue(line.contains(" enc.auBytes.p95=41233"));
        assertTrue(line.contains(" tx.send"));
        assertTrue(line.contains(" tx.send.p50=0.4"));
        assertTrue(line.contains(" tx.send.p95=3.9"));
        assertTrue(line.contains(" tx.qDepth.max=3"));
        assertTrue(line.contains(" tx.qResidency.max=120.0"));
        assertTrue(line.contains(" tx.clients=2"));
        assertTrue(line.contains(" kf.request=1"));
        assertTrue(line.contains(" kf.joins=2"));
        assertFalse(line.contains("tx.sent"));
    }

    @Test
    public void sessionLine_printsSendStageAndSessionCounters() {
        PipelineMetrics session = PipelineMetrics.create(new MutableNanoClock(), 16);
        session.record(PipelineMetrics.Window.SEND, 12_300_000L);
        session.record(PipelineMetrics.Window.QUEUE_RESIDENCY, 120_000_000L);
        session.record(PipelineMetrics.Window.QUEUE_DEPTH, 4L);
        session.add(PipelineMetrics.Counter.SENT_UNITS, 1489L);
        session.add(PipelineMetrics.Counter.RESYNCS, 2L);

        String line = MetricsFormatter.toSessionLogLine(session.snapshot(5000L), "1a2b3c4d");

        assertEquals("lss.metrics role=server period=5000 sid=1a2b3c4d chan=grpc tx.send.p50=12.3"
                + " tx.send.p95=12.3 tx.send.max=12.3 tx.qResidency.p95=120.0"
                + " tx.qResidency.max=120.0 tx.qDepth.max=4 tx.sent=1489 tx.resync=2", line);
    }

    @Test
    public void sessionLine_printsTheChannelOfTheSession() {
        PipelineMetrics session = PipelineMetrics.create(new MutableNanoClock(), 16);
        session.add(PipelineMetrics.Counter.SENT_UNITS, 7L);

        String line = MetricsFormatter.toSessionLogLine(session.snapshot(5000L), "1a2b3c4d",
                MediaTransportKind.TCP);

        assertEquals("lss.metrics role=server period=5000 sid=1a2b3c4d chan=tcp tx.sent=7", line);
    }

    @Test
    public void sessionLine_printsTheDatagramChannel() {
        PipelineMetrics session = PipelineMetrics.create(new MutableNanoClock(), 16);

        String line = MetricsFormatter.toSessionLogLine(session.snapshot(5000L), "1a2b3c4d",
                MediaTransportKind.UDP);

        assertTrue(line.contains(" chan=udp"));
    }

    @Test
    public void sessionLine_printsTheChannelSwitchCount() {
        PipelineMetrics session = PipelineMetrics.create(new MutableNanoClock(), 16);
        session.add(PipelineMetrics.Counter.MEDIA_CHANNEL_SWITCHES, 2L);

        String line = MetricsFormatter.toSessionLogLine(session.snapshot(5000L), "1a2b3c4d");

        assertTrue(line.contains(" tx.chanSwitch=2"));
    }

    @Test
    public void serverLine_printsTheFragmentCount() {
        PipelineMetrics metrics = PipelineMetrics.create(new MutableNanoClock(), 16);
        metrics.add(PipelineMetrics.Counter.FRAGMENTS_SENT, 5L);

        String line = MetricsFormatter.toLogLine(Role.SERVER, metrics.snapshot(5000L));

        assertTrue(line.contains(" tx.frag=5"));
    }

    @Test
    public void sessionLine_keepsOnlyTheFramingFieldsWhenNothingWasRecorded() {
        assertEquals("lss.metrics role=server period=5000 chan=grpc",
                MetricsFormatter.toSessionLogLine(PipelineMetrics.NONE.snapshot(5000L), null));
    }

    @Test
    public void clientLine_printsRoundTripKindAndCounters() {
        PipelineMetrics metrics = PipelineMetrics.create(new MutableNanoClock(), 16);
        metrics.record(PipelineMetrics.Window.RTT, 4_200_000L);
        metrics.increment(PipelineMetrics.Counter.RTT_SAMPLES);
        metrics.increment(PipelineMetrics.Counter.RTT_LOST);
        metrics.setGauge(PipelineMetrics.Gauge.RTT_EWMA, 4_500_000L);
        metrics.setGauge(PipelineMetrics.Gauge.RTT_KIND, PipelineMetrics.RTT_KIND_KEYFRAME_UPPER);
        metrics.record(PipelineMetrics.Window.ARRIVAL, 33_000_000L);
        metrics.add(PipelineMetrics.Counter.STALLS, 1L);
        metrics.add(PipelineMetrics.Counter.SEQUENCE_GAPS, 0L);

        String line = MetricsFormatter.toLogLine(Role.CLIENT, metrics.snapshot(5000L));

        assertTrue(line.startsWith("lss.metrics role=client period=5000"));
        assertTrue(line.contains(" rtt.p50=4.2"));
        assertTrue(line.contains(" rtt.ewma=4.5"));
        assertTrue(line.contains(" rtt.lost=1"));
        assertTrue(line.contains(" rtt.kind=keyframe_upper"));
        assertTrue(line.contains(" rx.arr.p50=33.0"));
        assertTrue(line.contains(" rx.stall=1"));
        assertFalse(line.contains("rx.seqGap"));
    }

    @Test
    public void clientLine_printsTheDatagramCountersOfTheWeakNetworkTier() {
        PipelineMetrics metrics = PipelineMetrics.create(new MutableNanoClock(), 16);
        metrics.add(PipelineMetrics.Counter.DATAGRAMS_RECEIVED, 240L);
        metrics.add(PipelineMetrics.Counter.DATAGRAMS_LOST, 6L);
        metrics.add(PipelineMetrics.Counter.DATAGRAMS_DUPLICATE, 2L);
        metrics.add(PipelineMetrics.Counter.DATAGRAMS_REORDERED, 3L);
        metrics.add(PipelineMetrics.Counter.ACCESS_UNITS_INCOMPLETE, 4L);
        metrics.add(PipelineMetrics.Counter.MEDIA_AUTH_FAILURES, 1L);
        metrics.add(PipelineMetrics.Counter.UDP_JOIN_POINT_REQUESTS, 9L);

        String line = MetricsFormatter.toLogLine(Role.CLIENT, metrics.snapshot(5000L));

        assertTrue(line.contains(" udp.recv=240"));
        assertTrue(line.contains(" udp.lost=6"));
        assertTrue(line.contains(" udp.dup=2"));
        assertTrue(line.contains(" udp.reorder=3"));
        assertTrue(line.contains(" udp.auIncomplete=4"));
        assertTrue(line.contains(" udp.authFail=1"));
        assertTrue(line.contains(" udp.idrReq=9"));
    }

    @Test
    public void clientLine_printsTheDatagramGapAndTheDropReasons() {
        PipelineMetrics metrics = PipelineMetrics.create(new MutableNanoClock(), 16);
        metrics.add(PipelineMetrics.Counter.DATAGRAMS_RECEIVED, 240L);
        metrics.record(PipelineMetrics.Window.FRAGMENT_GAP, 3_000_000L);
        metrics.record(PipelineMetrics.Window.FRAGMENT_GAP, 7_000_000L);
        metrics.record(PipelineMetrics.Window.FRAGMENT_GAP, 25_000_000L);
        metrics.add(PipelineMetrics.Counter.DATAGRAMS_LATE, 2L);
        metrics.add(PipelineMetrics.Counter.ACCESS_UNITS_TIMED_OUT, 3L);
        metrics.setGauge(PipelineMetrics.Gauge.MEDIA_HOLD_UP_MS, 10L);

        String line = MetricsFormatter.toLogLine(Role.CLIENT, metrics.snapshot(5000L));

        assertTrue(line.contains(" udp.gap.p50=7.0"));
        assertTrue(line.contains(" udp.gap.p95=25.0"));
        assertTrue(line.contains(" udp.gap.max=25.0"));
        assertTrue(line.contains(" udp.late=2"));
        assertTrue(line.contains(" udp.hold=10"));
        assertTrue(line.contains(" udp.auTimeout=3"));
    }

    /**
     * A unit discarded because a newer one was delivered is counted under its own reason.
     *
     * <p>{@code udp.auDrop} was registered with no producer. A superseded drop is the healthy path -
     * the pipeline moved on - while a timeout is the window expiring; the two lead to opposite
     * conclusions about a widened hold-up, so they cannot share one counter.</p>
     */
    @Test
    public void clientLine_printsTheSupersededDropReason() {
        PipelineMetrics metrics = PipelineMetrics.create(new MutableNanoClock(), 16);
        metrics.add(PipelineMetrics.Counter.DATAGRAMS_RECEIVED, 240L);
        metrics.add(PipelineMetrics.Counter.ACCESS_UNITS_INCOMPLETE, 5L);
        metrics.add(PipelineMetrics.Counter.ACCESS_UNITS_SUPERSEDED, 2L);
        metrics.add(PipelineMetrics.Counter.ACCESS_UNITS_TIMED_OUT, 3L);

        String line = MetricsFormatter.toLogLine(Role.CLIENT, metrics.snapshot(5000L));

        assertTrue(line.contains(" udp.auIncomplete=5"));
        assertTrue(line.contains(" udp.auDrop=2"));
        assertTrue(line.contains(" udp.auTimeout=3"));
    }

    @Test
    public void clientLine_omitsTheDatagramBlockWhenNothingArrived() {
        PipelineMetrics metrics = PipelineMetrics.create(new MutableNanoClock(), 16);
        metrics.add(PipelineMetrics.Counter.ACCESS_UNITS, 10L);

        String line = MetricsFormatter.toLogLine(Role.CLIENT, metrics.snapshot(5000L));

        assertFalse(line.contains("udp."));
    }

    @Test
    public void clientLine_omitsADatagramCounterThatStayedZero() {
        PipelineMetrics metrics = PipelineMetrics.create(new MutableNanoClock(), 16);
        metrics.add(PipelineMetrics.Counter.DATAGRAMS_RECEIVED, 3L);

        String line = MetricsFormatter.toLogLine(Role.CLIENT, metrics.snapshot(5000L));

        assertTrue(line.contains(" udp.recv=3"));
        assertFalse(line.contains("udp.lost"));
        assertFalse(line.contains("udp.authFail"));
        assertFalse(line.contains("udp.gap"));
        assertFalse(line.contains("udp.late"));
        assertFalse(line.contains("udp.hold"));
        assertFalse(line.contains("udp.auTimeout"));
        assertFalse(line.contains("udp.auDrop"));
    }

    @Test
    public void clientLine_printsIntervalRatesWhenMeasured() {
        PipelineMetrics metrics = PipelineMetrics.create(new MutableNanoClock(), 16);
        metrics.add(PipelineMetrics.Counter.ACCESS_UNITS, 150L);
        metrics.add(PipelineMetrics.Counter.ACCESS_UNIT_BYTES, 1_250_000L);

        String line = MetricsFormatter.toLogLine(Role.CLIENT, metrics.snapshot(5000L), "1a2b3c4d",
                new MetricsRate(5_000L, 150L, 1_250_000L));

        assertTrue(line.contains(" rx.auRate=30.0"));
        assertTrue(line.contains(" rx.bitrate=2000000"));
        assertFalse(MetricsFormatter.toLogLine(Role.CLIENT, metrics.snapshot(5000L))
                .contains("rx.auRate"));
    }

    @Test
    public void clientLine_printsCatchUpCountersAndReasonOnlyAfterAnEpisode() {
        PipelineMetrics metrics = PipelineMetrics.create(new MutableNanoClock(), 16);
        assertFalse(MetricsFormatter.toLogLine(Role.CLIENT, metrics.snapshot(5000L))
                .contains("rx.catchup"));

        metrics.add(PipelineMetrics.Counter.CATCH_UP_EPISODES, 2L);
        metrics.add(PipelineMetrics.Counter.CATCH_UP_SHEDS, 512L);
        metrics.add(PipelineMetrics.Counter.CONVERGENCE_FAILURES, 1L);
        metrics.setGauge(PipelineMetrics.Gauge.CATCH_UP_REASON,
                PipelineMetrics.CATCH_UP_REASON_QUEUE_DEPTH);
        metrics.record(PipelineMetrics.Window.CATCH_UP_LATENCY, 420_000_000L);

        String line = MetricsFormatter.toLogLine(Role.CLIENT, metrics.snapshot(5000L));

        assertTrue(line.contains(" rx.catchup=2"));
        assertTrue(line.contains(" rx.catchup.shed=512"));
        assertTrue(line.contains(" rx.converge.fail=1"));
        assertTrue(line.contains(" rx.catchup.why=qDepth"));
        assertTrue(line.contains(" rx.catchup.lat.p50=420.0"));
    }

    @Test
    public void catchUpReason_externalIsPrintedAsExt() {
        PipelineMetrics metrics = PipelineMetrics.create(new MutableNanoClock(), 16);
        metrics.setGauge(PipelineMetrics.Gauge.CATCH_UP_REASON,
                PipelineMetrics.CATCH_UP_REASON_EXTERNAL);

        String line = MetricsFormatter.toLogLine(Role.CLIENT, metrics.snapshot(5000L));

        assertTrue(line.contains(" rx.catchup.why=ext"));
    }

    @Test
    public void decLowLat_printsWhenOneOrTwo() {
        PipelineMetrics metrics = PipelineMetrics.create(new MutableNanoClock(), 16);

        metrics.setGauge(PipelineMetrics.Gauge.DECODER_LOW_LATENCY, 1L);
        assertTrue(MetricsFormatter.toLogLine(Role.CLIENT, metrics.snapshot(5000L))
                .contains(" dec.lowLat=1"));

        metrics.setGauge(PipelineMetrics.Gauge.DECODER_LOW_LATENCY, 2L);
        assertTrue(MetricsFormatter.toLogLine(Role.CLIENT, metrics.snapshot(5000L))
                .contains(" dec.lowLat=2"));
    }

    @Test
    public void decLowLat_isOmittedForZero() {
        PipelineMetrics metrics = PipelineMetrics.create(new MutableNanoClock(), 16);

        assertFalse(MetricsFormatter.toLogLine(Role.CLIENT, metrics.snapshot(5000L))
                .contains("dec.lowLat"));
    }

    @Test
    public void thermalHeadroomAndTier_printWhenSampled() {
        PipelineMetrics metrics = PipelineMetrics.create(new MutableNanoClock(), 16);
        metrics.setGauge(PipelineMetrics.Gauge.THERMAL_HEADROOM_PERMILLE, 1_234L);
        metrics.setGauge(PipelineMetrics.Gauge.THERMAL_TIER, 2L);

        String line = MetricsFormatter.toLogLine(Role.SERVER, metrics.snapshot(5000L));

        assertTrue(line.contains(" thermalHeadroom=1234"));
        assertTrue(line.contains(" thermalTier=2"));
    }

    @Test
    public void thermalGauges_areOmittedWhenZero() {
        PipelineMetrics metrics = PipelineMetrics.create(new MutableNanoClock(), 16);

        String line = MetricsFormatter.toLogLine(Role.SERVER, metrics.snapshot(5000L));

        assertFalse(line.contains("thermalHeadroom"));
        assertFalse(line.contains("thermalTier"));
    }

    @Test
    public void clientLine_printsTheAudioBlockAfterTheVideoFields() {
        PipelineMetrics metrics = PipelineMetrics.create(new MutableNanoClock(), 16);
        metrics.record(PipelineMetrics.Window.AV_OFFSET, 3_100_000L);
        metrics.record(PipelineMetrics.Window.AV_OFFSET, 12_400_000L);
        metrics.add(PipelineMetrics.Counter.AUDIO_FRAMES, 2_530L);
        metrics.add(PipelineMetrics.Counter.AUDIO_DROPPED, 7L);
        metrics.add(PipelineMetrics.Counter.AUDIO_STARVED, 3L);
        metrics.add(PipelineMetrics.Counter.AUDIO_DRIFT_INSERT, 2L);

        String line = MetricsFormatter.toLogLine(Role.CLIENT, metrics.snapshot(5000L));

        assertTrue(line.startsWith("lss.metrics role=client period=5000"));
        assertTrue(line.contains(" av.offset.p50=3.1"));
        assertTrue(line.contains(" av.offset.p95=12.4"));
        assertTrue(line.contains(" audio.frames=2530"));
        assertTrue(line.contains(" audio.dropped=7"));
        assertTrue(line.contains(" audio.starved=3"));
        assertTrue(line.contains(" audio.driftInsert=2"));
        assertTrue(line.indexOf("audio.frames") > line.indexOf("av.offset"));
    }

    @Test
    public void clientLine_printsAudioCountersIncludingZerosOnceTheTrackReceives() {
        PipelineMetrics metrics = PipelineMetrics.create(new MutableNanoClock(), 16);
        metrics.add(PipelineMetrics.Counter.AUDIO_FRAMES, 470L);

        String line = MetricsFormatter.toLogLine(Role.CLIENT, metrics.snapshot(5000L));

        // A zero starvation count is the answer to "is the playback buffer holding"; skipping zero
        // values is what made the previous device report unreadable.
        assertTrue(line.contains(" audio.frames=470"));
        assertTrue(line.contains(" audio.dropped=0"));
        assertTrue(line.contains(" audio.starved=0"));
        assertTrue(line.contains(" audio.driftInsert=0"));
        assertTrue(line.contains(" audio.driftDrop=0"));
    }

    @Test
    public void clientLine_omitsTheAudioBlockWithoutAudio() {
        String line = MetricsFormatter.toLogLine(Role.CLIENT,
                PipelineMetrics.NONE.snapshot(5000L));

        assertEquals("lss.metrics role=client period=5000", line);
        assertFalse(line.contains("av.offset"));
        assertFalse(line.contains("audio."));
    }

    @Test
    public void clientLine_defaultsToPingPongKind() {
        PipelineMetrics metrics = PipelineMetrics.create(new MutableNanoClock(), 16);
        metrics.increment(PipelineMetrics.Counter.RTT_SAMPLES);

        String line = MetricsFormatter.toLogLine(Role.CLIENT, metrics.snapshot(1000L));

        assertTrue(line.contains(" rtt.kind=ping_pong"));
    }

    @Test
    public void logLine_includesTheSessionIdentifierOnlyWhenGiven() {
        MetricsSnapshot snapshot = PipelineMetrics.NONE.snapshot(1000L);

        assertTrue(MetricsFormatter.toLogLine(Role.CLIENT, snapshot, "1a2b3c4d")
                .contains(" sid=1a2b3c4d"));
        assertFalse(MetricsFormatter.toLogLine(Role.CLIENT, snapshot).contains("sid="));
    }

    @Test
    public void hud_hasFourStableLines() {
        PipelineMetrics metrics = PipelineMetrics.create(new MutableNanoClock(), 16);
        metrics.add(PipelineMetrics.Counter.ACCESS_UNITS, 30L);
        metrics.add(PipelineMetrics.Counter.ACCESS_UNIT_BYTES, 475_000L);
        metrics.record(PipelineMetrics.Window.RTT, 4_200_000L);
        metrics.record(PipelineMetrics.Window.QUEUE_DEPTH, 2L);
        metrics.record(PipelineMetrics.Window.DECODE_LATENCY, 12_000_000L);
        metrics.record(PipelineMetrics.Window.DECODE_LATENCY, 31_000_000L);
        metrics.record(PipelineMetrics.Window.PRESENT_LATE, 0L);
        metrics.record(PipelineMetrics.Window.ROWD, 8_000_000L);
        metrics.add(PipelineMetrics.Counter.STALLS, 1L);
        metrics.record(PipelineMetrics.Window.AV_OFFSET, 3_100_000L);
        metrics.record(PipelineMetrics.Window.AV_OFFSET, 12_400_000L);
        metrics.add(PipelineMetrics.Counter.AUDIO_FRAMES, 2_530L);
        metrics.add(PipelineMetrics.Counter.AUDIO_STARVED, 3L);

        List<String> lines = MetricsFormatter.toHud(metrics.snapshot(1000L),
                new MetricsRate(1000L, 30L, 475_000L));

        assertEquals(4, lines.size());
        assertEquals("30.0 fps | 3.8 Mbps | RTT 4.2 ms", lines.get(0));
        assertEquals("q 2 | dec 12.0 ms (p95 31.0) | late 0.0", lines.get(1));
        assertEquals("rowd +8.0 | stall 1 | drop 0 | cu 0 | kf 0", lines.get(2));
        assertEquals("av 3.1 ms (p95 12.4) | af 2530 | ad 0 | as 3", lines.get(3));
    }

    @Test
    public void hud_ofEmptySnapshot_keepsTheShape() {
        List<String> lines = MetricsFormatter.toHud(PipelineMetrics.NONE.snapshot(1000L));

        assertEquals(4, lines.size());
        assertEquals("n/a fps | n/a Mbps | RTT n/a", lines.get(0));
        assertEquals("q 0 | dec n/a | late 0.0", lines.get(1));
        assertEquals("rowd +0.0 | stall 0 | drop 0 | cu 0 | kf 0", lines.get(2));
        assertEquals("av n/a | af 0 | ad 0 | as 0", lines.get(3));
    }

    @Test
    public void hud_ofSilentInterval_printsZeroRates() {
        List<String> lines = MetricsFormatter.toHud(PipelineMetrics.NONE.snapshot(1000L),
                MetricsRate.EMPTY);

        assertEquals("n/a fps | n/a Mbps | RTT n/a", lines.get(0));
        assertEquals("0.0 fps | 0.0 Mbps | RTT n/a",
                MetricsFormatter.toHud(PipelineMetrics.NONE.snapshot(1000L),
                        new MetricsRate(1000L, 0L, 0L)).get(0));
    }

    @Test
    public void hud_isUnmodifiable() {
        List<String> lines = MetricsFormatter.toHud(PipelineMetrics.NONE.snapshot(1000L));

        assertThrows(UnsupportedOperationException.class, () -> lines.add("extra"));
    }

    @Test
    public void deviceLine_printsEveryReading() {
        DeviceProfile profile = new DeviceProfile(2L, 352L, 12L * 1024L * 1024L,
                30L * 1024L * 1024L, 48L);

        assertEquals("lss.device role=client thermal=2 battTempC=35.2 heapMB=42.0 threads=48",
                MetricsFormatter.toDeviceLine(Role.CLIENT, profile));
    }

    @Test
    public void deviceLine_marksMissingReadingsAsUnknown() {
        DeviceProfile profile = new DeviceProfile(DeviceProfile.UNKNOWN, DeviceProfile.UNKNOWN,
                DeviceProfile.UNKNOWN, DeviceProfile.UNKNOWN, DeviceProfile.UNKNOWN);

        assertEquals("lss.device role=server thermal=n/a battTempC=n/a heapMB=n/a threads=n/a",
                MetricsFormatter.toDeviceLine(Role.SERVER, profile));
    }

    @Test
    public void nullArguments_areRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> MetricsFormatter.toLogLine(null, PipelineMetrics.NONE.snapshot(1000L)));
        assertThrows(IllegalArgumentException.class,
                () -> MetricsFormatter.toLogLine(Role.SERVER, null));
        assertThrows(IllegalArgumentException.class, () -> MetricsFormatter.toHud(null));
        assertThrows(IllegalArgumentException.class,
                () -> MetricsFormatter.toSessionLogLine(null, "1a2b3c4d"));
        assertThrows(IllegalArgumentException.class,
                () -> MetricsFormatter.toDeviceLine(null, new DeviceProfile(0L, 0L, 0L, 0L, 0L)));
        assertThrows(IllegalArgumentException.class,
                () -> MetricsFormatter.toDeviceLine(Role.CLIENT, null));
    }
}
