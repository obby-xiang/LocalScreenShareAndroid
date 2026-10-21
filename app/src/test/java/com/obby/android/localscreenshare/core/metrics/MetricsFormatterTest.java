package com.obby.android.localscreenshare.core.metrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.obby.android.localscreenshare.core.metrics.MetricsFormatter.Role;
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

        String line = MetricsFormatter.toLogLine(Role.SERVER, metrics.snapshot(1000L));

        assertEquals("lss.metrics role=server period=1000 tx.send.p50=12.3"
                + " tx.send.p95=12.3 tx.send.max=12.3 enc.auRate=30.0 enc.bitrate=0", line);
    }

    @Test
    public void emptySnapshot_printsOnlyTheFramingFields() {
        String line = MetricsFormatter.toLogLine(Role.SERVER, PipelineMetrics.NONE.snapshot(5000L));

        assertEquals("lss.metrics role=server period=5000 enc.auRate=0.0 enc.bitrate=0", line);
        assertFalse(line.contains("tx."));
        assertFalse(line.contains("enc.cap2out"));
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
        assertTrue(line.contains(" tx.sent=1489"));
        assertTrue(line.contains(" tx.drop=4"));
        assertTrue(line.contains(" tx.resync=1"));
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
    public void clientLine_defaultsToPingPongKind() {
        PipelineMetrics metrics = PipelineMetrics.create(new MutableNanoClock(), 16);
        metrics.increment(PipelineMetrics.Counter.RTT_SAMPLES);

        String line = MetricsFormatter.toLogLine(Role.CLIENT, metrics.snapshot(1000L));

        assertTrue(line.contains(" rtt.kind=ping_pong"));
    }

    @Test
    public void hud_hasThreeStableLines() {
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

        List<String> lines = MetricsFormatter.toHud(metrics.snapshot(1000L));

        assertEquals(3, lines.size());
        assertEquals("30.0 fps | 3.8 Mbps | RTT 4.2 ms", lines.get(0));
        assertEquals("q 2 | dec 12.0 ms (p95 31.0) | late 0.0", lines.get(1));
        assertEquals("rowd +8.0 | stall 1 | drop 0 | kf 0", lines.get(2));
    }

    @Test
    public void hud_ofEmptySnapshot_keepsTheShape() {
        List<String> lines = MetricsFormatter.toHud(PipelineMetrics.NONE.snapshot(1000L));

        assertEquals(3, lines.size());
        assertEquals("0.0 fps | 0.0 Mbps | RTT n/a", lines.get(0));
        assertEquals("q 0 | dec n/a | late 0.0", lines.get(1));
        assertEquals("rowd +0.0 | stall 0 | drop 0 | kf 0", lines.get(2));
    }

    @Test
    public void hud_isUnmodifiable() {
        List<String> lines = MetricsFormatter.toHud(PipelineMetrics.NONE.snapshot(1000L));

        assertThrows(UnsupportedOperationException.class, () -> lines.add("extra"));
    }

    @Test
    public void nullArguments_areRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> MetricsFormatter.toLogLine(null, PipelineMetrics.NONE.snapshot(1000L)));
        assertThrows(IllegalArgumentException.class,
                () -> MetricsFormatter.toLogLine(Role.SERVER, null));
        assertThrows(IllegalArgumentException.class, () -> MetricsFormatter.toHud(null));
    }
}
